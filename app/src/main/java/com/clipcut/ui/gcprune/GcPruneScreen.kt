package com.clipcut.ui.gcprune

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.text.KeyboardOptions
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import com.clipcut.domain.gcprune.AuditEvent
import com.clipcut.domain.gcprune.Candidate
import com.clipcut.domain.gcprune.ExecSettings
import com.clipcut.domain.gcprune.RunReport
import com.clipcut.domain.gcprune.ScanReady
import com.clipcut.domain.gcprune.XPricing
import com.clipcut.domain.gcprune.describe
import com.clipcut.ui.components.Screen
import com.clipcut.ui.components.SectionCard
import com.clipcut.ui.components.StatusRow
import com.clipcut.ui.components.Tone
import com.clipcut.ui.components.WarningCard

@Composable
fun GcPruneScreen(
    modifier: Modifier = Modifier,
    viewModel: GcPruneViewModel = hiltViewModel(),
    onExit: () -> Unit = {},
) {
    val state by viewModel.state.collectAsState()

    // Back during a removal means "stop", not "leave": leaving would kill the run mid-way.
    BackHandler(enabled = state.phase == GcPhase.REMOVING) { viewModel.stop() }

    val view = LocalView.current
    DisposableEffect(state.phase) {
        view.keepScreenOn = state.phase == GcPhase.REMOVING || state.phase == GcPhase.SCANNING
        onDispose { view.keepScreenOn = false }
    }

    Screen(
        title = "GC Prune",
        subtitle = state.scan?.let { "Group ${it.groupId}" } ?: "Remove group members who don't follow your main account",
        modifier = modifier.imePadding(),
    ) {
        LazyColumn(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(14.dp)) {
            when (state.phase) {
                GcPhase.INPUT -> {
                    item { InputStep(state, viewModel) }
                    item { WhitelistCard(state, viewModel) }
                }
                GcPhase.SCANNING, GcPhase.CONFIRM_COST -> item { ScanningStep(state, viewModel) }
                GcPhase.REVIEW -> {
                    val scan = state.scan
                    if (scan != null) {
                        item { SummaryCard(scan) }
                        item { RemovalCard(state, viewModel, scan) }
                        item { Text("People who would be removed", style = MaterialTheme.typography.titleSmall) }
                        if (scan.candidates.isEmpty()) {
                            item { Text("Nobody. Everyone in this group follows you or is protected.", style = MaterialTheme.typography.bodyMedium) }
                        }
                        items(scan.candidates, key = { it.userId }) { c -> CandidateRow(c, c.userId in state.selected, viewModel) }
                        item { WhitelistCard(state, viewModel) }
                    }
                }
                GcPhase.REMOVING -> item { RemovingStep(state, viewModel) }
                GcPhase.DONE -> item { DoneStep(state, viewModel) }
            }
        }
        TextButton(onClick = onExit) { Text("Back") }
    }

    if (state.confirmOpen) ConfirmDialog(state, viewModel)
    if (state.phase == GcPhase.CONFIRM_COST) CostDialog(state, viewModel)
}

