package com.zaneschepke.wireguardautotunnel.cat.runtime

import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit

internal class CatOperationTracker(
    private val scheduler: ScheduledExecutorService,
    private val emit: (CatLogRecord) -> Unit,
    private val nowWall: () -> Long = System::currentTimeMillis,
    private val nowElapsed: () -> Long,
    private val sessionId: () -> String,
) {
    private data class Pending(
        val component: String,
        val eventType: String,
        val action: String,
        val expected: String,
        val startedWall: Long,
        val startedElapsed: Long,
        val correlationId: String,
        val timeout: ScheduledFuture<*>,
    )

    private val pending = ConcurrentHashMap<String, Pending>()

    fun start(component: String, eventType: String, action: String, expected: String, timeoutMs: Long): String {
        val id = UUID.randomUUID().toString()
        val wall = nowWall()
        val elapsed = nowElapsed()
        lateinit var future: ScheduledFuture<*>
        future = scheduler.schedule({
            val operation = pending.remove(id) ?: return@schedule
            emit(record(operation, "timeout/abandoned", "PARTIAL", "operation exceeded ${timeoutMs}ms", nowElapsed() - operation.startedElapsed))
        }, timeoutMs.coerceAtLeast(1), TimeUnit.MILLISECONDS)
        val operation = Pending(component, eventType, action, expected, wall, elapsed, id, future)
        pending[id] = operation
        emit(record(operation, "started", null, "operation started", 0L))
        return id
    }

    fun finish(id: String, actual: String, result: String = "PASS", evidence: String? = null) {
        val operation = pending.remove(id) ?: return
        operation.timeout.cancel(false)
        emit(record(operation, actual, result, evidence, nowElapsed() - operation.startedElapsed))
    }

    fun abandonAll(reason: String) {
        pending.keys.toList().forEach { id ->
            val operation = pending.remove(id) ?: return@forEach
            operation.timeout.cancel(false)
            emit(record(operation, reason, "PARTIAL", "operation abandoned", nowElapsed() - operation.startedElapsed))
        }
    }

    private fun record(p: Pending, actual: String, result: String?, evidence: String?, duration: Long) =
        CatLogRecord(
            timestampMs = nowWall(), elapsedMs = nowElapsed(), sessionId = sessionId(), correlationId = p.correlationId,
            component = p.component, eventType = p.eventType, action = p.action, expected = p.expected,
            actual = actual, result = result, evidence = evidence, durationMs = duration,
        )
}
