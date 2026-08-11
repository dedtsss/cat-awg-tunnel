package com.zaneschepke.wireguardautotunnel.cat.diagnostics

import com.dedtsss.catawg.core.diagnostics.DiagnosticSource
import com.dedtsss.catawg.core.diagnostics.DiagnosticStore
import com.dedtsss.catawg.core.protocol.CatServerClient
import com.dedtsss.catawg.core.protocol.CatServerCredentialStore
import com.dedtsss.catawg.core.protocol.TimeRange
import com.zaneschepke.wireguardautotunnel.cat.server.CatServerErrorMapper
import com.zaneschepke.wireguardautotunnel.data.cat.CatServerSettingsStore
import java.time.Instant
import java.time.temporal.ChronoUnit
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext

data class CatDiagnosticsSyncResult(
    val uploadedEvents: Int,
    val serverIncidentCount: Int,
    val skipped: Boolean = false,
)

/** Bounded, non-blocking server sync. VPN lifecycle never depends on this coordinator. */
class CatDiagnosticsSyncCoordinator(
    private val store: DiagnosticStore,
    private val client: CatServerClient,
    private val credentials: CatServerCredentialStore,
    private val settingsStore: CatServerSettingsStore,
    private val ioDispatcher: CoroutineDispatcher,
) {
    suspend fun sync(): CatDiagnosticsSyncResult =
        withContext(ioDispatcher) {
            val settings = settingsStore.read()
            if (!settings.diagnosticsUploadEnabled || !settings.isPaired || credentials.read() == null) {
                return@withContext CatDiagnosticsSyncResult(0, 0, skipped = true)
            }
            val now = Instant.now()
            val from =
                settings.lastSyncAt?.let { runCatching { Instant.parse(it) }.getOrNull() }
                    ?: now.minus(48, ChronoUnit.HOURS)
            val events =
                store
                    .events(from, now)
                    .filter { it.source == DiagnosticSource.CLIENT }
                    .takeLast(MAX_PENDING_EVENTS)
            var uploaded = 0
            try {
                events.chunked(BATCH_SIZE).forEach { batch ->
                    retry { client.postDiagnosticEvents(batch) }
                    uploaded += batch.size
                }
                val incidents = retry {
                    client.incidents(TimeRange(from.toString(), now.toString()))
                }
                settingsStore.recordSyncSuccess()
                CatDiagnosticsSyncResult(uploaded, incidents.incidents.size)
            } catch (error: Throwable) {
                settingsStore.recordSyncFailure(
                    CatServerErrorMapper.code(error),
                    events.size - uploaded,
                )
                throw error
            }
        }

    private suspend fun <T> retry(operation: suspend () -> T): T {
        var lastError: Throwable? = null
        RETRY_DELAYS_MS.forEachIndexed { index, waitMs ->
            if (index > 0) delay(waitMs)
            runCatching {
                    return operation()
                }
                .onFailure { lastError = it }
        }
        throw requireNotNull(lastError)
    }

    private companion object {
        const val BATCH_SIZE = 100
        const val MAX_PENDING_EVENTS = 600
        val RETRY_DELAYS_MS = longArrayOf(0, 1_000, 5_000)
    }
}
