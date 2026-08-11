package com.zaneschepke.wireguardautotunnel.ui.screens.settings.configurator

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
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.dedtsss.catawg.core.configurator.AwgParameterExplanationCatalog
import com.dedtsss.catawg.core.configurator.ConfigProtocol
import com.zaneschepke.wireguardautotunnel.R
import com.zaneschepke.wireguardautotunnel.viewmodel.ConfiguratorViewModel
import kotlinx.coroutines.launch

@Composable
fun ConfiguratorScreen(viewModel: ConfiguratorViewModel) {
    val state by viewModel.state.collectAsState()
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val importLauncher =
        rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
            if (uri == null) return@rememberLauncherForActivityResult
            scope.launch {
                runCatching {
                        requireNotNull(context.contentResolver.openInputStream(uri))
                            .bufferedReader()
                            .use { it.readText() }
                    }
                    .onSuccess(viewModel::importRawText)
                    .onFailure { viewModel.reportError(context.getString(R.string.configurator_file_error)) }
            }
        }
    val exportLauncher =
        rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("text/plain")) { uri ->
            if (uri == null) return@rememberLauncherForActivityResult
            scope.launch {
                runCatching {
                        requireNotNull(context.contentResolver.openOutputStream(uri))
                            .bufferedWriter()
                            .use { it.write(viewModel.exportRawText()) }
                    }
                    .onFailure { viewModel.reportError(context.getString(R.string.configurator_file_error)) }
            }
        }

    Column(
        modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text(stringResource(R.string.configurator_title), style = MaterialTheme.typography.headlineSmall)
        Text(stringResource(R.string.configurator_intro))

        OutlinedTextField(
            value = state.profileName,
            onValueChange = viewModel::setProfileName,
            modifier = Modifier.fillMaxWidth(),
            label = { Text(stringResource(R.string.configurator_candidate_name)) },
            singleLine = true,
        )
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            ConfigProtocol.entries.forEach { protocol ->
                Button(
                    onClick = { viewModel.setProtocol(protocol) },
                    enabled = protocol != ConfigProtocol.AWG3 && protocol != state.protocol,
                ) {
                    Text(
                        if (protocol == ConfigProtocol.AWG3) {
                            stringResource(R.string.configurator_awg3_unavailable)
                        } else {
                            protocol.name
                        }
                    )
                }
            }
        }
        Text(stringResource(R.string.configurator_awg3_note))

        OutlinedTextField(
            value = state.rawText,
            onValueChange = viewModel::setRawText,
            modifier = Modifier.fillMaxWidth(),
            label = { Text(stringResource(R.string.configurator_profile)) },
            supportingText = { Text(stringResource(R.string.configurator_profile_hint)) },
            minLines = 12,
        )
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            Button(onClick = viewModel::createCandidate) { Text(stringResource(R.string.configurator_create)) }
            Button(onClick = { importLauncher.launch(arrayOf("text/plain", "application/octet-stream")) }) {
                Text(stringResource(R.string.configurator_import))
            }
            TextButton(onClick = { exportLauncher.launch("cat-awg.conf") }, enabled = state.rawText.isNotBlank()) {
                Text(stringResource(R.string.configurator_export))
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            Button(onClick = viewModel::validateLocally, enabled = !state.busy) {
                Text(stringResource(R.string.configurator_validate_local))
            }
            Button(
                onClick = viewModel::saveCandidate,
                enabled = state.parsedProfile != null && !state.busy,
            ) { Text(stringResource(R.string.configurator_save)) }
            TextButton(
                onClick = viewModel::validateOnServer,
                enabled = state.parsedProfile != null && !state.busy,
            ) { Text(stringResource(R.string.configurator_validate_server)) }
        }

        state.validation?.let { validation ->
            Card(modifier = Modifier.fillMaxWidth()) {
                Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(5.dp)) {
                    Text(
                        stringResource(
                            if (validation.isValid) R.string.configurator_local_valid
                            else R.string.configurator_local_invalid
                        )
                    )
                    validation.issues.forEach { issue ->
                        Text(
                            stringResource(
                                R.string.configurator_issue,
                                issue.level,
                                issue.field ?: stringResource(R.string.configurator_profile_default),
                                issue.code,
                            ) + "\n" + issue.message
                        )
                    }
                }
            }
        }

        state.parsedProfile?.let { profile ->
            Card(modifier = Modifier.fillMaxWidth()) {
                Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(
                        stringResource(R.string.configurator_public_profile, profile.name),
                        style = MaterialTheme.typography.titleMedium,
                    )
                    Text(
                        stringResource(
                            R.string.configurator_protocol_requirements,
                            profile.protocol,
                            profile.capabilityRequirements.joinToString(),
                        )
                    )
                    profile.parameters.toSortedMap().forEach { (key, value) -> Text("$key = $value") }
                }
            }
        }

        if (state.recommendations.isNotEmpty()) {
            Card(modifier = Modifier.fillMaxWidth()) {
                Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text(stringResource(R.string.configurator_recommendations), style = MaterialTheme.typography.titleMedium)
                    state.recommendations.forEach { recommendation ->
                        Text(recommendation.recommendation)
                        Text(
                            stringResource(
                                R.string.configurator_evidence,
                                recommendation.evidence.joinToString().ifBlank { "—" },
                                (recommendation.confidence * 100).toInt(),
                            )
                        )
                    }
                }
            }
        }

        state.parsedProfile?.takeIf { state.validation?.isValid == true }?.let {
            Card(modifier = Modifier.fillMaxWidth()) {
                Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text(stringResource(R.string.configurator_target_tunnel), style = MaterialTheme.typography.titleMedium)
                    state.targetTunnels.forEach { tunnel ->
                        TextButton(onClick = { viewModel.selectTargetTunnel(tunnel.id) }) {
                            Text((if (tunnel.id == state.selectedTunnelId) "✓ " else "") + tunnel.name)
                        }
                    }
                    Button(onClick = viewModel::requestApply, enabled = state.selectedTunnelId != null && !state.busy) {
                        Text(stringResource(R.string.configurator_apply_to_tunnel))
                    }
                }
            }
        }

        state.serverValidation?.let { response ->
            Card(modifier = Modifier.fillMaxWidth()) {
                Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(
                        stringResource(
                            R.string.configurator_server_validation,
                            stringResource(if (response.valid) R.string.configurator_valid else R.string.configurator_rejected),
                        )
                    )
                    Text(stringResource(R.string.configurator_capability, response.capabilitySatisfied))
                    response.issues.forEach { issue ->
                        Text(
                            stringResource(
                                R.string.configurator_issue,
                                issue.severity,
                                issue.field ?: stringResource(R.string.configurator_profile_default),
                                issue.code,
                            )
                        )
                    }
                }
            }
        }
        state.serverError?.let { Text(it, color = MaterialTheme.colorScheme.error) }
        state.error?.let { Text(it, color = MaterialTheme.colorScheme.error) }

        Text(stringResource(R.string.configurator_history), style = MaterialTheme.typography.titleMedium)
        if (state.profiles.isEmpty()) Text(stringResource(R.string.configurator_empty_history))
        state.profiles.forEach { profile ->
            Card(modifier = Modifier.fillMaxWidth()) {
                Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(5.dp)) {
                    Text("${profile.name} · ${profile.protocol}")
                    Text(stringResource(R.string.configurator_updated, profile.updatedAt, profile.parameters.size))
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        TextButton(onClick = { viewModel.selectComparisonBaseline(profile.id) }) {
                            Text(
                                if (profile.id == state.comparisonBaselineId) "✓ " + stringResource(R.string.configurator_select_baseline)
                                else stringResource(R.string.configurator_select_baseline)
                            )
                        }
                        TextButton(onClick = { viewModel.compare(profile.id) }) {
                            Text(stringResource(R.string.configurator_compare))
                        }
                        TextButton(onClick = { viewModel.loadReliability(profile.id) }) {
                            Text(stringResource(R.string.configurator_before_after))
                        }
                    }
                }
            }
        }
        if (state.comparison.isNotEmpty()) {
            Card(modifier = Modifier.fillMaxWidth()) {
                Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(stringResource(R.string.configurator_comparison), style = MaterialTheme.typography.titleMedium)
                    state.comparison.forEach { (key, value) -> Text("$key: $value") }
                }
            }
        }
        state.reliability?.let { metrics ->
            Card(modifier = Modifier.fillMaxWidth()) {
                Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(stringResource(R.string.configurator_reliability), style = MaterialTheme.typography.titleMedium)
                    Text(stringResource(R.string.configurator_change_at, metrics.changeAt))
                    Text(stringResource(R.string.configurator_before, metrics.before.incidents, metrics.before.reconnectEvents))
                    Text(stringResource(R.string.configurator_after, metrics.after.incidents, metrics.after.reconnectEvents))
                    Text(assessment(metrics.before.incidents, metrics.after.incidents))
                    state.reliabilitySource?.let { source -> Text(source) }
                    Text(metrics.before.sampleNote.ifBlank { metrics.after.sampleNote })
                }
            }
        }
        if (state.experiments.isNotEmpty()) {
            Text(stringResource(R.string.configurator_experiment_history), style = MaterialTheme.typography.titleMedium)
            state.experiments.forEach { experiment ->
                Text(
                    stringResource(
                        R.string.configurator_experiment_entry,
                        experiment.timestamp,
                        experiment.result ?: "—",
                        experiment.changedParameters.keys.joinToString().ifBlank { "—" },
                    )
                )
            }
        }

        HorizontalDivider()
        Text(stringResource(R.string.configurator_parameter_guide), style = MaterialTheme.typography.titleMedium)
        AwgParameterExplanationCatalog.all.forEach { explanation ->
            Card(modifier = Modifier.fillMaxWidth()) {
                Column(modifier = Modifier.padding(10.dp), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                    Text("${explanation.russianName} · ${explanation.technicalName}")
                    Text(explanation.purpose)
                    explanation.validRange?.let { Text(stringResource(R.string.configurator_parameter_range, it)) }
                    Text(stringResource(R.string.configurator_parameter_effect_up, explanation.increaseEffect))
                    Text(stringResource(R.string.configurator_parameter_effect_down, explanation.decreaseEffect))
                    Text(stringResource(R.string.configurator_parameter_risks, explanation.risks))
                    explanation.recommendedState?.let {
                        Text(stringResource(R.string.configurator_parameter_recommended, it))
                    }
                }
            }
        }
    }

    if (state.pendingApply) {
        AlertDialog(
            onDismissRequest = viewModel::dismissApply,
            title = { Text(stringResource(R.string.configurator_apply_confirm_title)) },
            text = { Text(stringResource(R.string.configurator_apply_confirm_message)) },
            confirmButton = {
                TextButton(onClick = viewModel::confirmApply) {
                    Text(stringResource(R.string.configurator_apply_confirm))
                }
            },
            dismissButton = {
                TextButton(onClick = viewModel::dismissApply) { Text(stringResource(R.string.cancel)) }
            },
        )
    }
}

@Composable
private fun assessment(before: Int, after: Int): String =
    when {
        before >= 2 && after < before -> stringResource(R.string.configurator_assessment_improved)
        after > before -> stringResource(R.string.configurator_assessment_worse)
        else -> stringResource(R.string.configurator_assessment_insufficient)
    }
