package com.zaneschepke.wireguardautotunnel.ui.screens.settings.configurator

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
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.dedtsss.catawg.core.configurator.AwgParameterMetadataCatalog
import com.dedtsss.catawg.core.configurator.ConfigProtocol
import com.zaneschepke.wireguardautotunnel.R
import com.zaneschepke.wireguardautotunnel.viewmodel.ConfiguratorViewModel

@Composable
fun ConfiguratorScreen(viewModel: ConfiguratorViewModel) {
    val state by viewModel.state.collectAsState()

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
                    enabled = protocol != ConfigProtocol.AWG3,
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
            supportingText = {
                Text(stringResource(R.string.configurator_profile_hint))
            },
            minLines = 12,
        )
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            Button(onClick = viewModel::createCandidate) {
                Text(stringResource(R.string.configurator_create))
            }
            Button(onClick = viewModel::validateLocally, enabled = !state.busy) {
                Text(stringResource(R.string.configurator_validate_local))
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            Button(
                onClick = viewModel::saveCandidate,
                enabled = state.parsedProfile != null && !state.busy,
            ) {
                Text(stringResource(R.string.configurator_save))
            }
            Button(
                onClick = viewModel::validateOnServer,
                enabled = state.parsedProfile != null && !state.busy,
            ) {
                Text(stringResource(R.string.configurator_validate_server))
            }
            TextButton(
                onClick = viewModel::askAi,
                enabled = state.parsedProfile != null && !state.busy,
            ) {
                Text(stringResource(R.string.configurator_ask_ai))
            }
        }

        state.validation?.let { validation ->
            Card(modifier = Modifier.fillMaxWidth()) {
                Column(
                    modifier = Modifier.padding(12.dp),
                    verticalArrangement = Arrangement.spacedBy(5.dp),
                ) {
                    Text(
                        stringResource(
                            if (validation.isValid) {
                                R.string.configurator_local_valid
                            } else {
                                R.string.configurator_local_invalid
                            }
                        )
                    )
                    validation.issues.forEach { issue ->
                        Text(
                            stringResource(
                                R.string.configurator_issue,
                                issue.level,
                                issue.field ?: stringResource(R.string.configurator_profile_default),
                                issue.code,
                            )
                        )
                    }
                }
            }
        }

        state.parsedProfile?.let { profile ->
            Card(modifier = Modifier.fillMaxWidth()) {
                Column(
                    modifier = Modifier.padding(12.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp),
                ) {
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
                    profile.parameters.toSortedMap().forEach { (key, value) ->
                        Text("$key = $value")
                    }
                }
            }
        }
        state.serverValidation?.let { response ->
            Card(modifier = Modifier.fillMaxWidth()) {
                Column(
                    modifier = Modifier.padding(12.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    Text(
                        stringResource(
                            R.string.configurator_server_validation,
                            stringResource(
                                if (response.valid) {
                                    R.string.configurator_valid
                                } else {
                                    R.string.configurator_rejected
                                }
                            ),
                        )
                    )
                    Text(
                        stringResource(
                            R.string.configurator_capability,
                            response.capabilitySatisfied,
                        )
                    )
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

        if (state.aiResponse != null || state.aiRecommendations.isNotEmpty()) {
            Card(modifier = Modifier.fillMaxWidth()) {
                Column(
                    modifier = Modifier.padding(12.dp),
                    verticalArrangement = Arrangement.spacedBy(5.dp),
                ) {
                    Text(
                        stringResource(R.string.configurator_ai_advice),
                        style = MaterialTheme.typography.titleMedium,
                    )
                    Text(state.aiResponse.orEmpty())
                    state.aiRecommendations.forEach {
                        Text(stringResource(R.string.configurator_candidate_advice, it))
                    }
                    Text(stringResource(R.string.configurator_ai_note))
                }
            }
        }

        Text(stringResource(R.string.configurator_history), style = MaterialTheme.typography.titleMedium)
        if (state.profiles.isEmpty()) Text(stringResource(R.string.configurator_empty_history))
        state.profiles.forEach { profile ->
            Card(modifier = Modifier.fillMaxWidth()) {
                Column(
                    modifier = Modifier.padding(12.dp),
                    verticalArrangement = Arrangement.spacedBy(5.dp),
                ) {
                    Text("${profile.name} · ${profile.protocol}")
                    Text(
                        stringResource(
                            R.string.configurator_updated,
                            profile.updatedAt,
                            profile.parameters.size,
                        )
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
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
                Column(
                    modifier = Modifier.padding(12.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    Text(
                        stringResource(R.string.configurator_comparison),
                        style = MaterialTheme.typography.titleMedium,
                    )
                    state.comparison.forEach { (key, value) -> Text("$key: $value") }
                }
            }
        }
        state.reliability?.let { metrics ->
            Card(modifier = Modifier.fillMaxWidth()) {
                Column(
                    modifier = Modifier.padding(12.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    Text(
                        stringResource(R.string.configurator_reliability),
                        style = MaterialTheme.typography.titleMedium,
                    )
                    Text(stringResource(R.string.configurator_change_at, metrics.changeAt))
                    Text(
                        stringResource(
                            R.string.configurator_before,
                            metrics.before.incidents,
                            metrics.before.reconnectEvents,
                        )
                    )
                    Text(
                        stringResource(
                            R.string.configurator_after,
                            metrics.after.incidents,
                            metrics.after.reconnectEvents,
                        )
                    )
                    Text(assessment(metrics.before.incidents, metrics.after.incidents))
                    state.reliabilitySource?.let { Text(it) }
                    Text(metrics.before.sampleNote.ifBlank { metrics.after.sampleNote })
                }
            }
        }

        HorizontalDivider()
        Text(
            stringResource(R.string.configurator_parameter_guide),
            style = MaterialTheme.typography.titleMedium,
        )
        AwgParameterMetadataCatalog.all.forEach { metadata ->
            Text(
                metadata.key +
                    metadata.validRange
                        ?.let { stringResource(R.string.configurator_parameter_range, it) }
                        .orEmpty()
            )
        }
    }
}

@Composable
private fun assessment(before: Int, after: Int): String =
    when {
        before >= 2 && after < before ->
            stringResource(R.string.configurator_assessment_improved)
        after > before ->
            stringResource(R.string.configurator_assessment_worse)
        else -> stringResource(R.string.configurator_assessment_insufficient)
    }
