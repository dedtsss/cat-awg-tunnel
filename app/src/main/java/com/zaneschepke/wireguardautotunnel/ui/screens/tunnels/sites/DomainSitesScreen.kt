package com.zaneschepke.wireguardautotunnel.ui.screens.tunnels.sites

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
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
import com.dedtsss.catawg.core.routing.DomainMatchMode
import com.dedtsss.catawg.core.routing.DomainRouteTarget
import com.dedtsss.catawg.core.routing.DomainRule
import com.dedtsss.catawg.core.routing.SharedIpIndex
import com.zaneschepke.wireguardautotunnel.R
import com.zaneschepke.wireguardautotunnel.viewmodel.DomainSitesViewModel
import kotlinx.coroutines.launch

/** Compact per-tunnel Sites screen; all edits go through the resolver/rebuild coordinator. */
@Composable
fun DomainSitesScreen(viewModel: DomainSitesViewModel) {
    val state by viewModel.state.collectAsState()
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var search by rememberSaveable { mutableStateOf("") }
    var showAdd by rememberSaveable { mutableStateOf(false) }
    var showDiagnose by rememberSaveable { mutableStateOf(false) }
    var editingRule by remember { mutableStateOf<DomainRule?>(null) }
    var exportJson by rememberSaveable { mutableStateOf(true) }
    val exportLauncher =
        rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("text/plain")) { uri ->
            if (uri == null) return@rememberLauncherForActivityResult
            scope.launch {
                runCatching {
                        val data = if (exportJson) viewModel.exportJson() else viewModel.exportTxt()
                        requireNotNull(context.contentResolver.openOutputStream(uri)) {
                            context.getString(R.string.domain_sites_output_error)
                        }.bufferedWriter().use { it.write(data) }
                    }
                    .onFailure {
                        viewModel.reportError(context.getString(R.string.domain_sites_export_error))
                    }
            }
        }
    val importLauncher =
        rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
            if (uri == null) return@rememberLauncherForActivityResult
            scope.launch {
                runCatching {
                        requireNotNull(context.contentResolver.openInputStream(uri)) {
                            context.getString(R.string.domain_sites_input_error)
                        }.bufferedReader().use { it.readText() }
                    }
                    .onSuccess(viewModel::importRules)
                    .onFailure {
                        viewModel.reportError(context.getString(R.string.domain_sites_import_error))
                    }
            }
        }
    val filtered = state.rules.filter { it.domain.contains(search, ignoreCase = true) }
    val sharedDomainsByAddress =
        remember(state.rules) {
            SharedIpIndex.conflicts(state.rules).associate { conflict ->
                conflict.address to conflict.domains
            }
        }

    Column(modifier = Modifier.fillMaxSize().padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        OutlinedTextField(
            value = search,
            onValueChange = { search = it },
            modifier = Modifier.fillMaxWidth(),
            label = { Text(stringResource(R.string.domain_sites_search)) },
            singleLine = true,
        )
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(onClick = { showAdd = true }) {
                Text(stringResource(R.string.domain_sites_add))
            }
            if (!state.isGlobalScope) {
                TextButton(
                    onClick = viewModel::copyGlobal,
                    enabled = state.globalRules.isNotEmpty() && !state.isWorking,
                ) {
                    Text(stringResource(R.string.copy_global))
                }
            }
            TextButton(onClick = viewModel::refresh, enabled = !state.isWorking) {
                Text(stringResource(R.string.domain_sites_refresh_ips))
            }
            TextButton(onClick = { showDiagnose = true }) {
                Text(stringResource(R.string.domain_sites_diagnose))
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            TextButton(
                onClick = {
                    exportJson = true
                    exportLauncher.launch("cat-domain-rules.json")
                },
            ) { Text(stringResource(R.string.domain_sites_export_json)) }
            TextButton(
                onClick = {
                    exportJson = false
                    exportLauncher.launch("cat-domain-rules.txt")
                },
            ) { Text(stringResource(R.string.domain_sites_export_txt)) }
            TextButton(onClick = { importLauncher.launch(arrayOf("application/json", "text/plain")) }) {
                Text(stringResource(R.string.import_action))
            }
        }
        state.error?.let { error ->
            Card(modifier = Modifier.fillMaxWidth()) {
                Row(modifier = Modifier.padding(12.dp), horizontalArrangement = Arrangement.SpaceBetween) {
                    Text(error, modifier = Modifier.weight(1f))
                    TextButton(onClick = viewModel::clearError) { Text(stringResource(R.string.dismiss)) }
                }
            }
        }
        LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.weight(1f)) {
            items(filtered, key = { it.id }) { rule ->
                val sharedDomains =
                    (rule.resolvedIpv4 + rule.resolvedIpv6)
                        .flatMap { address -> sharedDomainsByAddress[address.address].orEmpty() }
                        .filter { it != rule.domain }
                        .distinct()
                DomainRuleRow(
                    rule = rule,
                    sharedDomains = sharedDomains,
                    onToggle = { viewModel.toggle(rule) },
                    onEdit = { editingRule = rule },
                    onDelete = { viewModel.delete(rule) },
                )
            }
        }
    }

    if (showAdd || editingRule != null) {
        DomainRuleDialog(
            initial = editingRule,
            onDismiss = {
                showAdd = false
                editingRule = null
            },
            onAdd = { domain, mode, target, comment ->
                editingRule?.let { rule ->
                    viewModel.update(rule, domain, mode, target, comment)
                } ?: viewModel.add(domain, mode, target, comment)
                showAdd = false
                editingRule = null
            },
        )
    }
    if (showDiagnose) {
        DiagnoseDomainDialog(
            onDismiss = { showDiagnose = false },
            onRun = { value ->
                viewModel.diagnose(value)
                showDiagnose = false
            },
        )
    }
    state.diagnosis?.let { diagnosis ->
        AlertDialog(
            onDismissRequest = viewModel::dismissDiagnosis,
            confirmButton = {
                TextButton(onClick = viewModel::dismissDiagnosis) { Text(stringResource(R.string.close)) }
            },
            title = { Text(stringResource(R.string.domain_sites_diagnostics_title, diagnosis.domain)) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(
                        stringResource(
                            R.string.domain_sites_ipv4,
                            diagnosis.ipv4
                                .joinToString { it.describe() }
                                .ifBlank { stringResource(R.string.domain_sites_none) },
                        )
                    )
                    Text(
                        stringResource(
                            R.string.domain_sites_ipv6,
                            diagnosis.ipv6
                                .joinToString { it.describe() }
                                .ifBlank { stringResource(R.string.domain_sites_none) },
                        )
                    )
                    Text(
                        stringResource(
                            R.string.domain_sites_cache,
                            stringResource(
                                if (diagnosis.stale) {
                                    R.string.domain_sites_cache_stale
                                } else {
                                    R.string.domain_sites_cache_current
                                }
                            ),
                            stringResource(
                                if (diagnosis.changedIp) {
                                    R.string.domain_sites_yes
                                } else {
                                    R.string.domain_sites_no
                                }
                            ),
                        )
                    )
                    Text(
                        stringResource(
                            R.string.domain_sites_lookup_status,
                            diagnosis.resolutionStatus?.let { status ->
                                resolutionStatusLabel(status)
                            }
                                ?: stringResource(R.string.domain_sites_unknown),
                        )
                    )
                    (diagnosis.ipv4 + diagnosis.ipv6)
                        .flatMap { it.sharedWithDomains }
                        .distinct()
                        .takeIf { it.isNotEmpty() }
                        ?.let {
                        Text(stringResource(R.string.domain_sites_shared_ip_warning, it.joinToString()))
                    }
                    Text(diagnosis.evidenceNote)
                }
            },
        )
    }
}

