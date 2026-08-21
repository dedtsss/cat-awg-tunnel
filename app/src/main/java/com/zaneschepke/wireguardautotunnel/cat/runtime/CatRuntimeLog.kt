package com.zaneschepke.wireguardautotunnel.cat.runtime

import android.app.ActivityManager
import android.app.ApplicationExitInfo
import android.content.Context
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import com.zaneschepke.wireguardautotunnel.BuildConfig
import java.io.File
import java.util.UUID
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong

object CatRuntimeLog {
    private const val QUEUE_LIMIT = 256
    private const val HEARTBEAT_MS = 5_000L
    private const val FREEZE_THRESHOLD_MS = 10_000L
    private val lock = Any()
    @Volatile private var state: State? = null

    private class State(val context: Context) {
        val sessionId = UUID.randomUUID().toString()
        val directory = File(context.noBackupFilesDir, "cat-log")
        val store = CatLogStore(directory)
        val queue = ArrayBlockingQueue<CatLogRecord>(QUEUE_LIMIT)
        val dropped = AtomicLong()
        val written = AtomicLong()
        val writer = Executors.newSingleThreadExecutor { r -> Thread(r, "cat-runtime-log-writer").apply { isDaemon = true } }
        val scheduler = Executors.newSingleThreadScheduledExecutor { r -> Thread(r, "cat-runtime-log-timers").apply { isDaemon = true } }
        val operations = CatOperationTracker(scheduler, ::enqueue, System::currentTimeMillis, SystemClock::elapsedRealtime) { sessionId }
        var previousHandler: Thread.UncaughtExceptionHandler? = null
        val mainHandler = Handler(Looper.getMainLooper())
        var lastHeartbeat = SystemClock.elapsedRealtime()

        fun enqueue(record: CatLogRecord) {
            if (!queue.offer(sanitize(record))) {
                dropped.incrementAndGet()
                return
            }
            writer.execute(::drainQueue)
        }
        private fun drainQueue() {
            while (true) {
                val record = queue.poll() ?: return
                try {
                    store.append(record.toNdjson())
                    written.incrementAndGet()
                } catch (_: Throwable) {
                    dropped.incrementAndGet()
                }
            }
        }
    }

    fun initialize(context: Context) {
        if (state != null) return
        synchronized(lock) {
            if (state != null) return
            val created = State(context.applicationContext)
            state = created
            installCrashHandler(created)
            record("app", "session.start", "launch application", "runtime CAT Log active", "active", "PASS", "local-only diagnostics initialized", attributes = provenance())
            collectPreviousExit(created)
            scheduleHeartbeat(created)
        }
    }

    fun record(component: String, eventType: String, action: String? = null, expected: String? = null, actual: String? = null,
        result: String? = null, evidence: String? = null, error: Throwable? = null, durationMs: Long? = null,
        attributes: Map<String, Any?> = emptyMap(), correlationId: String = UUID.randomUUID().toString()) {
        val s = state ?: return
        s.enqueue(CatLogRecord(System.currentTimeMillis(), SystemClock.elapsedRealtime(), s.sessionId, correlationId, component, eventType,
            action, expected, actual, result, evidence, durationMs, CatLogSanitizer.throwable(error), CatLogSanitizer.attributes(attributes)))
    }

    fun handledException(component: String, eventType: String, action: String, expected: String, error: Throwable) =
        record(component, eventType, action, expected, "exception", "FAIL", "handled exception", error)

    fun startOperation(component: String, eventType: String, action: String, expected: String, timeoutMs: Long = 15_000L): String? =
        state?.operations?.start(component, eventType, action, expected, timeoutMs)

    fun finishOperation(id: String?, actual: String, result: String = "PASS", evidence: String? = null) {
        if (id != null) state?.operations?.finish(id, actual, result, evidence)
    }

    fun appForeground(foreground: Boolean) {
        if (!foreground) state?.operations?.abandonAll("app backgrounded before completion")
        record("lifecycle", if (foreground) "app.foreground" else "app.background", if (foreground) "enter foreground" else "enter background",
            if (foreground) "UI available" else "UI backgrounded", if (foreground) "foreground" else "background", "PASS", "process lifecycle")
    }

    fun navigation(from: Any?, to: Any?, action: String) = record("navigation", "navigation.$action", action,
        "target route opens", "${from?.javaClass?.simpleName ?: "none"} -> ${to?.javaClass?.simpleName ?: "none"}", "PASS", "central NavController hook")

