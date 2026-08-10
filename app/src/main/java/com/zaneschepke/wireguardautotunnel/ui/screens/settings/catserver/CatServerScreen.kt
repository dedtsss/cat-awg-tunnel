package com.zaneschepke.wireguardautotunnel.ui.screens.settings.catserver

import android.Manifest
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
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import com.dedtsss.catawg.core.protocol.ServerCapabilities
import com.zaneschepke.wireguardautotunnel.R
import com.zaneschepke.wireguardautotunnel.ui.state.CatPairingPreview
import com.zaneschepke.wireguardautotunnel.ui.state.CatPairingStep
import com.zaneschepke.wireguardautotunnel.ui.state.CatServerConnectionStatus
import com.zaneschepke.wireguardautotunnel.viewmodel.CatServerViewModel
import io.github.g00fy2.quickie.QRResult
import io.github.g00fy2.quickie.ScanQRCode
import timber.log.Timber

@Composable
fun CatServerScreen(viewModel: CatServerViewModel) {
    val state by viewModel.state.collectAsState()
    val defaultDeviceName = stringResource(R.string.cat_default_device_name)
    var deviceName by
        remember(state.settings.deviceName, defaultDeviceName) {
            mutableStateOf(state.settings.deviceName ?: defaultDeviceName)
        }
    var pastedBootstrap by remember { mutableStateOf("") }
    var manualExpanded by rememberSaveable { mutableStateOf(false) }
    var manualServerUrl by
        remember(state.settings.serverUrl) { mutableStateOf(state.settings.serverUrl.orEmpty()) }
    var manualFingerprint by
        remember(state.settings.certificateFingerprint) {
            mutableStateOf(state.settings.certificateFingerprint.orEmpty())
        }
    var manualBootstrapToken by remember { mutableStateOf("") }

    val scanQrCodeLauncher =
        rememberLauncherForActivityResult(ScanQRCode()) { result ->
            when (result) {
                is QRResult.QRError -> {
                    Timber.w(result.exception, "Cat pairing QR scan failed")
                    viewModel.reportError("PAIRING_QR_UNAVAILABLE")
                }
                QRResult.QRMissingPermission -> viewModel.reportError("CAMERA_PERMISSION_REQUIRED")
                is QRResult.QRSuccess -> {
                    result.content.rawValue?.let(viewModel::importBootstrap)
                        ?: viewModel.reportError("PAIRING_PAYLOAD_INVALID")
                }
                QRResult.QRUserCanceled -> Unit
            }
        }
    val requestCameraPermission =
        rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            if (granted) {
                scanQrCodeLauncher.launch(null)
            } else {
                viewModel.reportError("CAMERA_PERMISSION_REQUIRED")
            }
        }

    Column(
        modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text(
            stringResource(R.string.cat_server),
            style = androidx.compose.material3.MaterialTheme.typography.headlineSmall,
        )
        Text(stringResource(R.string.cat_pairing_security))
        Text(stringResource(R.string.cat_status, catStatusLabel(state.status)))

        when (state.pairingStep) {
            CatPairingStep.START ->
                PairingStartCard(
                    payload = pastedBootstrap,
                    busy = state.busy,
                    onPayloadChange = { pastedBootstrap = it },
                    onScan = { requestCameraPermission.launch(Manifest.permission.CAMERA) },
                    onImport = { viewModel.importBootstrap(pastedBootstrap) },
                )
            CatPairingStep.VERIFY ->
                PairingVerificationCard(
                    preview = state.pairingPreview,
                    busy = state.busy,
                    onVerify = viewModel::verifyImportedBootstrap,
                    onChangeSource = {
                        pastedBootstrap = ""
                        viewModel.discardImportedBootstrap()
                    },
                )
            CatPairingStep.PAIR ->
                PairingDeviceCard(
                    deviceName = deviceName,
                    busy = state.busy,
                    onDeviceNameChange = { deviceName = it },
                    onPair = { viewModel.pairImported(deviceName) },
                )
            CatPairingStep.CONNECTED ->
                Card(modifier = Modifier.fillMaxWidth()) {
                    Column(
                        modifier = Modifier.padding(12.dp),
                        verticalArrangement = Arrangement.spacedBy(6.dp),
                    ) {
                        Text(
                            stringResource(R.string.cat_pairing_connected_title),
                            style = androidx.compose.material3.MaterialTheme.typography.titleMedium,
                        )
                        Text(stringResource(R.string.cat_pairing_connected_body))
                    }
                }
        }

        state.errorCode?.let { code ->
            Card(modifier = Modifier.fillMaxWidth()) {
                Text(
                    text = catErrorMessage(code),
                    color = androidx.compose.material3.MaterialTheme.colorScheme.error,
                    modifier = Modifier.padding(12.dp),
                )
            }
        }

        if (state.settings.isConfigured) {
            ConnectionDetailsCard(viewModel)
        }

        TextButton(onClick = { manualExpanded = !manualExpanded }) {
            Text(stringResource(R.string.cat_manual_settings))
        }
        if (manualExpanded) {
            ManualPairingCard(
                serverUrl = manualServerUrl,
                fingerprint = manualFingerprint,
                bootstrapToken = manualBootstrapToken,
                deviceName = deviceName,
                busy = state.busy,
                paired = state.settings.isPaired,
                onServerUrlChange = { manualServerUrl = it },
                onFingerprintChange = { manualFingerprint = it },
                onBootstrapTokenChange = { manualBootstrapToken = it },
                onDeviceNameChange = { deviceName = it },
                onHealthCheck = { viewModel.healthCheck(manualServerUrl, manualFingerprint) },
                onPair = {
                    viewModel.pair(
                        manualServerUrl,
                        manualFingerprint,
                        manualBootstrapToken,
                        deviceName,
                    )
                },
            )
        }

        state.capabilities?.let { capabilities -> CapabilitiesCard(capabilities) }
    }
}

