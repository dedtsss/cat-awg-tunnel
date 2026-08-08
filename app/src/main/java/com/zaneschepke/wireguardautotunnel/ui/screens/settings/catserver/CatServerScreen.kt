package com.zaneschepke.wireguardautotunnel.ui.screens.settings.catserver

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
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import com.dedtsss.catawg.core.protocol.CatBootstrapParser
import com.dedtsss.catawg.core.protocol.ServerCapabilities
import com.dedtsss.catawg.core.protocol.displayCertificateFingerprint
import com.zaneschepke.wireguardautotunnel.ui.state.CatServerConnectionStatus
import com.zaneschepke.wireguardautotunnel.viewmodel.CatServerViewModel

@Composable
fun CatServerScreen(viewModel: CatServerViewModel) {
    val state by viewModel.state.collectAsState()
    var serverUrl by
        remember(state.settings.serverUrl) { mutableStateOf(state.settings.serverUrl.orEmpty()) }
    var fingerprint by
        remember(state.settings.certificateFingerprint) {
            mutableStateOf(
                state.settings.certificateFingerprint
                    ?.let(::displayCertificateFingerprint)
                    .orEmpty()
            )
        }
    var bootstrapToken by remember { mutableStateOf("") }
    var deviceName by
        remember(state.settings.deviceName) {
            mutableStateOf(state.settings.deviceName ?: "Android device")
        }
    var pastedBootstrap by remember { mutableStateOf("") }
    var importError by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(state.lastAction) {
        if (state.lastAction?.startsWith("Paired") == true) bootstrapToken = ""
    }

    Column(
        modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text(
            "Cat Server",
            style = androidx.compose.material3.MaterialTheme.typography.headlineSmall,
        )
        Text(
            "Management requires HTTPS and an out-of-band SHA-256 certificate fingerprint. HTTP and trust-all TLS are not supported."
        )
        Text("Status: ${statusLabel(state.status)}")

        OutlinedTextField(
            value = serverUrl,
            onValueChange = { serverUrl = it },
            modifier = Modifier.fillMaxWidth(),
            label = { Text("Server URL") },
            placeholder = { Text("https://host:8443") },
            singleLine = true,
            enabled = !state.busy,
        )
        OutlinedTextField(
            value = fingerprint,
            onValueChange = { fingerprint = it },
            modifier = Modifier.fillMaxWidth(),
            label = { Text("Certificate SHA-256 fingerprint") },
            placeholder = { Text("AA:BB:…:FF") },
            supportingText = { Text("Accepts SHA-256: prefix, spaces, colons or hyphens") },
            singleLine = true,
            enabled = !state.busy,
        )

        OutlinedTextField(
            value = pastedBootstrap,
            onValueChange = {
                pastedBootstrap = it
                importError = null
            },
            modifier = Modifier.fillMaxWidth(),
            label = { Text("Paste bootstrap / QR-compatible payload") },
            supportingText = {
                Text(
                    "catpair:v1 with server, fingerprint and one-time token; the token is kept only in this screen"
                )
            },
            minLines = 3,
            enabled = !state.busy,
        )
        TextButton(
            onClick = {
                runCatching {
                        val payload = CatBootstrapParser.parse(pastedBootstrap)
                        serverUrl = payload.server
                        fingerprint = displayCertificateFingerprint(payload.certificateFingerprint)
                        bootstrapToken = payload.bootstrapToken
                    }
                    .onFailure { importError = it.message ?: "Could not parse bootstrap payload" }
            },
            enabled = pastedBootstrap.isNotBlank() && !state.busy,
        ) {
            Text("Use pasted bootstrap")
        }
        importError?.let {
            Text(it, color = androidx.compose.material3.MaterialTheme.colorScheme.error)
        }

        OutlinedTextField(
            value = bootstrapToken,
            onValueChange = { bootstrapToken = it },
            modifier = Modifier.fillMaxWidth(),
            label = { Text("One-time bootstrap token") },
            visualTransformation = PasswordVisualTransformation(),
            singleLine = true,
            enabled = !state.busy,
        )
        OutlinedTextField(
            value = deviceName,
            onValueChange = { deviceName = it },
            modifier = Modifier.fillMaxWidth(),
            label = { Text("Device name") },
            singleLine = true,
            enabled = !state.busy,
        )

        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(
                onClick = { viewModel.healthCheck(serverUrl, fingerprint) },
                enabled = !state.busy,
            ) {
                Text("Health check")
            }
            Button(
                onClick = { viewModel.pair(serverUrl, fingerprint, bootstrapToken, deviceName) },
                enabled = !state.busy && bootstrapToken.isNotBlank(),
            ) {
                Text(if (state.settings.isPaired) "Re-pair" else "Pair")
            }
        }

        state.error?.let { error ->
            Card(modifier = Modifier.fillMaxWidth()) { Text(error, Modifier.padding(12.dp)) }
        }
        state.lastAction?.let { Text(it) }

        if (state.settings.isConfigured) {
            Card(modifier = Modifier.fillMaxWidth()) {
                Column(
                    modifier = Modifier.padding(12.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    Text(
                        "Connection details",
                        style = androidx.compose.material3.MaterialTheme.typography.titleMedium,
                    )
                    Text("Server: ${state.settings.serverUrl}")
                    Text("Device: ${state.settings.deviceName ?: "Not paired"}")
                    Text("Last contact: ${state.settings.lastContactAt ?: "Never"}")
                    state.settings.lastErrorCode?.let { Text("Last error: $it") }
                    HorizontalDivider()
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                    ) {
                        Text("Upload client diagnostics")
                        Switch(
                            checked = state.settings.diagnosticsUploadEnabled,
                            onCheckedChange = viewModel::setDiagnosticsUploadEnabled,
                        )
                    }
                    Text("A disabled upload never affects local tunnel operation.")
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        TextButton(onClick = viewModel::refresh, enabled = !state.busy) {
                            Text("Refresh")
                        }
                        TextButton(onClick = viewModel::forget, enabled = !state.busy) {
                            Text("Forget local pairing")
                        }
                    }
                }
            }
        }

        state.capabilities?.let { capabilities -> CapabilitiesCard(capabilities) }
    }
}

@Composable
private fun CapabilitiesCard(capabilities: ServerCapabilities) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(5.dp),
        ) {
            Text(
                "Server capabilities",
                style = androidx.compose.material3.MaterialTheme.typography.titleMedium,
            )
            Text(
                "Cat Agent ${capabilities.agentVersion} · ${capabilities.os} / ${capabilities.architecture}"
            )
            capabilities.engines.toSortedMap().forEach { (name, capability) ->
                Text(
                    "${name.uppercase()}: ${if (capability.supported) "supported" else "unavailable"}${capability.version?.let { " ($it)" }.orEmpty()}"
                )
            }
            Text("Diagnostics: ${onOff(capabilities.features.diagnostics)}")
            Text("Config management: ${onOff(capabilities.features.configManagement)}")
            Text("Server routing: ${onOff(capabilities.features.serverRouting)}")
            Text("Routing backend: ${capabilities.features.routingBackend ?: "none"}")
            Text("AI gateway: ${onOff(capabilities.features.aiGateway)}")
        }
    }
}

private fun statusLabel(status: CatServerConnectionStatus): String =
    when (status) {
        CatServerConnectionStatus.NOT_CONFIGURED -> "Not configured"
        CatServerConnectionStatus.CONFIGURED -> "Configured; pairing required"
        CatServerConnectionStatus.CONNECTED -> "Connected"
        CatServerConnectionStatus.ERROR -> "Error"
    }

private fun onOff(value: Boolean): String = if (value) "on" else "off"