@Composable
private fun DomainRuleRow(
    rule: DomainRule,
    sharedDomains: List<String>,
    onToggle: () -> Unit,
    onEdit: () -> Unit,
    onDelete: () -> Unit,
) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Row(horizontalArrangement = Arrangement.SpaceBetween, modifier = Modifier.fillMaxWidth()) {
                Text(rule.domain, modifier = Modifier.weight(1f))
                Switch(checked = rule.enabled, onCheckedChange = { onToggle() })
            }
            Text(
                stringResource(
                    R.string.domain_sites_rule_metadata,
                    matchModeLabel(rule.matchMode),
                    routeTargetLabel(rule.routeTarget),
                    ruleSourceLabel(rule.source),
                )
            )
            Text(
                stringResource(
                    R.string.domain_sites_ipv4,
                    rule.resolvedIpv4
                        .filter { it.isCurrent }
                        .joinToString { it.address }
                        .ifBlank { stringResource(R.string.domain_sites_not_resolved) },
                )
            )
            Text(
                stringResource(
                    R.string.domain_sites_ipv6,
                    rule.resolvedIpv6
                        .filter { it.isCurrent }
                        .joinToString { it.address }
                        .ifBlank { stringResource(R.string.domain_sites_not_resolved) },
                )
            )
            val historical = (rule.resolvedIpv4 + rule.resolvedIpv6).count { !it.isCurrent }
            if (historical > 0) {
                Text(stringResource(R.string.domain_sites_historical_ips, historical))
            }
            if (sharedDomains.isNotEmpty()) {
                Text(
                    stringResource(
                        R.string.domain_sites_shared_ip_warning,
                        sharedDomains.joinToString(),
                    )
                )
            }
            Text(
                stringResource(
                    R.string.domain_sites_last_resolve,
                    rule.lastResolvedAt ?: resolutionStatusLabel(rule.lastResolveStatus),
                )
            )
            rule.comment?.let { Text(stringResource(R.string.domain_sites_note, it)) }
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                TextButton(onClick = onEdit) { Text(stringResource(R.string.domain_sites_edit)) }
                TextButton(onClick = onDelete) { Text(stringResource(R.string.delete)) }
            }
        }
    }
}

private fun com.dedtsss.catawg.core.routing.DiagnosedAddress.describe(): String =
    buildString {
        append(address)
        append(" → ")
        append(route)
        ruleDomain?.let { append(" ($it)") }
        knownByDomains.takeIf { it.isNotEmpty() }?.let { append("; known: ").append(it.joinToString()) }
    }