    fun status(): String {
        val s = state ?: return "inactive"
        return "session=${s.sessionId.take(8)} events=${s.written.get()} dropped=${s.dropped.get()} bytes=${s.store.totalBytes()}"
    }

    fun clear() { state?.let { it.store.clear(); record("cat-log", "log.clear", "clear CAT Log", "local records removed", "cleared", "PASS", "user requested reset") } }

    fun export(context: Context): File {
        val s = requireNotNull(state) { "CAT Log is not initialized" }
        flush(1_500L)
        val shareDir = File(context.cacheDir, "cat-log-share").apply { mkdirs() }
        val output = File(shareDir, "cat-runtime-${System.currentTimeMillis()}.zip")
        return CatLogBundleWriter.write(
            output = output,
            records = s.store.filesNewestFirst(),
            summary =
                mapOf(
                    "schema" to "cat-runtime-v1",
                    "session_id" to s.sessionId,
                    "git_sha" to BuildConfig.GIT_SHA,
                    "app_version" to BuildConfig.VERSION_NAME,
                    "sdk_int" to Build.VERSION.SDK_INT.toString(),
                    "manufacturer" to Build.MANUFACTURER,
                    "model" to Build.MODEL,
                    "dropped" to s.dropped.get().toString(),
                ),
        )
    }

    fun flush(timeoutMs: Long) {
        val s = state ?: return
        runCatching { s.writer.submit {}.get(timeoutMs, TimeUnit.MILLISECONDS) }
    }

    private fun sanitize(r: CatLogRecord) = r.copy(
        component = CatLogSanitizer.text(r.component).orEmpty(), eventType = CatLogSanitizer.text(r.eventType).orEmpty(),
        action = CatLogSanitizer.text(r.action), expected = CatLogSanitizer.text(r.expected), actual = CatLogSanitizer.text(r.actual),
        result = CatLogSanitizer.text(r.result), evidence = CatLogSanitizer.text(r.evidence), error = CatLogSanitizer.text(r.error),
        attributes = CatLogSanitizer.attributes(r.attributes)
    )

    private fun installCrashHandler(s: State) {
        s.previousHandler = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, error ->
            record("crash", "process.uncaught_exception", "execute ${thread.name}", "no uncaught exception", "uncaught exception", "FAIL", "crash-path record", error)
            flush(500L)
            s.previousHandler?.uncaughtException(thread, error)
        }
    }

    private fun collectPreviousExit(s: State) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return
        runCatching {
            val manager = s.context.getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager ?: return
            val exit = manager.getHistoricalProcessExitReasons(s.context.packageName, 0, 1).firstOrNull() ?: return
            val reason = when (exit.reason) {
                ApplicationExitInfo.REASON_ANR -> "ANR"; ApplicationExitInfo.REASON_CRASH -> "CRASH"
                ApplicationExitInfo.REASON_CRASH_NATIVE -> "CRASH_NATIVE"; ApplicationExitInfo.REASON_LOW_MEMORY -> "LOW_MEMORY"
                else -> exit.reason.toString()
            }
            record("process", "process.previous_exit", "inspect previous process", "clean or known exit", reason,
                if (reason in setOf("ANR", "CRASH", "CRASH_NATIVE")) "PARTIAL" else "PASS", CatLogSanitizer.text(exit.description), attributes = mapOf("exit_reason" to reason, "exit_timestamp_ms" to exit.timestamp))
        }
    }

    private fun scheduleHeartbeat(s: State) {
        val runnable = object : Runnable {
            override fun run() {
                val now = SystemClock.elapsedRealtime(); val gap = now - s.lastHeartbeat; s.lastHeartbeat = now
                if (gap > HEARTBEAT_MS + FREEZE_THRESHOLD_MS) record("main-thread", "main_thread.freeze_evidence", "main loop heartbeat",
                    "heartbeat within ${HEARTBEAT_MS + FREEZE_THRESHOLD_MS}ms", "gap=${gap}ms", "PARTIAL", "delayed main-thread callback", durationMs = gap)
                s.mainHandler.postDelayed(this, HEARTBEAT_MS)
            }
        }
        s.mainHandler.postDelayed(runnable, HEARTBEAT_MS)
    }

    private fun provenance() = mapOf("git_sha" to BuildConfig.GIT_SHA, "app_version" to BuildConfig.VERSION_NAME,
        "sdk_int" to Build.VERSION.SDK_INT, "device" to "${Build.MANUFACTURER} ${Build.MODEL}")
}
