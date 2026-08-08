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
import androidx.compose.ui.unit.dp
import com.dedtsss.catawg.core.routing.DomainMatchMode
import com.dedtsss.catawg.core.routing.DomainRouteTarget
import com.dedtsss.catawg.core.routing.DomainRule
import com.dedtsss.catawg.core.routing.SharedIpIndex
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
    var exportJson by rememberSaveable { mutableStateOf(true) }
    val exportLauncher =
        rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("text/plain")) { uri ->
            if (uri == null) return@rememberLauncherForActivityResult
            scope.launch {
                runCatching {
                        val data = if (exportJson) viewModel.exportJson() else viewModel.exportTxt()
                        requireNotNull(context.contentResolver.openOutputStream(uri)) {
                            "Could not open selected export destination"
                        }.bufferedWriter().use { it.write(data) }
                    }
                    .onFailure { viewModel.reportError(it.message ?: "Could not export domain rules") }
            }
        }
    val importLauncher =
        rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
            if (uri == null) return@rememberLauncherForActivityResult
            scope.launch {
                runCatching {
                        requireNotNull(context.contentResolver.openInputStream(uri)) {
                            "Could not open selected import file"
                        }.bufferedReader().use { it.readText() }
                    }
                    .onSuccess(viewModel::importRules)
                    .onFailure { viewModel.reportError(it.message ?: "Could not import domain rules") }
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
            label = { Text("Search sites") },
            singleLine = true,
        )
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(onClick = { showAdd = true }) { Text("Add site") }
            TextButton(onClick = viewModel::refresh, enabled = !state.isWorking) { Text("Refresh IPs") }
            TextButton(onClick = { showDiagnose = true }) { Text("Why does this site not work?") }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            TextButton(
                onClick = {
                    exportJson = true
                    exportLauncher.launch("cat-domain-rules.json")
                },
            ) { Text("Export JSON") }
            TextButton(
                onClick = {
                    exportJson = false
                    exportLauncher.launch("cat-domain-rules.txt")
                },
            ) { Text("Export TXT") }
            TextButton(onClick = { importLauncher.launch(arrayOf("application/json", "text/plain")) }) {
                Text("Import")
            }
        }
        state.error?.let { error ->
            Card(modifier = Modifier.fillMaxWidth()) {
                Row(modifier = Modifier.padding(12.dp), horizontalArrangement = Arrangement.SpaceBetween) {
                    Text(error, modifier = Modifier.weight(1f))
                    TextButton(onClick = viewModel::clearError) { Text("Dismiss") }
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
                    onDelete = { viewModel.delete(rule) },
                )
            }
        }
    }

    if (showAdd) {
        AddDomainDialog(
            onDismiss = { showAdd = false },
            onAdd = { domain, mode, target, comment ->
                viewModel.add(domain, mode, target, comment)
                showAdd = false
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
        val sourceByRuleId = state.rules.associateBy { it.id }
        AlertDialog(
            onDismissRequest = viewModel::dismissDiagnosis,
            confirmButton = { TextButton(onClick = viewModel::dismissDiagnosis) { Text("Close") } },
            title = { Text("Site diagnostics: ${diagnosis.domain}") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(
                        "IPv4: ${diagnosis.ipv4.joinToString { it.describe(it.ruleId?.let(sourceByRuleId::get)?.source?.name) }.ifBlank { "none" }}"
                    )
                    Text(
                        "IPv6: ${diagnosis.ipv6.joinToString { it.describe(it.ruleId?.let(sourceByRuleId::get)?.source?.name) }.ifBlank { "none" }}"
                    )
                    Text("Cache: ${if (diagnosis.stale) "stale" else "current"}; changed IP: ${diagnosis.changedIp}")
                    Text("Lookup status: ${diagnosis.resolutionStatus ?: "unknown"}")
                    diagnosis.ipv4.flatMap { it.sharedWithDomains }.distinct().takeIf { it.isNotEmpty() }?.let {
                        Text("Shared IP warning: ${it.joinToString()}")
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
    onDelete: () -> Unit,
) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Row(horizontalArrangement = Arrangement.SpaceBetween, modifier = Modifier.fillMaxWidth()) {
                Text(rule.domain, modifier = Modifier.weight(1f))
                Switch(checked = rule.enabled, onCheckedChange = { onToggle() })
            }
            Text("${rule.matchMode} • ${rule.routeTarget} • ${rule.source}")
            Text("IPv4: ${rule.resolvedIpv4.filter { it.isCurrent }.joinToString { it.address }.ifBlank { "not resolved" }}")
            Text("IPv6: ${rule.resolvedIpv6.filter { it.isCurrent }.joinToString { it.address }.ifBlank { "not resolved" }}")
            val historical = (rule.resolvedIpv4 + rule.resolvedIpv6).count { !it.isCurrent }
            if (historical > 0) Text("Historical IPs retained for diagnostics: $historical")
            if (sharedDomains.isNotEmpty()) Text("Shared IP warning: ${sharedDomains.joinToString()}")
            Text("Last resolve: ${rule.lastResolvedAt ?: rule.lastResolveStatus}")
            rule.comment?.let { Text("Note: $it") }
            TextButton(onClick = onDelete) { Text("Delete") }
        }
    }
}

private fun com.dedtsss.catawg.core.routing.DiagnosedAddress.describe(source: String?): String =
    buildString {
        append(address)
        append(" → ")
        append(route)
        ruleDomain?.let { domain ->
            append(" via ")
            append(domain)
            source?.let {
                append(" (")
                append(it)
                append(")")
            }
        }
    }

@Composable
private fun AddDomainDialog(
    onDismiss: () -> Unit,
    onAdd: (String, DomainMatchMode, DomainRouteTarget, String?) -> Unit,
) {
    var domain by rememberSaveable { mutableStateOf("") }
    var exact by rememberSaveable { mutableStateOf(false) }
    var localDirect by rememberSaveable { mutableStateOf(true) }
    var comment by rememberSaveable { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onDismiss,
        confirmButton = {
            TextButton(onClick = { onAdd(domain, if (exact) DomainMatchMode.EXACT else DomainMatchMode.SUFFIX, if (localDirect) DomainRouteTarget.LOCAL_DIRECT else DomainRouteTarget.DEFAULT_TUNNEL, comment) }) {
                Text("Add")
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
        title = { Text("Add site") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(value = domain, onValueChange = { domain = it }, label = { Text("Domain or URL") })
                Row { Switch(checked = exact, onCheckedChange = { exact = it }); Text(if (exact) "Exact hostname" else "Domain + subdomains") }
                Row { Switch(checked = localDirect, onCheckedChange = { localDirect = it }); Text(if (localDirect) "Local direct" else "Default tunnel") }
                OutlinedTextField(value = comment, onValueChange = { comment = it }, label = { Text("Comment (optional)") })
            }
        },
    )
}

@Composable
private fun DiagnoseDomainDialog(onDismiss: () -> Unit, onRun: (String) -> Unit) {
    var value by rememberSaveable { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onDismiss,
        confirmButton = { TextButton(onClick = { onRun(value) }) { Text("Diagnose") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
        title = { Text("Why does this site not work?") },
        text = { OutlinedTextField(value = value, onValueChange = { value = it }, label = { Text("Domain or URL") }) },
    )
}
