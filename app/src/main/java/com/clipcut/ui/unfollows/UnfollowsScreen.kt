package com.clipcut.ui.unfollows

import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import com.clipcut.domain.unfollows.Automation
import com.clipcut.domain.unfollows.GcStatus
import com.clipcut.domain.unfollows.GcTone
import com.clipcut.domain.unfollows.TrackerStatus
import com.clipcut.domain.unfollows.UnfollowRow
import com.clipcut.ui.components.Screen
import com.clipcut.ui.components.SectionCard
import com.clipcut.ui.components.StatusRow
import com.clipcut.ui.components.Tone
import com.clipcut.ui.components.WarningCard
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

private val DAY = DateTimeFormatter.ofPattern("EEE d MMM yyyy", Locale.getDefault())
private val CLOCK = DateTimeFormatter.ofPattern("HH:mm", Locale.getDefault())

private fun day(ms: Long): LocalDate = Instant.ofEpochMilli(ms).atZone(ZoneId.systemDefault()).toLocalDate()

private fun clock(ms: Long?): String? = ms?.let { Instant.ofEpochMilli(it).atZone(ZoneId.systemDefault()).format(CLOCK) }

private fun stamp(iso: String?): String =
    iso?.let { runCatching { Instant.parse(it).atZone(ZoneId.systemDefault()).format(DateTimeFormatter.ofPattern("d MMM, HH:mm")) }.getOrNull() } ?: "never"

