package com.zaneschepke.wireguardautotunnel.cat.runtime

import java.io.File
import java.nio.charset.StandardCharsets

internal class CatLogStore(
    private val directory: File,
    private val maxBytes: Long = 2L * 1024L * 1024L,
    private val maxFiles: Int = 3,
) {
    private val active = File(directory, "cat-events.ndjson")

    @Synchronized
    fun append(line: String) {
        runCatching {
            directory.mkdirs()
            val bytes = line.toByteArray(StandardCharsets.UTF_8)
            if (active.length() + bytes.size > maxBytes) rotate()
            active.appendBytes(bytes)
        }
    }

    @Synchronized
    fun clear() {
        directory.listFiles()?.forEach { if (it.name.startsWith("cat-events")) it.delete() }
    }

    @Synchronized
    fun filesNewestFirst(): List<File> =
        directory.listFiles()
            ?.filter { it.isFile && it.name.startsWith("cat-events") }
            ?.sortedByDescending { it.lastModified() }
            .orEmpty()

    @Synchronized
    fun totalBytes(): Long = filesNewestFirst().sumOf { it.length() }

    private fun rotate() {
        for (index in maxFiles - 1 downTo 1) {
            val from = if (index == 1) active else File(directory, "cat-events.${index - 1}.ndjson")
            val to = File(directory, "cat-events.$index.ndjson")
            if (to.exists()) to.delete()
            if (from.exists()) from.renameTo(to)
        }
    }
}
