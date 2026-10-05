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
import androidx.compose.foundation.text.KeyboardOptions
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
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import com.clipcut.domain.gcprune.AuditEvent
import com.clipcut.domain.gcprune.ExecSettings
import com.clipcut.domain.gcprune.LookupReady
import com.clipcut.domain.gcprune.PrunePlanner
import com.clipcut.domain.gcprune.RunReport
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
        view.keepScreenOn = state.phase == GcPhase.REMOVING || state.phase == GcPhase.LOOKING_UP
        onDispose { view.keepScreenOn = false }
    }

    Screen(
        title = "GC Prune",
        subtitle = state.lookup?.let { "Group ${it.groupId}" } ?: "Remove the people you list from a group chat",
        modifier = modifier.imePadding(),
    ) {
        LazyColumn(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(14.dp)) {
            when (state.phase) {
                GcPhase.INPUT -> {
                    item { InputStep(state, viewModel) }
                    item { WhitelistCard(state, viewModel) }
                }
                GcPhase.LOOKING_UP -> item { LookingUpStep(viewModel) }
                GcPhase.REVIEW -> {
                    val lookup = state.lookup
                    if (lookup != null) {
                        item { SummaryCard(lookup) }
                        item { RemovalCard(state, viewModel, lookup) }
                        item { Text("Your list", style = MaterialTheme.typography.titleSmall) }
                        items(lookup.rows, key = { it.userId ?: ("name:" + it.requested) }) { r ->
                            PersonRow(r, r.userId != null && r.userId in state.selected, viewModel)
                        }
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
}

// -------------------------------------------------------------------------------------------------
@Composable
private fun InputStep(state: GcPruneUiState, vm: GcPruneViewModel) {
    Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
        WarningCard(
            "Paste the usernames of the people to remove. A lookup never removes anyone: it shows who is in the group, " +
                "who follows you and who is protected. Removal is a separate step that you have to switch on and confirm by " +
                "typing the group id. You, group admins and anyone on the whitelist are never removed.",
        )
        SectionCard("Group chat") {
            OutlinedTextField(
                value = state.link,
                onValueChange = vm::setLink,
                label = { Text("Group chat link or id") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
        }
        SectionCard("People to remove") {
            OutlinedTextField(
                value = state.namesText,
                onValueChange = vm::setNames,
                label = { Text("Usernames") },
                supportingText = { Text("One per line, or separated by spaces or commas. @name and x.com/name links are fine.") },
                minLines = 5,
                modifier = Modifier.fillMaxWidth(),
            )
            if (state.namesText.isNotBlank()) {
                Text("${state.parsed.valid.size} usernames understood", style = MaterialTheme.typography.bodySmall)
            }
            if (state.parsed.invalid.isNotEmpty()) {
                StatusRow("Not understood, so left out: " + state.parsed.invalid.joinToString(", "), Tone.Bad)
            }
            if (state.parsed.tooMany) {
                StatusRow("That is more than ${com.clipcut.domain.gcprune.HandleList.MAX_NAMES} names. Do them in smaller lists.", Tone.Bad)
            }
            state.message?.let { StatusRow(it, Tone.Bad) }
            Button(onClick = vm::lookUp, enabled = state.canLookUp, modifier = Modifier.fillMaxWidth()) {
                val n = state.parsed.valid.size
                Text(if (n > 0) "Look up $n people (about $" + "%.2f".format(state.lookupCostUsd) + ")" else "Look up (removes nobody)")
            }
            Text(
                "X charges about 1 cent for each account looked up, plus one extra to check the follow answers can be trusted. " +
                    "Nothing is removed in this step.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun LookingUpStep(vm: GcPruneViewModel) {
    SectionCard("Looking up") {
        Row(verticalAlignment = Alignment.CenterVertically) {
            CircularProgressIndicator(modifier = Modifier.padding(end = 12.dp))
            Text("Reading the group and the usernames…")
        }
        Text("Nothing is removed in this step.", style = MaterialTheme.typography.bodySmall)
        OutlinedButton(onClick = vm::cancelLookup) { Text("Cancel") }
    }
}

@Composable
private fun SummaryCard(lookup: LookupReady) {
    val rows = lookup.rows
    val removable = rows.count { it.status is PrunePlanner.Status.Removable }
    val follows = rows.count { it.status is PrunePlanner.Status.FollowsYou }
    val others = rows.size - removable - follows
    SectionCard("Lookup result") {
        StatusRow("${lookup.memberCount} members in the group", Tone.Neutral)
        StatusRow("$removable can be removed (in the group, not following @${lookup.mainHandle})", if (removable > 0) Tone.Neutral else Tone.Good)
        if (follows > 0) StatusRow("$follows follow @${lookup.mainHandle}: not ticked, check they are the right people", Tone.Bad)
        if (others > 0) StatusRow("$others cannot be removed (not in the group, protected or not found)", Tone.Neutral)
        if (!lookup.followCheckTrusted) {
            StatusRow(
                "X did not confirm that a known follower follows you, so the follow warnings cannot be relied on. " +
                    "Nobody is ticked: tick only people you are sure about.",
                Tone.Bad,
            )
        }
        Text(
            "Removal would run as @${lookup.removerHandle}.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        lookup.auditLocation?.let { Text("Saved: $it", style = MaterialTheme.typography.bodySmall) }
        lookup.auditWarning?.let { StatusRow(it, Tone.Bad) }
    }
}

@Composable
private fun RemovalCard(state: GcPruneUiState, vm: GcPruneViewModel, lookup: LookupReady) {
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
                label = { Text("Type ${lookup.groupId} to confirm") },
                singleLine = true,
                textStyle = MaterialTheme.typography.bodyMedium.copy(fontFamily = FontFamily.Monospace),
                modifier = Modifier.fillMaxWidth(),
            )
            Text("${state.selectedCount} ticked", style = MaterialTheme.typography.bodySmall)
            if (state.tickedFollowers > 0) {
                StatusRow("${state.tickedFollowers} of the ticked people follow @${lookup.mainHandle}.", Tone.Bad)
            }
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

private fun statusLine(r: PrunePlanner.Row, mainHandle: String? = null): String = when (val s = r.status) {
    PrunePlanner.Status.Removable -> "In the group, does not follow you"
    PrunePlanner.Status.FollowsYou -> "⚠ Follows you" + (mainHandle?.let { " (@$it)" } ?: "")
    PrunePlanner.Status.NotInGroup -> "Not in this group"
    is PrunePlanner.Status.Protected -> "Protected: ${s.why}"
    is PrunePlanner.Status.NotFound -> "Not found: ${s.reason}"
}

@Composable
private fun PersonRow(r: PrunePlanner.Row, selected: Boolean, vm: GcPruneViewModel) {
    val dim = if (r.selectable) 1f else 0.5f
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
        Checkbox(checked = selected && r.selectable, enabled = r.selectable, onCheckedChange = { r.userId?.let(vm::toggle) })
        Column(modifier = Modifier.weight(1f)) {
            Text(
                r.handle?.let { "@$it" } ?: r.requested,
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = dim),
            )
            Text(
                listOfNotNull(r.name, r.userId?.let { "id $it" }).joinToString("  -  ").ifEmpty { " " },
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = dim),
            )
            Text(
                statusLine(r),
                style = MaterialTheme.typography.bodySmall,
                color = (if (r.status is PrunePlanner.Status.FollowsYou) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant)
                    .copy(alpha = if (r.status is PrunePlanner.Status.FollowsYou) 1f else dim),
            )
        }
        if (r.selectable) TextButton(onClick = { r.userId?.let(vm::keep) }) { Text("Keep") }
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
        Button(onClick = vm::reset, modifier = Modifier.fillMaxWidth()) { Text("Back to start") }
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
private fun ConfirmDialog(state: GcPruneUiState, vm: GcPruneViewModel) {
    val lookup = state.lookup ?: return
    val n = minOf(state.selectedCount, state.cap ?: 0)
    val followers = if (state.tickedFollowers > 0) {
        "\n\n⚠ ${state.tickedFollowers} of the ticked people FOLLOW @${lookup.mainHandle}. Cancel and untick them unless you mean it."
    } else {
        ""
    }
    AlertDialog(
        onDismissRequest = vm::dismissConfirm,
        title = { Text("Remove up to $n people?") },
        text = {
            Text(
                "@${lookup.removerHandle} will remove up to $n of the ${state.selectedCount} ticked people from ${lookup.groupId}, " +
                    "one at a time, ${(state.delayMs ?: 0) / 1000} seconds apart. Each is checked against a fresh read of the group first, " +
                    "and the run stops at the first problem.\n\nThe app cannot undo this. Who was removed is saved in the audit file." + followers,
            )
        },
        confirmButton = {
            TextButton(onClick = vm::confirmRemove) { Text("Remove", color = MaterialTheme.colorScheme.error) }
        },
        dismissButton = { TextButton(onClick = vm::dismissConfirm) { Text("Cancel") } },
    )
}
