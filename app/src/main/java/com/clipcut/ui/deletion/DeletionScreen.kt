package com.clipcut.ui.deletion

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.DateRangePicker
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberDateRangePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import com.clipcut.domain.deletion.DeletionPreset
import com.clipcut.ui.components.Screen
import com.clipcut.ui.components.SectionCard
import com.clipcut.ui.components.WarningCard
import com.clipcut.domain.deletion.DeletionService
import com.clipcut.domain.deletion.RunResult
import com.clipcut.domain.deletion.RunStop
import java.time.Instant
import java.time.ZoneId
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter

private val TIME_FORMAT: DateTimeFormatter = DateTimeFormatter.ofPattern("d MMM HH:mm")

@Composable
fun DeletionScreen(
    modifier: Modifier = Modifier,
    viewModel: DeletionViewModel = hiltViewModel(),
    onOpenHistory: () -> Unit = {},
    onExit: () -> Unit = {},
) {
    val state by viewModel.uiState.collectAsState()

    // Back during a run means "stop", not "leave": leaving would kill the Activity mid-run.
    BackHandler(enabled = state.phase == DeletionPhase.DELETING) { viewModel.stop() }

    // The run only lives while this screen does; keep the display awake so it is not interrupted.
    val view = LocalView.current
    DisposableEffect(state.phase) {
        view.keepScreenOn = state.phase == DeletionPhase.DELETING
        onDispose { view.keepScreenOn = false }
    }

    Screen(
        title = "Trigger Deletion",
        subtitle = "Delete BB LOGS posts from @${DeletionService.EXPECTED_HANDLE}",
        modifier = modifier,
    ) {
        when (state.phase) {
            DeletionPhase.PICK -> PickStep(state, viewModel, onOpenHistory, onExit)
            DeletionPhase.FINDING -> FindingStep(state, viewModel)
            DeletionPhase.REVIEW -> ReviewStep(state, viewModel)
            DeletionPhase.DELETING -> DeletingStep(state, viewModel)
            DeletionPhase.DONE -> DoneStep(state, viewModel, onOpenHistory, onExit)
        }
    }

    if (state.confirmVisible) ConfirmDialog(state, viewModel)
}

@OptIn(ExperimentalLayoutApi::class, ExperimentalMaterial3Api::class)
@Composable
private fun PickStep(state: DeletionUiState, vm: DeletionViewModel, onOpenHistory: () -> Unit, onExit: () -> Unit) {
    var pickerOpen by remember { mutableStateOf(false) }
    Column(
        modifier = Modifier.verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        WarningCard(
            "Only @${DeletionService.EXPECTED_HANDLE}'s own posts that read exactly \"BB LOGS - NNN\" can be deleted here. " +
                "Your main account is never touched. Nothing is deleted until you review the list and confirm.",
        )

        SectionCard("Time period") {
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                DeletionPreset.entries.forEach { preset ->
                    FilterChip(
                        selected = state.preset == preset,
                        onClick = {
                            vm.selectPreset(preset)
                            if (preset == DeletionPreset.CUSTOM) pickerOpen = true
                        },
                        label = { Text(preset.label) },
                    )
                }
            }
            if (state.preset == DeletionPreset.CUSTOM) {
                OutlinedButton(onClick = { pickerOpen = true }, modifier = Modifier.fillMaxWidth()) {
                    val from = state.customFrom
                    val to = state.customTo
                    Text(if (from != null && to != null) "$from  to  $to  (whole days)" else "Choose dates")
                }
            }
        }

        state.error?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }

        Button(onClick = vm::find, enabled = state.canFind, modifier = Modifier.fillMaxWidth()) {
            Text("Find posts (deletes nothing)")
        }
        OutlinedButton(onClick = onOpenHistory, modifier = Modifier.fillMaxWidth()) { Text("Deletion history") }
        TextButton(onClick = onExit) { Text("Back") }
    }

    if (pickerOpen) {
        val pickerState = rememberDateRangePickerState()
        DatePickerDialog(
            onDismissRequest = { pickerOpen = false },
            confirmButton = {
                TextButton(
                    onClick = {
                        val a = pickerState.selectedStartDateMillis
                        val b = pickerState.selectedEndDateMillis ?: a
                        if (a != null && b != null) {
                            // The picker reports UTC midnight of the tapped day; that calendar day is what was meant.
                            vm.setCustomRange(
                                Instant.ofEpochMilli(a).atZone(ZoneOffset.UTC).toLocalDate(),
                                Instant.ofEpochMilli(b).atZone(ZoneOffset.UTC).toLocalDate(),
                            )
                            pickerOpen = false
                        }
                    },
                    enabled = pickerState.selectedStartDateMillis != null,
                ) { Text("OK") }
            },
            dismissButton = { TextButton(onClick = { pickerOpen = false }) { Text("Cancel") } },
        ) {
            DateRangePicker(state = pickerState, modifier = Modifier.weight(1f, fill = false))
        }
    }
}

