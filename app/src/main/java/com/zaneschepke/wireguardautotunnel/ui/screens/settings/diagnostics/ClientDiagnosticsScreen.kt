package com.zaneschepke.wireguardautotunnel.ui.screens.settings.diagnostics

import android.content.ContentResolver
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.dedtsss.catawg.core.diagnostics.DiagnosticExportBundle
import com.dedtsss.catawg.core.diagnostics.DiagnosticExportWindow
import com.zaneschepke.wireguardautotunnel.viewmodel.ClientDiagnosticsViewModel
import java.io.BufferedOutputStream
import java.nio.charset.StandardCharsets
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlinx.coroutines.launch

/** Local-only incident history and explicit user-initiated sanitized export. */
@Composable
fun ClientDiagnosticsScreen(viewModel: ClientDiagnosticsViewModel) {
    val state by viewModel.state.collectAsState()
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var requestedWindow by rememberSaveable { mutableStateOf<DiagnosticExportWindow?>(null) }
    val exportLauncher =
        rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/zip")) { uri ->
            val window = requestedWindow
            if (uri == null || window == null) return@rememberLauncherForActivityResult
            scope.launch {
                runCatching {
                        val bundle = viewModel.export(window)
                        writeBundle(context.contentResolver, uri, bundle)
                    }
                    .onSuccess { viewModel.refresh() }
                    .onFailure { viewModel.reportError(it.message ?: "Could not export diagnostics") }
            }
        }

    Column(
        modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text("Client diagnostics")
        Text("Events and incidents stay on this device for about 48 hours. Exports are sanitized before writing.")
        Text("Stored events: ${state.eventCount}; incidents: ${state.incidents.size}")
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            DiagnosticExportWindow.entries.take(2).forEach { window ->
                Button(
                    onClick = {
                        requestedWindow = window
                        exportLauncher.launch("cat-diagnostics-${window.minutes}m.zip")
                    },
                ) { Text(if (window == DiagnosticExportWindow.MINUTES_15) "Export 15m" else "Export 1h") }
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            DiagnosticExportWindow.entries.drop(2).forEach { window ->
                Button(
                    onClick = {
                        requestedWindow = window
                        exportLauncher.launch("cat-diagnostics-${window.minutes}m.zip")
                    },
                ) { Text(if (window == DiagnosticExportWindow.HOURS_6) "Export 6h" else "Export 24h") }
            }
        }
        TextButton(onClick = viewModel::refresh) { Text("Refresh history") }
        state.error?.let { error -> Card(modifier = Modifier.fillMaxWidth()) { Text(error, Modifier.padding(12.dp)) } }
        state.incidents.forEach { incident ->
            Card(modifier = Modifier.fillMaxWidth()) {
                Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text("${incident.severity} · ${incident.classification}")
                    Text("${incident.status}; confidence ${"%.0f".format(incident.confidence * 100)}%")
                    Text("Started ${incident.startAt}${incident.endAt?.let { "; ended $it" }.orEmpty()}")
                    incident.probableCause?.let { Text(it) }
                    incident.recommendations.forEach { Text("• $it") }
                }
            }
        }
    }
}

private fun writeBundle(resolver: ContentResolver, uri: android.net.Uri, bundle: DiagnosticExportBundle) {
    requireNotNull(resolver.openOutputStream(uri)) { "Could not open selected export destination" }.use { output ->
        ZipOutputStream(BufferedOutputStream(output)).use { zip ->
            bundle.entries.toSortedMap().forEach { (path, content) ->
                zip.putNextEntry(ZipEntry(path))
                zip.write(content.toByteArray(StandardCharsets.UTF_8))
                zip.closeEntry()
            }
        }
    }
}
