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
import androidx.compose.ui.unit.dp
import com.dedtsss.catawg.core.configurator.AwgParameterMetadataCatalog
import com.dedtsss.catawg.core.configurator.ConfigProtocol
import com.zaneschepke.wireguardautotunnel.viewmodel.ConfiguratorViewModel

@Composable
fun ConfiguratorScreen(viewModel: ConfiguratorViewModel) {
    val state by viewModel.state.collectAsState()

    Column(
        modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text("AWG Configurator v1", style = MaterialTheme.typography.headlineSmall)
        Text(
            "Import or create a candidate, validate it deterministically, and keep networking changes explicit. PrivateKey/PresharedKey never leave this screen or enter the public profile store."
        )

        OutlinedTextField(
            value = state.profileName,
            onValueChange = viewModel::setProfileName,
            modifier = Modifier.fillMaxWidth(),
            label = { Text("Candidate name") },
            singleLine = true,
        )
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            ConfigProtocol.entries.forEach { protocol ->
                Button(
                    onClick = { viewModel.setProtocol(protocol) },
                    enabled = protocol != ConfigProtocol.AWG3,
                ) {
                    Text(if (protocol == ConfigProtocol.AWG3) "AWG3 unavailable" else protocol.name)
                }
            }
        }
        Text(
            "AWG3 is shown as unavailable until both the bundled client and paired server advertise real support."
        )

        OutlinedTextField(
            value = state.rawText,
            onValueChange = viewModel::setRawText,
            modifier = Modifier.fillMaxWidth(),
            label = { Text("WireGuard/AWG profile (paste/import)") },
            supportingText = {
                Text(
                    "INI sections [Interface] and [Peer]. This field is parsed locally; only redacted public parameters can be sent to Cat Server."
                )
            },
            minLines = 12,
        )
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            Button(onClick = viewModel::createCandidate) { Text("Create candidate") }
            Button(onClick = viewModel::validateLocally, enabled = !state.busy) {
                Text("Validate locally")
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            Button(
                onClick = viewModel::saveCandidate,
                enabled = state.parsedProfile != null && !state.busy,
            ) {
                Text("Save candidate")
            }
            Button(
                onClick = viewModel::validateOnServer,
                enabled = state.parsedProfile != null && !state.busy,
            ) {
                Text("Validate on Cat Server")
            }
            TextButton(
                onClick = viewModel::askAi,
                enabled = state.parsedProfile != null && !state.busy,
            ) {
                Text("Ask AI")
            }
        }

        state.validation?.let { validation ->
            Card(modifier = Modifier.fillMaxWidth()) {
                Column(
                    modifier = Modifier.padding(12.dp),
                    verticalArrangement = Arrangement.spacedBy(5.dp),
                ) {
                    Text(
                        if (validation.isValid) "Local validation: valid"
                        else "Local validation: needs changes"
                    )
                    validation.issues.forEach { issue ->
                        Text(
                            "${issue.level}: ${issue.field ?: "profile"} · ${issue.code} · ${issue.message}"
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
                        "Public profile: ${profile.name}",
                        style = MaterialTheme.typography.titleMedium,
                    )
                    Text(
                        "Protocol: ${profile.protocol}; requirements: ${profile.capabilityRequirements.joinToString()}"
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
                    Text("Cat Server validation: ${if (response.valid) "valid" else "rejected"}")
                    Text("Capability satisfied: ${response.capabilitySatisfied}")
                    response.issues.forEach { issue ->
                        Text("${issue.severity}: ${issue.field ?: "profile"} · ${issue.message}")
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
                    Text("AI candidate advice", style = MaterialTheme.typography.titleMedium)
                    Text(state.aiResponse.orEmpty())
                    state.aiRecommendations.forEach { Text("Candidate: $it") }
                    Text("AI recommendations are advice only and are never applied automatically.")
                }
            }
        }

        Text("History", style = MaterialTheme.typography.titleMedium)
        if (state.profiles.isEmpty()) Text("No saved public candidates yet.")
        state.profiles.forEach { profile ->
            Card(modifier = Modifier.fillMaxWidth()) {
                Column(
                    modifier = Modifier.padding(12.dp),
                    verticalArrangement = Arrangement.spacedBy(5.dp),
                ) {
                    Text("${profile.name} · ${profile.protocol}")
                    Text(
                        "Updated ${profile.updatedAt}; ${profile.parameters.size} public parameters"
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        TextButton(onClick = { viewModel.compare(profile.id) }) { Text("Compare") }
                        TextButton(onClick = { viewModel.loadReliability(profile.id) }) {
                            Text("Before/after")
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
                    Text("Public profile comparison", style = MaterialTheme.typography.titleMedium)
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
                    Text("Before/after reliability", style = MaterialTheme.typography.titleMedium)
                    Text("Change at ${metrics.changeAt}")
                    Text(
                        "Before: ${metrics.before.incidents} incidents, ${metrics.before.reconnectEvents} reconnects"
                    )
                    Text(
                        "After: ${metrics.after.incidents} incidents, ${metrics.after.reconnectEvents} reconnects"
                    )
                    Text(assessment(metrics.before.incidents, metrics.after.incidents))
                    state.reliabilitySource?.let { Text(it) }
                    Text(metrics.before.sampleNote.ifBlank { metrics.after.sampleNote })
                }
            }
        }

        HorizontalDivider()
        Text("Parameter guide", style = MaterialTheme.typography.titleMedium)
        AwgParameterMetadataCatalog.all.forEach { metadata ->
            Text(
                "${metadata.key} — ${metadata.description}${metadata.validRange?.let { " Range: $it." }.orEmpty()}"
            )
        }
    }
}

private fun assessment(before: Int, after: Int): String =
    when {
        before >= 2 && after < before ->
            "Assessment: improved descriptively; sample size does not prove causality."
        after > before ->
            "Assessment: worse descriptively; inspect diagnostics before changing settings again."
        else -> "Assessment: insufficient data for a causal conclusion."
    }