// -------------------------------------------------------------------------------------------------
@Composable
private fun InputStep(state: GcPruneUiState, vm: GcPruneViewModel) {
    Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
        WarningCard(
            "A scan never removes anyone. It lists the members of the group who do not follow you so you can review them. " +
                "Removal is a separate step that you have to switch on and confirm by typing the group id. " +
                "You, group admins and anyone on the whitelist are never removed.\n\n" +
                "Scanning reads every follower of your main account, which X bills per record. You will see the " +
                "estimated cost and must approve it before anything is read.",
        )
        SectionCard("Group chat") {
            OutlinedTextField(
                value = state.link,
                onValueChange = vm::setLink,
                label = { Text("Group chat link or id") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
            state.message?.let { StatusRow(it, Tone.Bad) }
            Button(onClick = vm::scan, enabled = state.link.isNotBlank(), modifier = Modifier.fillMaxWidth()) {
                Text("Scan (removes nobody)")
            }
        }
    }
}

@Composable
private fun ScanningStep(state: GcPruneUiState, vm: GcPruneViewModel) {
    SectionCard("Scanning") {
        Row(verticalAlignment = Alignment.CenterVertically) {
            CircularProgressIndicator(modifier = Modifier.padding(end = 12.dp))
            Text("Fetched ${state.followersFetched} followers…")
        }
        Text(
            if (state.phase == GcPhase.CONFIRM_COST) "Waiting for your approval. Nothing has been read yet." else "Reading the whole follower list. Nothing is removed in this step.",
            style = MaterialTheme.typography.bodySmall,
        )
        OutlinedButton(onClick = vm::cancelScan) { Text("Cancel") }
    }
}

@Composable
private fun SummaryCard(scan: ScanReady) {
    SectionCard("Scan result") {
        StatusRow("${scan.memberCount} members in the group", Tone.Neutral)
        StatusRow(
            "${scan.followersFetched} of ${scan.followersReported} followers fetched for @${scan.mainHandle}",
            Tone.Good,
        )
        StatusRow("${scan.candidates.size} do not follow and are not protected", if (scan.candidates.isEmpty()) Tone.Good else Tone.Neutral)
        val e = scan.exempt
        Text(
            "Left alone: ${e.own} of your own accounts, ${e.admins} admins, ${e.whitelisted} whitelisted.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(
            "Removal would run as @${scan.removerHandle}.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        scan.auditLocation?.let { Text("Saved: $it", style = MaterialTheme.typography.bodySmall) }
        scan.auditWarning?.let { StatusRow(it, Tone.Bad) }
    }
}

@Composable
private fun RemovalCard(state: GcPruneUiState, vm: GcPruneViewModel, scan: ScanReady) {
    SectionCard("Removal") {
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
            Column(modifier = Modifier.weight(1f)) {
                Text(if (state.allowRemoval) "Removal is ON" else "Preview only", style = MaterialTheme.typography.titleMedium)
                Text(
                    if (state.allowRemoval) "You can now remove the ticked people." else "Switch on to be able to remove people.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Switch(checked = state.allowRemoval, onCheckedChange = vm::setAllowRemoval)
        }
        if (state.allowRemoval) {
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                OutlinedTextField(
                    value = state.capText,
                    onValueChange = vm::setCap,
                    label = { Text("Max this run") },
                    singleLine = true,
                    isError = state.cap == null,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    supportingText = { Text("1 to ${ExecSettings.MAX_CAP}") },
                    modifier = Modifier.weight(1f),
                )
                OutlinedTextField(
                    value = state.delayText,
                    onValueChange = vm::setDelay,
                    label = { Text("Seconds apart") },
                    singleLine = true,
                    isError = state.delayMs == null,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    supportingText = { Text("1 to 600") },
                    modifier = Modifier.weight(1f),
                )
            }
            OutlinedTextField(
                value = state.typedId,
                onValueChange = vm::setTyped,
                label = { Text("Type ${scan.groupId} to confirm") },
                singleLine = true,
                textStyle = MaterialTheme.typography.bodyMedium.copy(fontFamily = FontFamily.Monospace),
                modifier = Modifier.fillMaxWidth(),
            )
            Text("${state.selectedCount} ticked", style = MaterialTheme.typography.bodySmall)
            Button(
                onClick = vm::requestRemove,
                enabled = state.canRemove,
                colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error),
                modifier = Modifier.fillMaxWidth(),
            ) {
                val n = minOf(state.selectedCount, state.cap ?: 0)
                Text(if (n > 0) "Remove up to $n…" else "Remove…")
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            TextButton(onClick = { vm.selectAll(true) }) { Text("Tick all") }
            TextButton(onClick = { vm.selectAll(false) }) { Text("Untick all") }
            TextButton(onClick = vm::reset) { Text("Start over") }
        }
    }
}

@Composable
private fun CandidateRow(c: Candidate, selected: Boolean, vm: GcPruneViewModel) {
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
        Checkbox(checked = selected, onCheckedChange = { vm.toggle(c.userId) })
        Column(modifier = Modifier.weight(1f)) {
            Text(
                c.handle?.let { "@$it" } ?: "(account unavailable)",
                style = MaterialTheme.typography.bodyLarge,
            )
            Text(
                listOfNotNull(c.name, "id ${c.userId}").joinToString("  -  "),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(c.reason, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        TextButton(onClick = { vm.keep(c.userId) }) { Text("Keep") }
    }
}

@Composable
private fun RemovingStep(state: GcPruneUiState, vm: GcPruneViewModel) {
    val report = state.report
    SectionCard("Removing") {
        Row(verticalAlignment = Alignment.CenterVertically) {
            CircularProgressIndicator(modifier = Modifier.padding(end = 12.dp))
            Text("Removed ${report?.removed?.size ?: 0} so far")
        }
        Text("One at a time, with a pause between each. Keep this screen open.", style = MaterialTheme.typography.bodySmall)
        report?.let { ReportLists(it) }
        OutlinedButton(onClick = vm::stop, modifier = Modifier.fillMaxWidth()) { Text("Stop after the current person") }
    }
}

@Composable
private fun DoneStep(state: GcPruneUiState, vm: GcPruneViewModel) {
    val report = state.report
    SectionCard("Finished") {
        if (report == null) {
            Text("No report.")
        } else {
            Text(report.stop.describe(), style = MaterialTheme.typography.titleSmall)
            Text(
                "Removed ${report.removed.size}  -  skipped ${report.skipped.size}  -  failed ${report.failures.size}",
                style = MaterialTheme.typography.bodyMedium,
            )
            ReportLists(report)
            report.auditLocation?.let { Text("Audit file: $it", style = MaterialTheme.typography.bodySmall) }
        }
        Button(onClick = vm::reset, modifier = Modifier.fillMaxWidth()) { Text("Back to start (scan again for a fresh list)") }
    }
}

@Composable
private fun ReportLists(report: RunReport) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        report.removed.takeLast(40).forEach { EventLine(it, Tone.Good) }
        report.skipped.takeLast(40).forEach { EventLine(it, Tone.Neutral) }
        report.failures.forEach { EventLine(it, Tone.Bad) }
    }
}

@Composable
private fun EventLine(e: AuditEvent, tone: Tone) {
    StatusRow("${e.person.handle?.let { "@$it" } ?: "id ${e.person.userId}"}: ${e.outcome} - ${e.detail}", tone)
}

@Composable
private fun WhitelistCard(state: GcPruneUiState, vm: GcPruneViewModel) {
    SectionCard("Whitelist (never removed)") {
        if (state.whitelist.isEmpty()) {
            Text("Nobody yet.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        state.whitelist.sorted().forEach { id ->
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                Text(id, style = MaterialTheme.typography.bodyMedium, fontFamily = FontFamily.Monospace, modifier = Modifier.weight(1f))
                TextButton(onClick = { vm.removeFromWhitelist(id) }) { Text("Remove") }
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            OutlinedTextField(
                value = state.whitelistInput,
                onValueChange = vm::setWhitelistInput,
                label = { Text("User id or @handle") },
                singleLine = true,
                modifier = Modifier.weight(1f),
            )
            Button(onClick = vm::addToWhitelist, enabled = state.whitelistInput.isNotBlank()) { Text("Add") }
        }
        state.whitelistMessage?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
    }
}

@Composable
private fun CostDialog(state: GcPruneUiState, vm: GcPruneViewModel) {
    val e = state.estimate ?: return
    val cost = "%.2f".format(e.estimatedUsd)
    AlertDialog(
        onDismissRequest = vm::declineScan,
        title = { Text("Approve the cost of this scan?") },
        text = {
            Text(
                "Scanning reads all ${e.followers} followers of your main account. X charges about " +
                    "${'$'}${XPricing.USER_READ_USD} for each record, so this scan will cost roughly ${'$'}$cost.\n\n" +
                    "X bills each person once per UTC day (the day changes at 05:30 IST), so scanning again today costs far less. " +
                    "Prices can change; X's usage page is the source of truth.\n\nNothing has been read yet.",
            )
        },
        confirmButton = { TextButton(onClick = vm::confirmScan) { Text("Scan (about ${'$'}$cost)") } },
        dismissButton = { TextButton(onClick = vm::declineScan) { Text("Cancel") } },
    )
}

@Composable
private fun ConfirmDialog(state: GcPruneUiState, vm: GcPruneViewModel) {
    val scan = state.scan ?: return
    val n = minOf(state.selectedCount, state.cap ?: 0)
    AlertDialog(
        onDismissRequest = vm::dismissConfirm,
        title = { Text("Remove up to $n people?") },
        text = {
            Text(
                "@${scan.removerHandle} will remove up to $n of the ${state.selectedCount} ticked people from ${scan.groupId}, " +
                    "one at a time, ${(state.delayMs ?: 0) / 1000} seconds apart. Each is checked again immediately before removal, " +
                    "and the run stops at the first problem.\n\nThe app cannot undo this. Who was removed is saved in the audit file.",
            )
        },
        confirmButton = {
            TextButton(onClick = vm::confirmRemove) { Text("Remove", color = MaterialTheme.colorScheme.error) }
        },
        dismissButton = { TextButton(onClick = vm::dismissConfirm) { Text("Cancel") } },
    )
}