@Composable
private fun PairingStartCard(
    payload: String,
    busy: Boolean,
    onPayloadChange: (String) -> Unit,
    onScan: () -> Unit,
    onImport: () -> Unit,
) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(
                stringResource(R.string.cat_pairing_start_title),
                style = androidx.compose.material3.MaterialTheme.typography.titleMedium,
            )
            Text(stringResource(R.string.cat_pairing_step, 1))
            Text(stringResource(R.string.cat_pairing_start_body))
            Button(onClick = onScan, enabled = !busy) {
                Text(stringResource(R.string.cat_pairing_scan_qr))
            }
            OutlinedTextField(
                value = payload,
                onValueChange = onPayloadChange,
                modifier = Modifier.fillMaxWidth(),
                label = { Text(stringResource(R.string.cat_pairing_paste_label)) },
                placeholder = { Text(stringResource(R.string.cat_pairing_paste_hint)) },
                supportingText = { Text(stringResource(R.string.cat_pairing_paste_supporting)) },
                minLines = 3,
                enabled = !busy,
            )
            Button(onClick = onImport, enabled = payload.isNotBlank() && !busy) {
                Text(stringResource(R.string.cat_pairing_import))
            }
        }
    }
}

@Composable
private fun PairingVerificationCard(
    preview: CatPairingPreview?,
    busy: Boolean,
    onVerify: () -> Unit,
    onChangeSource: () -> Unit,
) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(
                stringResource(R.string.cat_pairing_preview_title),
                style = androidx.compose.material3.MaterialTheme.typography.titleMedium,
            )
            Text(stringResource(R.string.cat_pairing_step, 2))
            preview?.let { Text(stringResource(R.string.cat_pairing_preview_server, it.server)) }
            Text(stringResource(R.string.cat_pairing_fingerprint_received))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = onVerify, enabled = preview != null && !busy) {
                    Text(stringResource(R.string.cat_pairing_verify))
                }
                TextButton(onClick = onChangeSource, enabled = !busy) {
                    Text(stringResource(R.string.cat_pairing_change_source))
                }
            }
        }
    }
}