@Composable
fun UnfollowsScreen(
    modifier: Modifier = Modifier,
    viewModel: UnfollowsViewModel = hiltViewModel(),
    onExit: () -> Unit = {},
) {
    val state by viewModel.state.collectAsState()
    val context = LocalContext.current
    val whitelist = state.status?.automation?.whitelist.orEmpty().toSet()

    Screen(title = "Unfollowers", subtitle = "Who unfollowed @main_account, and who was kicked out of the group", modifier = modifier) {
        LazyColumn(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(14.dp)) {
            item { SummaryCard(state) }
            item { AutomationCard(state, onMode = viewModel::setMode, onResume = viewModel::resume) }
            item { TrackerCard(state, onRefresh = viewModel::refresh) }

            if (state.kickedRows.isNotEmpty() && state.filter == UnfollowFilter.ALL) {
                item { Text("Kicked out of the group", style = MaterialTheme.typography.titleSmall) }
                items(state.kickedRows, key = { "k-" + it.event.eventUuid }) { row ->
                    PersonRow(row, whitelist, onOpen = { open(context, row) }, onProtect = viewModel::protect)
                }
            }

            item {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(top = 6.dp)) {
                    UnfollowFilter.entries.forEach { f ->
                        FilterChip(selected = state.filter == f, onClick = { viewModel.setFilter(f) }, label = { Text(f.label) })
                    }
                }
            }
            val visible = state.visibleRows
            if (visible.isEmpty() && !state.refreshing) {
                item {
                    Text(
                        if (state.rows.isEmpty()) "No unfollows recorded yet. They appear here as X reports them, from the moment tracking started." else "Nothing in this view.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            for ((date, list) in visible.groupBy { day(it.event.receivedAtEpochMs) }) {
                item(key = "h-$date") { Text(date.format(DAY), style = MaterialTheme.typography.titleSmall, modifier = Modifier.padding(top = 6.dp)) }
                items(list, key = { it.event.eventUuid }) { row ->
                    PersonRow(row, whitelist, onOpen = { open(context, row) }, onProtect = viewModel::protect)
                }
            }
        }
        TextButton(onClick = onExit) { Text("Back") }
    }
}

private fun open(context: android.content.Context, row: UnfollowRow) =
    context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://x.com/i/user/${row.event.userId}")))

/** The two numbers you came for: how many unfollowed today, and how many of them were kicked out of the group. */
@Composable
private fun SummaryCard(state: UnfollowsUiState) {
    val auto = state.status?.automation
    SectionCard("Today") {
        Row(horizontalArrangement = Arrangement.spacedBy(24.dp)) {
            Column {
                Text("${state.unfollowedToday()}", style = MaterialTheme.typography.headlineMedium)
                Text("unfollowed", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Column {
                Text("${state.kickedToday()}", style = MaterialTheme.typography.headlineMedium, color = MaterialTheme.colorScheme.primary)
                Text("kicked from the group", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        if (auto != null && auto.mode == "dry_run") {
            Text(
                "Dry run: nobody is actually removed yet. \"Would be kicked\" shows what live mode would do.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun AutomationCard(state: UnfollowsUiState, onMode: (String) -> Unit, onResume: () -> Unit) {
    val auto: Automation? = state.status?.automation
    var confirmLive by remember { mutableStateOf(false) }
    if (auto == null) return
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        if (auto.pausedReason != null) {
            WarningCard("Group automation is paused: ${auto.pausedReason}.")
            Button(onClick = onResume, enabled = !state.acting) { Text("Resume after checking") }
        }
        SectionCard("Group automation") {
            val modes = listOf("off" to "Off", "dry_run" to "Dry run", "live" to "Live")
            SingleChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth()) {
                modes.forEachIndexed { index, (value, label) ->
                    SegmentedButton(
                        selected = auto.mode == value,
                        onClick = { if (value == "live") confirmLive = true else onMode(value) },
                        enabled = !state.acting,
                        shape = SegmentedButtonDefaults.itemShape(index, modes.size),
                    ) { Text(label) }
                }
            }
            Text(
                "When someone unfollows and is in the group, ${auto.delayMin} minutes later the app checks again. " +
                    "If they still do not follow you, they are removed as @rw_account. Admins, your accounts and anyone you protect are never removed.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(
                "Removed today ${auto.kickedToday} of ${auto.kickCap} allowed. Waiting for their re-check: ${auto.pending}. Last check ${stamp(auto.lastRunAt)}.",
                style = MaterialTheme.typography.bodySmall,
            )
        }
    }
    if (confirmLive) {
        AlertDialog(
            onDismissRequest = { confirmLive = false },
            title = { Text("Switch to live?") },
            text = {
                Text(
                    "From now on, anyone who unfollows you, is in the group and still does not follow after ${auto.delayMin} minutes " +
                        "will be removed from the group automatically, up to ${auto.kickCap} a day. It stops by itself at the first problem. " +
                        "People already marked \"would be kicked\" are not removed.",
                )
            },
            confirmButton = { TextButton(onClick = { confirmLive = false; onMode("live") }) { Text("Go live", color = MaterialTheme.colorScheme.error) } },
            dismissButton = { TextButton(onClick = { confirmLive = false }) { Text("Cancel") } },
        )
    }
}

@Composable
private fun TrackerCard(state: UnfollowsUiState, onRefresh: () -> Unit) {
    val s: TrackerStatus? = state.status
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        if (s?.pausedReason != null) {
            WarningCard("Tracking is paused, so nothing is being billed: ${s.pausedReason}. Unfollows are not being recorded until it is switched back on.")
        }
        state.error?.let { StatusRow(it, Tone.Bad) }
        state.warning?.let { StatusRow(it, Tone.Bad) }
        SectionCard("Tracker") {
            if (s != null) {
                StatusRow("Tracking since ${stamp(s.trackingSince)}", Tone.Neutral)
                StatusRow("Last unfollow reported: ${stamp(s.lastEventAt)}", Tone.Neutral)
                StatusRow("${s.todayCount} today (the tracker stops itself at ${s.dailyCap} a day)", Tone.Neutral)
                s.lastProblem?.let { StatusRow("Last problem: $it", Tone.Bad) }
            }
            Text(
                "Cost this month: about $" + "%.2f".format(state.monthCostUsd()) + " (1 cent per unfollow X reports, plus 1 cent for each re-check). Nothing before tracking started can be shown.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Button(onClick = onRefresh, enabled = !state.refreshing) { Text("Refresh") }
                if (state.refreshing) CircularProgressIndicator(modifier = Modifier.padding(4.dp))
                else state.lastResult?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
            }
        }
    }
}

@Composable
private fun toneColor(tone: GcTone): Color = when (tone) {
    GcTone.Done -> MaterialTheme.colorScheme.primary
    GcTone.Waiting -> MaterialTheme.colorScheme.tertiary
    GcTone.Warning -> MaterialTheme.colorScheme.error
    GcTone.Neutral -> MaterialTheme.colorScheme.onSurfaceVariant
}

@Composable
private fun PersonRow(row: UnfollowRow, whitelist: Set<String>, onOpen: () -> Unit, onProtect: (String) -> Unit) {
    val e = row.event
    val protectedNow = e.userId in whitelist && GcStatus.canProtect(e.gcStatus)
    val badge = if (protectedNow) {
        com.clipcut.domain.unfollows.GcBadge("Protected, will be kept", GcTone.Neutral)
    } else {
        GcStatus.badge(e.gcStatus, clock(e.gcDueAtEpochMs), clock(e.gcUpdatedAtEpochMs))
    }
    Row(modifier = Modifier.fillMaxWidth().clickable(onClick = onOpen).padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(modifier = Modifier.weight(1f)) {
            Text(e.username?.let { "@$it" } ?: "(account unavailable)", style = MaterialTheme.typography.bodyLarge)
            Text(
                listOfNotNull(e.name, e.followersCount?.let { "$it followers" }).joinToString("  -  ").ifEmpty { "id ${e.userId}" },
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(badge.label, style = MaterialTheme.typography.labelMedium, color = toneColor(badge.tone))
            e.gcDetail?.takeIf { e.gcStatus == "failed" || e.gcStatus == "unverified" }?.let {
                Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
            }
            if (row.timesUnfollowed > 1) {
                Text("Unfollowed ${row.timesUnfollowed} times (they followed again in between)", style = MaterialTheme.typography.bodySmall)
            }
        }
        Column(horizontalAlignment = Alignment.End) {
            Text(clock(e.receivedAtEpochMs).orEmpty(), style = MaterialTheme.typography.bodyMedium)
            if (GcStatus.canProtect(e.gcStatus) && !protectedNow) {
                TextButton(onClick = { onProtect(e.userId) }) { Text("Never kick") }
            }
        }
    }
}
