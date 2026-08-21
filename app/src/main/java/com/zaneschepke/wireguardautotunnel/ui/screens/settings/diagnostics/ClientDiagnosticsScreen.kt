package com.zaneschepke.wireguardautotunnel.ui.screens.settings.diagnostics

import android.content.ContentResolver
import android.content.Intent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.FileProvider
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
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.dedtsss.catawg.core.diagnostics.DiagnosticExportBundle
import com.dedtsss.catawg.core.diagnostics.DiagnosticExportWindow
import com.zaneschepke.wireguardautotunnel.BuildConfig
import com.zaneschepke.wireguardautotunnel.R
import com.zaneschepke.wireguardautotunnel.cat.runtime.CatRuntimeLog
import com.zaneschepke.wireguardautotunnel.viewmodel.ClientDiagnosticsViewModel
import java.io.BufferedOutputStream
import java.nio.charset.StandardCharsets
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@Composable
fun ClientDiagnosticsScreen(viewModel: ClientDiagnosticsViewModel) {
    val state by viewModel.state.collectAsState()
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var requestedWindow by rememberSaveable { mutableStateOf<DiagnosticExportWindow?>(null) }
    var requestedServerWindow by rememberSaveable { mutableStateOf<DiagnosticExportWindow?>(null) }
    var aiMessage by remember { mutableStateOf("") }
    var catStatus by remember { mutableStateOf(CatRuntimeLog.status()) }
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
                        viewModel.reportError(context.getString(R.string.diagnostics_export_error))
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
                        viewModel.reportError(context.getString(R.string.diagnostics_bundle_error))
                    }
            }
        }

    Column(
        modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text(stringResource(R.string.diagnostics_title), style = MaterialTheme.typography.headlineSmall)
        Text(stringResource(R.string.diagnostics_intro))
        Text(stringResource(R.string.diagnostics_counts, state.eventCount, state.incidents.size))

        Card(modifier = Modifier.fillMaxWidth()) {
            Column(
                modifier = Modifier.padding(12.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Text(stringResource(R.string.cat_runtime_log_title), style = MaterialTheme.typography.titleMedium)
                Text(stringResource(R.string.cat_runtime_log_desc))
                Text(catStatus)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(onClick = {
                        scope.launch {
                            runCatching {
                                withContext(Dispatchers.IO) { CatRuntimeLog.export(context) }
                            }.onSuccess { file ->
                                catStatus = CatRuntimeLog.status()
                                val uri = FileProvider.getUriForFile(context, BuildConfig.FILE_PROVIDER_AUTHORITY, file)
                                val share = Intent(Intent.ACTION_SEND)
                                    .setType("application/zip")
                                    .putExtra(Intent.EXTRA_STREAM, uri)
                                    .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                                context.startActivity(Intent.createChooser(share, context.getString(R.string.cat_runtime_log_share)))
                            }.onFailure { error ->
                                CatRuntimeLog.handledException("diagnostics-ui", "cat_log.export_error", "export CAT Log", "share bundle created", error)
                                catStatus = context.getString(R.string.cat_runtime_log_export_failed)
                            }
                        }
                    }) { Text(stringResource(R.string.cat_runtime_log_export)) }
                    TextButton(onClick = {
                        CatRuntimeLog.clear()
                        catStatus = CatRuntimeLog.status()
                    }) { Text(stringResource(R.string.cat_runtime_log_clear)) }
                    TextButton(onClick = { catStatus = CatRuntimeLog.status() }) {
                        Text(stringResource(R.string.cat_runtime_log_refresh))
                    }
                }
            }
        }

        Card(modifier = Modifier.fillMaxWidth()) {
            Column(
                modifier = Modifier.padding(12.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                Text(
                    stringResource(R.string.diagnostics_server_sync),
                    style = MaterialTheme.typography.titleMedium,
                )
                Text(
                    if (state.serverSettings.isPaired)
                        stringResource(
                            R.string.diagnostics_paired_server,
                            state.serverSettings.serverUrl.orEmpty(),
                        )
                    else stringResource(R.string.diagnostics_standalone)
                )
                Text(
                    stringResource(
                        R.string.diagnostics_last_sync,
                        state.serverSettings.lastSyncAt ?: stringResource(R.string.never),
                    )
                )
                Text(
                    stringResource(
                        R.string.diagnostics_pending,
                        state.serverSettings.pendingSyncCount,
                    )
                )
                state.serverSettings.lastSyncErrorCode?.let {
                    Text(stringResource(R.string.diagnostics_last_state, it))
                }
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                ) {
                    Text(stringResource(R.string.diagnostics_upload_client))
                    Switch(
                        checked = state.serverSettings.diagnosticsUploadEnabled,
                        onCheckedChange = viewModel::setDiagnosticsUploadEnabled,
                    )
                }
                Button(
                    onClick = viewModel::syncNow,
                    enabled = !state.syncBusy && state.serverSettings.isPaired,
                ) {
                    Text(
                        stringResource(
                            if (state.syncBusy) {
                                R.string.diagnostics_syncing
                            } else {
                                R.string.diagnostics_sync_now
                            }
                        )
                    )
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
                                Text(
                                    stringResource(
                                        R.string.diagnostics_server_bundle,
                                        window.minutes,
                                    )
                                )
                            }
                        }
                    }
                }
                state.serverError?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            }
        }

        Text(
            stringResource(R.string.diagnostics_local_evidence),
            style = MaterialTheme.typography.titleMedium,
        )
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            DiagnosticExportWindow.entries.take(2).forEach { window ->
                Button(
                    onClick = {
                        requestedWindow = window
                        exportLauncher.launch("cat-diagnostics-${window.minutes}m.zip")
                    }
                ) {
                    Text(stringResource(R.string.diagnostics_export_window, window.shortLabel()))
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
                    Text(stringResource(R.string.diagnostics_export_window, window.shortLabel()))
                }
            }
        }
        TextButton(onClick = viewModel::refresh) {
            Text(stringResource(R.string.diagnostics_refresh_history))
        }
        state.incidents.forEach { incident ->
            IncidentCard(stringResource(R.string.diagnostics_source_client), incident)
        }

        if (state.serverSettings.isPaired) {
            Text(
                stringResource(R.string.diagnostics_server_evidence),
                style = MaterialTheme.typography.titleMedium,
            )
            if (state.serverEvidenceAvailable) {
                if (state.serverIncidents.isEmpty())
                    Text(stringResource(R.string.diagnostics_no_server_incidents))
                state.serverIncidents.forEach { incident ->
                    IncidentCard(stringResource(R.string.diagnostics_source_server), incident)
                }
            } else {
                Text(stringResource(R.string.diagnostics_server_unavailable))
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
                        Text(
                            stringResource(R.string.diagnostics_ai_title),
                            style = MaterialTheme.typography.titleMedium,
                        )
                        Text(stringResource(R.string.diagnostics_ai_intro))
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                        ) {
                            Text(stringResource(R.string.diagnostics_ai_memory))
                            Switch(
                                checked = state.serverSettings.memoryEnabled,
                                onCheckedChange = viewModel::setMemoryEnabled,
                            )
                        }
                        OutlinedTextField(
                            value = aiMessage,
                            onValueChange = { aiMessage = it },
                            modifier = Modifier.fillMaxWidth(),
                            label = { Text(stringResource(R.string.diagnostics_ai_prompt)) },
                            minLines = 2,
                        )
                        Button(
                            onClick = { viewModel.askAi(aiMessage) },
                            enabled = aiMessage.isNotBlank() && !state.aiBusy,
                        ) {
                            Text(
                                stringResource(
                                    if (state.aiBusy) {
                                        R.string.diagnostics_asking
                                    } else {
                                        R.string.diagnostics_ask_ai
                                    }
                                )
                            )
                        }
                        state.aiResponse?.let { response -> Text(response) }
                        state.aiRecommendations.forEach { recommendation ->
                            Text(
                                stringResource(
                                    R.string.diagnostics_candidate_advice,
                                    recommendation,
                                )
                            )
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
private fun IncidentCard(source: String, incident: com.dedtsss.catawg.core.diagnostics.Incident) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Text(stringResource(R.string.diagnostics_incident_title, source, incident.classification))
            Text(
                stringResource(
                    R.string.diagnostics_incident_status,
                    incident.status,
                    (incident.confidence * 100).toInt(),
                )
            )
            Text(
                stringResource(
                    R.string.diagnostics_incident_time,
                    incident.startAt,
                    incident.endAt
                        ?.let { stringResource(R.string.diagnostics_incident_ended, it) }
                        .orEmpty(),
                )
            )
            incident.probableCause?.let { Text(it) }
            incident.recommendations.forEach {
                Text(stringResource(R.string.diagnostics_recommendation, it))
            }
        }
    }
}

@Composable
private fun DiagnosticExportWindow.shortLabel(): String =
    stringResource(
        when (this) {
            DiagnosticExportWindow.MINUTES_15 -> R.string.diagnostics_window_15
            DiagnosticExportWindow.HOUR_1 -> R.string.diagnostics_window_1h
            DiagnosticExportWindow.HOURS_6 -> R.string.diagnostics_window_6h
            DiagnosticExportWindow.HOURS_24 -> R.string.diagnostics_window_24h
        }
    )

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