@Composable
private fun FindingStep(state: DeletionUiState, vm: DeletionViewModel) {
    Column(verticalArrangement = Arrangement.spacedBy(12.dp), horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.fillMaxWidth()) {
        CircularProgressIndicator()
        Text("Scanning @${DeletionService.EXPECTED_HANDLE} - page ${state.pages}, ${state.scanned} posts checked")
        Text("Nothing is deleted in this step.", style = MaterialTheme.typography.titleSmall)
        TextButton(onClick = vm::cancelFind) { Text("Stop") }
    }
}

@Composable
private fun ColumnScope.ReviewStep(state: DeletionUiState, vm: DeletionViewModel) {
    val zone = ZoneId.systemDefault()
    val settled = state.rows.count { it.status != RowStatus.PENDING }
    Text(
        if (settled == 0) {
            "${state.rows.size} BB LOGS posts on @${state.handle}"
        } else {
            "${state.rows.size - settled} left of ${state.rows.size} on @${state.handle}"
        },
        style = MaterialTheme.typography.titleMedium,
    )
    Text(state.windowLabel, style = MaterialTheme.typography.bodySmall)
    if (state.truncated) {
        Text(
            "Scan stopped at its safety limit (${DeletionService.MAX_PAGES} pages); older posts in this window were not checked.",
            color = MaterialTheme.colorScheme.error,
            style = MaterialTheme.typography.bodySmall,
        )
    }
    if (state.rows.isEmpty()) {
        Text("No posts matched. Nothing to delete.")
        Text(
            "X does not return posts from the last few seconds, so a clip you have only just posted " +
                "will not show up straight away.",
            style = MaterialTheme.typography.bodySmall,
        )
        OutlinedButton(onClick = vm::reset) { Text("Change time period") }
        return
    }
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        TextButton(onClick = { vm.selectAll(true) }) { Text("Select all") }
        TextButton(onClick = { vm.selectAll(false) }) { Text("Select none") }
    }
    LazyColumn(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        items(state.rows, key = { it.candidate.id }) { row ->
            val pending = row.status == RowStatus.PENDING
            Row(
                modifier = Modifier.fillMaxWidth().clickable(enabled = pending) { vm.toggle(row.candidate.id) },
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Checkbox(
                    checked = row.selected && pending,
                    onCheckedChange = { vm.toggle(row.candidate.id) },
                    enabled = pending,
                )
                Column {
                    Text("BB LOGS - %03d".format(row.candidate.bbNumber), style = MaterialTheme.typography.bodyLarge)
                    Text(
                        "posted ${TIME_FORMAT.format(row.candidate.postedAt.atZone(zone))}" +
                            if (pending) "  -  id ${row.candidate.id}" else "  -  ${statusLabel(row.status)}",
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
            }
        }
    }
    Text(
        "Any post on another account that embeds one of these will lose its video.",
        style = MaterialTheme.typography.bodySmall,
    )
    Button(
        onClick = vm::requestConfirm,
        enabled = state.selectedCount > 0,
        colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error),
        modifier = Modifier.fillMaxWidth(),
    ) {
        val n = state.selectedCount
        val cap = DeletionService.MAX_PER_RUN
        Text(if (n > cap) "Review: delete first $cap of $n..." else "Review: delete $n selected...")
    }
    OutlinedButton(onClick = vm::reset, modifier = Modifier.fillMaxWidth()) { Text("Back") }
}

@Composable
private fun ColumnScope.DeletingStep(state: DeletionUiState, vm: DeletionViewModel) {
    val total = state.runTotal
    Text("Deleting ${state.runSettled} of $total", style = MaterialTheme.typography.titleMedium)
    LinearProgressIndicator(
        progress = { if (total == 0) 0f else state.runSettled.toFloat() / total },
        modifier = Modifier.fillMaxWidth(),
    )
    Text("One post at a time with a pause between each. Keep this screen open until it finishes.", style = MaterialTheme.typography.bodySmall)
    LazyColumn(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
        items(state.rows.filter { it.selected }, key = { it.candidate.id }) { row ->
            Text(
                "BB LOGS - %03d  -  %s".format(row.candidate.bbNumber, statusLabel(row.status)),
                style = MaterialTheme.typography.bodyMedium,
            )
        }
    }
    OutlinedButton(onClick = vm::stop, modifier = Modifier.fillMaxWidth()) { Text("Stop after the current post") }
}