@Composable
private fun DomainRuleDialog(
    initial: DomainRule?,
    onDismiss: () -> Unit,
    onAdd: (String, DomainMatchMode, DomainRouteTarget, String?) -> Unit,
) {
    var domain by rememberSaveable(initial?.id) { mutableStateOf(initial?.domain.orEmpty()) }
    var exact by rememberSaveable(initial?.id) { mutableStateOf(initial?.matchMode == DomainMatchMode.EXACT) }
    var localDirect by rememberSaveable(initial?.id) {
        mutableStateOf(initial?.routeTarget != DomainRouteTarget.DEFAULT_TUNNEL)
    }
    var comment by rememberSaveable(initial?.id) { mutableStateOf(initial?.comment.orEmpty()) }
    AlertDialog(
        onDismissRequest = onDismiss,
        confirmButton = {
            TextButton(onClick = {
                onAdd(
                    domain,
                    if (exact) DomainMatchMode.EXACT else DomainMatchMode.SUFFIX,
                    if (localDirect) DomainRouteTarget.LOCAL_DIRECT else DomainRouteTarget.DEFAULT_TUNNEL,
                    comment,
                )
            }) {
                Text(stringResource(if (initial == null) R.string.add else R.string.domain_sites_update))
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) } },
        title = {
            Text(stringResource(if (initial == null) R.string.domain_sites_add else R.string.domain_sites_edit))
        },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                    value = domain,
                    onValueChange = { domain = it },
                    label = { Text(stringResource(R.string.domain_sites_domain_or_url)) },
                )
                Row {
                    Switch(checked = exact, onCheckedChange = { exact = it })
                    Text(
                        stringResource(
                            if (exact) {
                                R.string.domain_sites_exact_hostname
                            } else {
                                R.string.domain_sites_suffix
                            }
                        )
                    )
                }
                Row {
                    Switch(checked = localDirect, onCheckedChange = { localDirect = it })
                    Text(
                        stringResource(
                            if (localDirect) {
                                R.string.domain_sites_local_direct
                            } else {
                                R.string.domain_sites_default_tunnel
                            }
                        )
                    )
                }
                OutlinedTextField(
                    value = comment,
                    onValueChange = { comment = it },
                    label = { Text(stringResource(R.string.domain_sites_comment_optional)) },
                )
            }
        },
    )
}

@Composable
private fun DiagnoseDomainDialog(onDismiss: () -> Unit, onRun: (String) -> Unit) {
    var value by rememberSaveable { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onDismiss,
        confirmButton = {
            TextButton(onClick = { onRun(value) }) { Text(stringResource(R.string.diagnose)) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) } },
        title = { Text(stringResource(R.string.domain_sites_diagnose)) },
        text = {
            OutlinedTextField(
                value = value,
                onValueChange = { value = it },
                label = { Text(stringResource(R.string.domain_sites_domain_or_url)) },
            )
        },
    )
}

@Composable
private fun matchModeLabel(value: DomainMatchMode): String =
    stringResource(
        if (value == DomainMatchMode.EXACT) {
            R.string.domain_sites_match_exact
        } else {
            R.string.domain_sites_match_suffix
        }
    )

@Composable
private fun routeTargetLabel(value: DomainRouteTarget): String =
    stringResource(
        when (value) {
            DomainRouteTarget.LOCAL_DIRECT -> R.string.domain_sites_route_local
            DomainRouteTarget.DEFAULT_TUNNEL -> R.string.domain_sites_route_default
            DomainRouteTarget.SERVER_EGRESS -> R.string.domain_sites_route_server
            DomainRouteTarget.BLOCK -> R.string.domain_sites_route_block
        }
    )

@Composable
private fun ruleSourceLabel(value: com.dedtsss.catawg.core.routing.DomainRuleSource): String =
    stringResource(
        when (value) {
            com.dedtsss.catawg.core.routing.DomainRuleSource.MANUAL ->
                R.string.domain_sites_source_manual
            com.dedtsss.catawg.core.routing.DomainRuleSource.SHARE ->
                R.string.domain_sites_source_share
            com.dedtsss.catawg.core.routing.DomainRuleSource.IMPORT ->
                R.string.domain_sites_source_import
        }
    )

@Composable
private fun resolutionStatusLabel(value: com.dedtsss.catawg.core.routing.DomainResolutionStatus): String =
    stringResource(
        when (value) {
            com.dedtsss.catawg.core.routing.DomainResolutionStatus.NEVER ->
                R.string.domain_sites_status_never
            com.dedtsss.catawg.core.routing.DomainResolutionStatus.SUCCESS ->
                R.string.domain_sites_status_success
            com.dedtsss.catawg.core.routing.DomainResolutionStatus.EMPTY ->
                R.string.domain_sites_status_empty
            com.dedtsss.catawg.core.routing.DomainResolutionStatus.TIMEOUT ->
                R.string.domain_sites_status_timeout
            com.dedtsss.catawg.core.routing.DomainResolutionStatus.FAILED ->
                R.string.domain_sites_status_failed
        }
    )
