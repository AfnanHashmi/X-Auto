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
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
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

    Screen(title = "Unfollowers", subtitle = "People who unfollowed @main_account", modifier = modifier) {
        LazyColumn(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(14.dp)) {
            item {
                StatusCard(state, onRefresh = viewModel::refresh)
            }
            val grouped = state.rows.groupBy { day(it.event.receivedAtEpochMs) }
            if (state.rows.isEmpty() && !state.refreshing) {
                item {
                    Text(
                        "No unfollows recorded yet. They appear here as X reports them, from the moment tracking started.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            for ((date, list) in grouped) {
                item(key = "h-$date") { Text(date.format(DAY), style = MaterialTheme.typography.titleSmall, modifier = Modifier.padding(top = 6.dp)) }
                items(list, key = { it.event.eventUuid }) { row ->
                    PersonRow(row) {
                        context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://x.com/i/user/${row.event.userId}")))
                    }
                }
            }
        }
        TextButton(onClick = onExit) { Text("Back") }
    }
}

@Composable
private fun StatusCard(state: UnfollowsUiState, onRefresh: () -> Unit) {
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
                "Cost this month: about $" + "%.2f".format(state.monthCostUsd()) + " (1 cent per unfollow X reports). Nothing before tracking started can be shown.",
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
private fun PersonRow(row: UnfollowRow, onOpen: () -> Unit) {
    val e = row.event
    Row(modifier = Modifier.fillMaxWidth().clickable(onClick = onOpen).padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(modifier = Modifier.weight(1f)) {
            Text(e.username?.let { "@$it" } ?: "(account unavailable)", style = MaterialTheme.typography.bodyLarge)
            Text(
                listOfNotNull(e.name, e.followersCount?.let { "$it followers" }).joinToString("  -  ").ifEmpty { "id ${e.userId}" },
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            if (row.timesUnfollowed > 1) {
                Text("Unfollowed ${row.timesUnfollowed} times (they followed again in between)", style = MaterialTheme.typography.bodySmall)
            }
        }
        Text(
            Instant.ofEpochMilli(e.receivedAtEpochMs).atZone(ZoneId.systemDefault()).format(CLOCK),
            style = MaterialTheme.typography.bodyMedium,
        )
    }
}
