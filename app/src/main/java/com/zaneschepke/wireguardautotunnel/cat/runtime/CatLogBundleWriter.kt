package com.zaneschepke.wireguardautotunnel.cat.runtime

import java.io.BufferedOutputStream
import java.io.File
import java.io.FileOutputStream
import java.nio.charset.StandardCharsets
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

internal object CatLogBundleWriter {
    fun write(output: File, records: List<File>, summary: Map<String, String>): File {
        output.parentFile?.mkdirs()
        val safeSummary = summary.mapValues { (_, value) -> CatLogSanitizer.text(value).orEmpty() }
        ZipOutputStream(BufferedOutputStream(FileOutputStream(output))).use { zip ->
            records.forEachIndexed { index, file ->
                zip.putNextEntry(
                    ZipEntry(if (index == 0) "cat-events.ndjson" else "cat-events.$index.ndjson")
                )
                if (file.isFile) file.inputStream().use { it.copyTo(zip) }
                zip.closeEntry()
            }
            zip.putNextEntry(ZipEntry("summary.json"))
            zip.write(Json.encodeToString(safeSummary).toByteArray(StandardCharsets.UTF_8))
            zip.closeEntry()
        }
        return output
    }
}
