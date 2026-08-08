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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
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

@Composable
fun ClientDiagnosticsScreen(viewModel: ClientDiagnosticsViewModel) {
    val state by viewModel.state.collectAsState()
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var requestedWindow by rememberSaveable { mutableStateOf<DiagnosticExportWindow?>(null) }
    var requestedServerWindow by rememberSaveable { mutableStateOf<DiagnosticExportWindow?>(null) }
    var aiMessage by remember { mutableStateOf("") }
    val exportLauncher =
        rememberLauncherForActivityResult(
            ActivityResultContracts.CreateDocument("application/zip")
        ) { uri ->
            val window = requestedWindow
            if (uri == null || window == null) return@rememberLauncherForActivityResult
            scope.launch {
                runCatching {
                        val bundle = viewModel.export(window)
                        writeBundle(context.contentResolver, uri, bundle)
                    }
                    .onSuccess { viewModel.refresh() }
                    .onFailure {
                        viewModel.reportError(it.message ?: "Could not export diagnostics")
                    }
            }
        }
    val serverBundleLauncher =
        rememberLauncherForActivityResult(
            ActivityResultContracts.CreateDocument("application/zip")
        ) { uri ->
            val window = requestedServerWindow
            if (uri == null || window == null) return@rememberLauncherForActivityResult
            scope.launch {
                runCatching {
                        val bundle = viewModel.downloadServerBundle(window)
                        requireNotNull(context.contentResolver.openOutputStream(uri)).use {
                            it.write(bundle)
                        }
                    }
                    .onFailure {
                        viewModel.reportError(it.message ?: "Could not download Cat Server bundle")
                    }
            }
        }

    Column(
        modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text("Diagnostics", style = MaterialTheme.typography.headlineSmall)
        Text(
            "Deterministic local evidence remains available without Cat Server. Server correlation is shown separately and is never inferred from a client-only event."
        )
        Text("Stored client events: ${state.eventCount}; local incidents: ${state.incidents.size}")

        Card(modifier = Modifier.fillMaxWidth()) {
            Column(
                modifier = Modifier.padding(12.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                Text("Cat Server sync", style = MaterialTheme.typography.titleMedium)
                Text(
                    if (state.serverSettings.isPaired)
                        "Paired server: ${state.serverSettings.serverUrl}"
                    else "No Cat Server paired; standalone diagnostics mode is active."
                )
                Text("Last successful sync: ${state.serverSettings.lastSyncAt ?: "Never"}")
                Text("Pending/failed events: ${state.serverSettings.pendingSyncCount}")
                state.serverSettings.lastSyncErrorCode?.let { Text("Last sync state: $it") }
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                ) {
                    Text("Upload CLIENT events")
                    Switch(
                        checked = state.serverSettings.diagnosticsUploadEnabled,
                        onCheckedChange = viewModel::setDiagnosticsUploadEnabled,
                    )
                }
                Button(
                    onClick = viewModel::syncNow,
                    enabled = !state.syncBusy && state.serverSettings.isPaired,
                ) {
                    Text(if (state.syncBusy) "Syncing…" else "Sync now")
                }
                if (state.serverSettings.isPaired) {
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        DiagnosticExportWindow.entries.take(2).forEach { window ->
                            TextButton(
                                onClick = {
                                    requestedServerWindow = window
                                    serverBundleLauncher.launch(
                                        "cat-diagnostics-server-${window.minutes}m.zip"
                                    )
                                }
                            ) {
                                Text("Server bundle ${window.minutes}m")
                            }
                        }
                    }
                }
                state.serverError?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            }
        }

        Text("Local client evidence", style = MaterialTheme.typography.titleMedium)
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            DiagnosticExportWindow.entries.take(2).forEach { window ->
                Button(
                    onClick = {
                        requestedWindow = window
                        exportLauncher.launch("cat-diagnostics-${window.minutes}m.zip")
                    }
                ) {
                    Text(
                        if (window == DiagnosticExportWindow.MINUTES_15) "Export 15m"
                        else "Export 1h"
                    )
                }
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            DiagnosticExportWindow.entries.drop(2).forEach { window ->
                Button(
                    onClick = {
                        requestedWindow = window
                        exportLauncher.launch("cat-diagnostics-${window.minutes}m.zip")
                    }
                ) {
                    Text(
                        if (window == DiagnosticExportWindow.HOURS_6) "Export 6h" else "Export 24h"
                    )
                }
            }
        }
        TextButton(onClick = viewModel::refresh) { Text("Refresh history") }
        state.incidents.forEach { incident ->
            IncidentCard("CLIENT · ${incident.severity}", incident)
        }

        if (state.serverSettings.isPaired) {
            Text("Server/correlated evidence", style = MaterialTheme.typography.titleMedium)
            if (state.serverEvidenceAvailable) {
                if (state.serverIncidents.isEmpty())
                    Text("Cat Server returned no incidents for the last 48 hours.")
                state.serverIncidents.forEach { incident ->
                    IncidentCard("SERVER · ${incident.severity}", incident)
                }
            } else {
                Text(
                    "Server evidence unavailable. Local client-only inference is not proof of a server failure."
                )
            }
        }

        state.serverSettings.capabilities
            ?.takeIf { it.features.aiGateway }
            ?.let {
                Card(modifier = Modifier.fillMaxWidth()) {
                    Column(
                        modifier = Modifier.padding(12.dp),
                        verticalArrangement = Arrangement.spacedBy(7.dp),
                    ) {
                        Text("AI Assistant", style = MaterialTheme.typography.titleMedium)
                        Text(
                            "AI runs through Cat Server; no provider key is stored in the APK. Replies are candidate advice only."
                        )
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                        ) {
                            Text("Allow requested context memory")
                            Switch(
                                checked = state.serverSettings.memoryEnabled,
                                onCheckedChange = viewModel::setMemoryEnabled,
                            )
                        }
                        OutlinedTextField(
                            value = aiMessage,
                            onValueChange = { aiMessage = it },
                            modifier = Modifier.fillMaxWidth(),
                            label = { Text("Ask about an incident or AWG parameter") },
                            minLines = 2,
                        )
                        Button(
                            onClick = { viewModel.askAi(aiMessage) },
                            enabled = aiMessage.isNotBlank() && !state.aiBusy,
                        ) {
                            Text(if (state.aiBusy) "Asking…" else "Ask AI")
                        }
                        state.aiResponse?.let { response -> Text(response) }
                        state.aiRecommendations.forEach { recommendation ->
                            Text("Candidate advice: $recommendation")
                        }
                    }
                }
            }

        state.error?.let { error ->
            Card(modifier = Modifier.fillMaxWidth()) { Text(error, Modifier.padding(12.dp)) }
        }
    }
}

@Composable
private fun IncidentCard(title: String, incident: com.dedtsss.catawg.core.diagnostics.Incident) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Text("$title · ${incident.classification}")
            Text("${incident.status}; confidence ${"%.0f".format(incident.confidence * 100)}%")
            Text("Started ${incident.startAt}${incident.endAt?.let { "; ended $it" }.orEmpty()}")
            incident.probableCause?.let { Text(it) }
            incident.recommendations.forEach { Text("Recommendation: $it") }
        }
    }
}

private fun writeBundle(
    resolver: ContentResolver,
    uri: android.net.Uri,
    bundle: DiagnosticExportBundle,
) {
    requireNotNull(resolver.openOutputStream(uri)).use { output ->
        ZipOutputStream(BufferedOutputStream(output)).use { zip ->
            bundle.entries.toSortedMap().forEach { (path, content) ->
                zip.putNextEntry(ZipEntry(path))
                zip.write(content.toByteArray(StandardCharsets.UTF_8))
                zip.closeEntry()
            }
        }
    }
}