@Composable
private fun PairingDeviceCard(
    deviceName: String,
    busy: Boolean,
    onDeviceNameChange: (String) -> Unit,
    onPair: () -> Unit,
) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(
                stringResource(R.string.cat_pairing_device_title),
                style = androidx.compose.material3.MaterialTheme.typography.titleMedium,
            )
            Text(stringResource(R.string.cat_pairing_step, 3))
            Text(stringResource(R.string.cat_pairing_device_body))
            OutlinedTextField(
                value = deviceName,
                onValueChange = onDeviceNameChange,
                modifier = Modifier.fillMaxWidth(),
                label = { Text(stringResource(R.string.cat_device_name)) },
                singleLine = true,
                enabled = !busy,
            )
            Button(onClick = onPair, enabled = deviceName.isNotBlank() && !busy) {
                Text(stringResource(R.string.cat_pairing_pair))
            }
        }
    }
}

@Composable
private fun ManualPairingCard(
    serverUrl: String,
    fingerprint: String,
    bootstrapToken: String,
    deviceName: String,
    busy: Boolean,
    paired: Boolean,
    onServerUrlChange: (String) -> Unit,
    onFingerprintChange: (String) -> Unit,
    onBootstrapTokenChange: (String) -> Unit,
    onDeviceNameChange: (String) -> Unit,
    onHealthCheck: () -> Unit,
    onPair: () -> Unit,
) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(stringResource(R.string.cat_manual_settings_body))
            OutlinedTextField(
                value = serverUrl,
                onValueChange = onServerUrlChange,
                modifier = Modifier.fillMaxWidth(),
                label = { Text(stringResource(R.string.cat_server_url)) },
                placeholder = { Text(stringResource(R.string.cat_server_url_hint)) },
                singleLine = true,
                enabled = !busy,
            )
            OutlinedTextField(
                value = fingerprint,
                onValueChange = onFingerprintChange,
                modifier = Modifier.fillMaxWidth(),
                label = { Text(stringResource(R.string.cat_certificate_fingerprint)) },
                placeholder = { Text(stringResource(R.string.cat_certificate_fingerprint_hint)) },
                supportingText = {
                    Text(stringResource(R.string.cat_certificate_fingerprint_supporting))
                },
                singleLine = true,
                enabled = !busy,
            )
            OutlinedTextField(
                value = bootstrapToken,
                onValueChange = onBootstrapTokenChange,
                modifier = Modifier.fillMaxWidth(),
                label = { Text(stringResource(R.string.cat_one_time_token)) },
                visualTransformation = PasswordVisualTransformation(),
                singleLine = true,
                enabled = !busy,
            )
            OutlinedTextField(
                value = deviceName,
                onValueChange = onDeviceNameChange,
                modifier = Modifier.fillMaxWidth(),
                label = { Text(stringResource(R.string.cat_device_name)) },
                singleLine = true,
                enabled = !busy,
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = onHealthCheck, enabled = !busy) {
                    Text(stringResource(R.string.cat_health_check))
                }
                Button(onClick = onPair, enabled = bootstrapToken.isNotBlank() && !busy) {
                    Text(stringResource(if (paired) R.string.cat_repair else R.string.cat_pair))
                }
            }
        }
    }
}

