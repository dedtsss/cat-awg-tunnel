package com.zaneschepke.wireguardautotunnel.data.cat

import androidx.datastore.preferences.core.stringPreferencesKey
import com.dedtsss.catawg.core.protocol.ServerCapabilities
import com.dedtsss.catawg.core.protocol.normalizeCatServerUrl
import com.dedtsss.catawg.core.protocol.normalizeCertificateFingerprint
import com.zaneschepke.wireguardautotunnel.data.DataStoreManager
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

/**
 * Non-secret Cat Server metadata. Device tokens are intentionally absent and live in the Android
 * Keystore adapter instead.
 */
@Serializable
data class CatServerSettings(
    val serverUrl: String? = null,
    val certificateFingerprint: String? = null,
    val deviceId: String? = null,
    val deviceName: String? = null,
    val lastContactAt: String? = null,
    val lastErrorCode: String? = null,
    val diagnosticsUploadEnabled: Boolean = true,
    val lastSyncAt: String? = null,
    val pendingSyncCount: Int = 0,
    val lastSyncErrorCode: String? = null,
    val memoryEnabled: Boolean = false,
    val capabilities: ServerCapabilities? = null,
) {
    val isConfigured: Boolean
        get() = !serverUrl.isNullOrBlank() && !certificateFingerprint.isNullOrBlank()

    val isPaired: Boolean
        get() = isConfigured && !deviceId.isNullOrBlank()
}

class CatServerSettingsStore(private val dataStoreManager: DataStoreManager) {
    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
    }

    val flow: Flow<CatServerSettings> =
        dataStoreManager.getFromStoreFlow(KEY).map { encoded ->
            encoded?.let {
                runCatching { json.decodeFromString<CatServerSettings>(it) }.getOrNull()
            } ?: CatServerSettings()
        }

    suspend fun read(): CatServerSettings =
        dataStoreManager.getFromStore(KEY)?.let {
            runCatching { json.decodeFromString<CatServerSettings>(it) }.getOrNull()
        } ?: CatServerSettings()

    suspend fun saveEndpoint(serverUrl: String, fingerprint: String) {
        val current = read()
        save(
            current.copy(
                serverUrl = normalizeCatServerUrl(serverUrl),
                certificateFingerprint = normalizeCertificateFingerprint(fingerprint),
                lastErrorCode = null,
            )
        )
    }

    suspend fun markPaired(deviceId: String, deviceName: String, fingerprint: String) {
        val current = read()
        save(
            current.copy(
                deviceId = deviceId,
                deviceName = deviceName,
                certificateFingerprint = normalizeCertificateFingerprint(fingerprint),
                lastContactAt = java.time.Instant.now().toString(),
                lastErrorCode = null,
                lastSyncErrorCode = null,
            )
        )
    }

    suspend fun recordContact(capabilities: ServerCapabilities? = null) {
        val current = read()
        save(
            current.copy(
                lastContactAt = java.time.Instant.now().toString(),
                lastErrorCode = null,
                capabilities = capabilities ?: current.capabilities,
            )
        )
    }

    suspend fun recordError(code: String) {
        val current = read()
        save(current.copy(lastErrorCode = code))
    }

    suspend fun recordSyncSuccess() {
        val current = read()
        save(
            current.copy(
                lastSyncAt = java.time.Instant.now().toString(),
                pendingSyncCount = 0,
                lastSyncErrorCode = null,
            )
        )
    }

    suspend fun recordSyncFailure(code: String, pendingCount: Int) {
        val current = read()
        save(
            current.copy(lastSyncErrorCode = code, pendingSyncCount = pendingCount.coerceAtLeast(0))
        )
    }

    suspend fun setDiagnosticsUploadEnabled(enabled: Boolean) =
        save(read().copy(diagnosticsUploadEnabled = enabled))

    suspend fun setMemoryEnabled(enabled: Boolean) = save(read().copy(memoryEnabled = enabled))

    suspend fun clearPairing() {
        val current = read()
        save(
            CatServerSettings(
                diagnosticsUploadEnabled = current.diagnosticsUploadEnabled,
                memoryEnabled = current.memoryEnabled,
            )
        )
    }

    private suspend fun save(value: CatServerSettings) {
        dataStoreManager.saveToDataStore(KEY, json.encodeToString(value))
    }

    companion object {
        private val KEY = stringPreferencesKey("CAT_SERVER_SETTINGS_V1")
    }
}