@Composable
private fun DoneStep(state: DeletionUiState, vm: DeletionViewModel, onOpenHistory: () -> Unit, onExit: () -> Unit) {
    val r = state.result
    // Ticks so a "continue after <time>" button un-greys itself without the user leaving the screen.
    val nowSec by produceState(System.currentTimeMillis() / 1000) {
        while (true) {
            kotlinx.coroutines.delay(1_000)
            value = System.currentTimeMillis() / 1000
        }
    }
    Column(verticalArrangement = Arrangement.spacedBy(12.dp), modifier = Modifier.verticalScroll(rememberScrollState())) {
        if (r != null) {
            Text(headline(r), style = MaterialTheme.typography.titleMedium)
            Text("Deleted ${r.deleted}  -  already gone ${r.alreadyGone}  -  not attempted ${r.notAttempted}")
            detail(r.stop)?.let {
                Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodyMedium)
            }
        }
        val remaining = state.selectedCount
        if (remaining > 0) {
            val waitUntil = state.resumeAllowedAtEpochSec
            val ready = waitUntil == null || nowSec >= waitUntil
            Button(onClick = vm::continueRemaining, enabled = ready, modifier = Modifier.fillMaxWidth()) {
                Text(
                    if (ready) {
                        "Continue with the remaining $remaining"
                    } else {
                        "Continue in ${waitUntil - nowSec}s (X asked us to wait)"
                    },
                )
            }
            Text(
                "Continuing reuses the list you already reviewed - X is not scanned again - and still asks you to confirm.",
                style = MaterialTheme.typography.bodySmall,
            )
        }
        Text("The BB LOGS counter is unchanged: numbers are never reused.", style = MaterialTheme.typography.bodySmall)
        OutlinedButton(onClick = onOpenHistory, modifier = Modifier.fillMaxWidth()) { Text("View history") }
        OutlinedButton(onClick = vm::reset, modifier = Modifier.fillMaxWidth()) { Text("Start over") }
        TextButton(onClick = onExit) { Text("Done") }
    }
}

private fun statusLabel(status: RowStatus): String = when (status) {
    RowStatus.PENDING -> "waiting"
    RowStatus.DELETED -> "deleted"
    RowStatus.ALREADY_GONE -> "already gone"
}

private fun headline(r: RunResult): String = when (r.stop) {
    RunStop.Completed -> "Finished"
    RunStop.UserStopped -> "Stopped"
    is RunStop.RateLimited -> "Paused: X rate limit reached"
    is RunStop.HardError -> "Stopped on an error"
    is RunStop.UnknownOutcome -> "Stopped: last post unconfirmed"
    is RunStop.CapReached -> "Stopped at the safety cap"
}

private fun detail(stop: RunStop): String? = when (stop) {
    RunStop.Completed, RunStop.UserStopped -> null
    is RunStop.RateLimited -> {
        val at = stop.resetEpochSec?.let { TIME_FORMAT.format(Instant.ofEpochSecond(it).atZone(ZoneId.systemDefault())) }
        if (at != null) "X asked us to wait. Try again after $at. Nothing was retried." else "X asked us to wait. Try again later. Nothing was retried."
    }
    is RunStop.HardError -> "${stop.message}\nNothing further was attempted."
    is RunStop.UnknownOutcome ->
        "No reply came back for post ${stop.id}, so it may or may not be deleted (${stop.message}). " +
            "Check it on X. It is marked in History and will not be offered again."
    is RunStop.CapReached ->
        if (stop.perRun) "At most ${stop.limit} posts are deleted per run. Run it again to continue."
        else "At most ${stop.limit} posts are deleted per 24 hours. Try again later."
}

@Composable
private fun ConfirmDialog(state: DeletionUiState, vm: DeletionViewModel) {
    val n = minOf(state.selectedCount, DeletionService.MAX_PER_RUN)
    // Guard against the double-tap that opened this dialog carrying straight through to "Delete".
    var armed by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) {
        kotlinx.coroutines.delay(2_000)
        armed = true
    }
    AlertDialog(
        onDismissRequest = vm::dismissConfirm,
        title = { Text("Delete $n posts from @${state.handle}?") },
        text = {
            Text(
                "This permanently deletes $n posts posted between ${state.windowLabel}. Every one reads exactly " +
                    "\"BB LOGS - NNN\". Your main account is not touched. This cannot be undone.\n\n" +
                    "They are deleted one at a time with a pause between each, and you can stop at any point.",
            )
        },
        confirmButton = {
            TextButton(onClick = vm::confirmDelete, enabled = armed) {
                Text(if (armed) "Delete $n posts" else "Delete $n posts (wait)", color = MaterialTheme.colorScheme.error)
            }
        },
        dismissButton = { TextButton(onClick = vm::dismissConfirm) { Text("Cancel") } },
    )
}