@Composable
private fun ConnectionDetailsCard(viewModel: CatServerViewModel) {
    val state by viewModel.state.collectAsState()
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Text(
                stringResource(R.string.cat_connection_details),
                style = androidx.compose.material3.MaterialTheme.typography.titleMedium,
            )
            state.settings.serverUrl?.let { Text(stringResource(R.string.cat_connection_server, it)) }
            Text(
                stringResource(
                    R.string.cat_connection_device,
                    state.settings.deviceName ?: stringResource(R.string.cat_not_paired),
                )
            )
            Text(
                stringResource(
                    R.string.cat_connection_last_contact,
                    state.settings.lastContactAt ?: stringResource(R.string.never),
                )
            )
            state.settings.lastErrorCode?.let {
                Text(stringResource(R.string.cat_connection_last_error, it))
            }
            HorizontalDivider()
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Text(stringResource(R.string.cat_diagnostics_upload))
                Switch(
                    checked = state.settings.diagnosticsUploadEnabled,
                    onCheckedChange = viewModel::setDiagnosticsUploadEnabled,
                )
            }
            Text(stringResource(R.string.cat_diagnostics_upload_hint))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                TextButton(onClick = viewModel::refresh, enabled = !state.busy) {
                    Text(stringResource(R.string.cat_refresh))
                }
                TextButton(onClick = viewModel::forget, enabled = !state.busy) {
                    Text(stringResource(R.string.cat_forget_pairing))
                }
            }
        }
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
                stringResource(R.string.cat_capabilities),
                style = androidx.compose.material3.MaterialTheme.typography.titleMedium,
            )
            Text(
                stringResource(
                    R.string.cat_agent_details,
                    capabilities.agentVersion,
                    capabilities.os,
                    capabilities.architecture,
                )
            )
            capabilities.engines.toSortedMap().forEach { (name, capability) ->
                val availability =
                    stringResource(
                        if (capability.supported) R.string.cat_supported else R.string.cat_unavailable
                    )
                Text("${name.uppercase()}: $availability${capability.version?.let { " ($it)" }.orEmpty()}")
            }
            Text(stringResource(R.string.cat_capability_diagnostics, onOff(capabilities.features.diagnostics)))
            Text(
                stringResource(
                    R.string.cat_capability_config_management,
                    onOff(capabilities.features.configManagement),
                )
            )
            Text(
                stringResource(
                    R.string.cat_capability_server_routing,
                    onOff(capabilities.features.serverRouting),
                )
            )
            Text(
                stringResource(
                    R.string.cat_capability_routing_backend,
                    capabilities.features.routingBackend ?: stringResource(R.string.cat_none),
                )
            )
            Text(stringResource(R.string.cat_capability_ai_gateway, onOff(capabilities.features.aiGateway)))
        }
    }
}

@Composable
private fun catStatusLabel(status: CatServerConnectionStatus): String =
    stringResource(
        when (status) {
            CatServerConnectionStatus.NOT_CONFIGURED -> R.string.cat_status_not_configured
            CatServerConnectionStatus.CONFIGURED -> R.string.cat_status_configured
            CatServerConnectionStatus.CONNECTED -> R.string.cat_status_connected
            CatServerConnectionStatus.ERROR -> R.string.cat_status_error
        }
    )

@Composable
private fun catErrorMessage(code: String): String =
    stringResource(
        when (code) {
            "PAIRING_PAYLOAD_INVALID", "PAIRING_QR_UNAVAILABLE" ->
                R.string.cat_error_pairing_payload_invalid
            "PAIRING_PAYLOAD_REQUIRED" -> R.string.cat_error_pairing_payload_required
            "CAMERA_PERMISSION_REQUIRED" -> R.string.cat_error_camera_permission
            "TLS_FINGERPRINT_REQUIRED", "TLS_FINGERPRINT_MISMATCH", "HOSTNAME_MISMATCH" ->
                R.string.cat_error_tls
            "PAIRING_EXPIRED_OR_REVOKED", "PAIRING_START_FAILED", "PAIRING_COMPLETE_FAILED" ->
                R.string.cat_error_pairing_expired
            "SERVER_UNREACHABLE" -> R.string.cat_error_unreachable
            "NOT_CONFIGURED" -> R.string.cat_error_not_configured
            else -> R.string.cat_error_generic
        }
    )

@Composable
private fun onOff(value: Boolean): String =
    stringResource(if (value) R.string.cat_on else R.string.cat_off)
