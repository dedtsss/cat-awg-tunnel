package com.zaneschepke.wireguardautotunnel.cat.runtime

import java.nio.file.Files
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicLong
import java.util.zip.ZipFile
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CatRuntimeLogCoreTest {
    @Test
    fun recordSerializesSemanticContractAndCorrelation() {
        val record =
            CatLogRecord(
                timestampMs = 123L,
                elapsedMs = 45L,
                sessionId = "session-1",
                correlationId = "correlation-1",
                component = "ui",
                eventType = "screen.open",
                action = "open settings",
                expected = "settings visible",
                actual = "settings visible",
                result = "PASS",
                evidence = "navigation terminal state",
                durationMs = 22L,
            )

        val json = Json.parseToJsonElement(record.toNdjson().trim()).jsonObject
        assertEquals("open settings", json.getValue("action").toString().trim('"'))
        assertEquals("settings visible", json.getValue("expected").toString().trim('"'))
        assertEquals("settings visible", json.getValue("actual").toString().trim('"'))
        assertEquals("PASS", json.getValue("result").toString().trim('"'))
        assertEquals("navigation terminal state", json.getValue("evidence").toString().trim('"'))
        assertEquals("session-1", json.getValue("sessionId").toString().trim('"'))
        assertEquals("correlation-1", json.getValue("correlationId").toString().trim('"'))
    }

    @Test
    fun centralSanitizerRemovesSecretFormsAndSensitiveAttributes() {
        val raw = """
            PrivateKey = private-value
            PresharedKey=preshared-value
            Authorization: Bearer bearer-value
            password=hunter2 bootstrap_token=boot-value api_key=api-value
        """.trimIndent()
        val clean = CatLogSanitizer.text(raw).orEmpty()

        assertFalse(clean.contains("private-value"))
        assertFalse(clean.contains("preshared-value"))
        assertFalse(clean.contains("bearer-value"))
        assertFalse(clean.contains("hunter2"))
        assertFalse(clean.contains("boot-value"))
        assertFalse(clean.contains("api-value"))
        assertTrue(clean.contains("[redacted]"))

        val attrs =
            CatLogSanitizer.attributes(
                mapOf(
                    "private_key" to "raw-private",
                    "token" to "raw-token",
                    "screen" to "diagnostics",
                    "error" to "Bearer abc.def",
                )
            )
        assertFalse(attrs.containsKey("private_key"))
        assertFalse(attrs.containsKey("token"))
        assertEquals("diagnostics", attrs["screen"])
        assertFalse(attrs["error"].orEmpty().contains("abc.def"))
    }

    @Test
    fun handledExceptionTextIsRedacted() {
        val safe = CatLogSanitizer.throwable(IllegalStateException("token=secret-token Bearer raw.bearer"))
        assertTrue(safe.orEmpty().startsWith("IllegalStateException:"))
        assertFalse(safe.orEmpty().contains("secret-token"))
        assertFalse(safe.orEmpty().contains("raw.bearer"))
    }

    @Test
    fun storeRotatesAndKeepsBoundedFileCount() {
        val directory = Files.createTempDirectory("cat-log-test").toFile()
        try {
            val store = CatLogStore(directory, maxBytes = 80L, maxFiles = 3)
            repeat(12) { store.append("event-$it-${"x".repeat(30)}\n") }
            val files = store.filesNewestFirst()
            assertTrue(files.size <= 3)
            assertTrue(files.all { it.length() <= 120L })
            assertTrue(store.totalBytes() <= 360L)
            store.clear()
            assertTrue(store.filesNewestFirst().isEmpty())
        } finally {
            directory.deleteRecursively()
        }
    }

    @Test
    fun exportBundleContainsProvenanceAndRedactsSummarySecrets() {
        val directory = Files.createTempDirectory("cat-log-export-test").toFile()
        try {
            val records = directory.resolve("events.ndjson")
            records.writeText(
                CatLogRecord(
                        timestampMs = 1L,
                        elapsedMs = 2L,
                        sessionId = "session",
                        correlationId = "correlation",
                        component = "test",
                        eventType = "test.event",
                        action = "run",
                        expected = "safe",
                        actual = "safe",
                        result = "PASS",
                        evidence = "unit test",
                    )
                    .toNdjson()
            )
            val output = directory.resolve("cat.zip")
            CatLogBundleWriter.write(
                output,
                listOf(records),
                mapOf("git_sha" to "abc123", "detail" to "token=top-secret"),
            )

            ZipFile(output).use { zip ->
                val summary = zip.getInputStream(zip.getEntry("summary.json")).bufferedReader().readText()
                val events = zip.getInputStream(zip.getEntry("cat-events.ndjson")).bufferedReader().readText()
                assertTrue(summary.contains("abc123"))
                assertFalse(summary.contains("top-secret"))
                assertTrue(events.contains("correlation"))
            }
        } finally {
            directory.deleteRecursively()
        }
    }

    @Test
    fun operationStartAndCompletionProducePassWithDuration() {
        val scheduler = Executors.newSingleThreadScheduledExecutor()
        val elapsed = AtomicLong(100L)
        val records = CopyOnWriteArrayList<CatLogRecord>()
        try {
            val tracker =
                CatOperationTracker(
                    scheduler = scheduler,
                    emit = records::add,
                    nowWall = { 1000L + elapsed.get() },
                    nowElapsed = elapsed::get,
                    sessionId = { "session" },
                )
            val id = tracker.start("ui", "save", "save settings", "saved", 1_000L)
            elapsed.set(145L)
            tracker.finish(id, "saved")

            assertEquals(2, records.size)
            assertEquals("started", records.first().actual)
            assertEquals("PASS", records.last().result)
            assertEquals(45L, records.last().durationMs)
            assertEquals(records.first().correlationId, records.last().correlationId)
        } finally {
            scheduler.shutdownNow()
        }
    }

    @Test
    fun operationWithoutCompletionProducesBoundedStallEvidence() {
        val scheduler = Executors.newSingleThreadScheduledExecutor()
        val elapsed = AtomicLong(100L)
        val records = CopyOnWriteArrayList<CatLogRecord>()
        try {
            val tracker =
                CatOperationTracker(
                    scheduler = scheduler,
                    emit = records::add,
                    nowWall = { System.currentTimeMillis() },
                    nowElapsed = { elapsed.addAndGet(10L) },
                    sessionId = { "session" },
                )
            tracker.start("startup", "load", "load app", "loaded", 30L)
            Thread.sleep(100L)
            assertTrue(records.any { it.actual == "timeout/abandoned" && it.result == "PARTIAL" })
        } finally {
            scheduler.shutdownNow()
        }
    }
}
