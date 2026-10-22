package com.clipcut.ui.scheduler

import android.app.TimePickerDialog
import android.content.Context
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.clipcut.domain.scheduler.ALL_DAYS
import com.clipcut.domain.scheduler.MAX_NIGHT_TIMES
import com.clipcut.domain.scheduler.NightRule
import com.clipcut.domain.scheduler.SchedStatus
import com.clipcut.domain.scheduler.dayName
import com.clipcut.domain.scheduler.nightRulesText
import com.clipcut.domain.scheduler.stateText
import com.clipcut.domain.scheduler.timeText
import com.clipcut.domain.scheduler.whenText
import com.clipcut.ui.components.Pill
import com.clipcut.ui.components.Screen
import com.clipcut.ui.components.SectionCard
import com.clipcut.ui.components.StatusRow
import com.clipcut.ui.components.Tone
import com.clipcut.ui.components.WarningCard
import java.time.ZoneId

/** Opens Android's 24-hour time picker on [initial] ("HH:MM") and reports the choice as "HH:MM". */
private fun pickTime(context: Context, initial: String, onPicked: (String) -> Unit) {
    val h = initial.substringBefore(':').toIntOrNull() ?: 10
    val m = initial.substringAfter(':', "0").toIntOrNull() ?: 0
    TimePickerDialog(context, { _, hour, minute -> onPicked(timeText(hour, minute)) }, h, m, true).show()
}

@Composable
fun SchedulerScreen(modifier: Modifier = Modifier, viewModel: SchedulerViewModel = hiltViewModel(), onExit: () -> Unit = {}) {
    val ui by viewModel.state.collectAsState()
    val zone = ZoneId.systemDefault()
    val lifecycleOwner = LocalLifecycleOwner.current
    // Follow the laptop only while this screen is in front.
    var visible by remember { mutableStateOf(lifecycleOwner.lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)) }
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, _ -> visible = lifecycleOwner.lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED) }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }
    LaunchedEffect(visible) { if (visible) viewModel.watch() }

    var morningDialog by remember { mutableStateOf(false) }
    var nightDialog by remember { mutableStateOf(false) }
    val status = ui.status
    val running = status?.activeRun != null

    Screen(title = "Recorder PC", subtitle = "Start recording, upload and shut down the laptop. Over Tailscale.", modifier = modifier, onBack = onExit) {
        Column(modifier = Modifier.weight(1f).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            if (!viewModel.isConfigured) {
                WarningCard("The laptop scheduler is not set up on this build. Add SCHEDULER_URL and SCHEDULER_TOKEN to local.properties and rebuild.")
            }
            ui.busy?.let {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp)
                    Text(it, style = MaterialTheme.typography.bodyMedium)
                }
            }
            ui.error?.let { StatusRow(it, Tone.Bad) }
            ui.notice?.let { StatusRow(it, Tone.Good) }
            if (ui.error != null || ui.notice != null) TextButton(onClick = viewModel::dismiss) { Text("Dismiss") }

            if (status == null) {
                if (ui.loading && viewModel.isConfigured) {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp)
                        Text("Reaching the laptop…", style = MaterialTheme.typography.bodyMedium)
                    }
                } else if (viewModel.isConfigured) {
                    OutlinedButton(onClick = viewModel::refresh, modifier = Modifier.fillMaxWidth()) { Text("Try again") }
                }
            } else {
                NowCard(status, zone)
                if (running) RunningCard(status, ui.busy != null, viewModel::cancelRun)
                SectionCard("Do it now") {
                    Button(onClick = { morningDialog = true }, enabled = !running && ui.busy == null && status.state == "idle", modifier = Modifier.fillMaxWidth()) {
                        Text("Start recording now")
                    }
                    OutlinedButton(onClick = { nightDialog = true }, enabled = !running && ui.busy == null, modifier = Modifier.fillMaxWidth()) {
                        Text("Night job now: stop, upload, shut down")
                    }
                    Text(
                        "Both do exactly what the schedule does. Only one job runs at a time.",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                ScheduleCard(status, zone, enabled = ui.busy == null, onEdit = viewModel::openEditor)
                RunsCard(status, zone)
            }
        }
    }

    if (morningDialog && status != null) {
        MorningDialog(
            streaming = status.streaming,
            onDismiss = { morningDialog = false },
            onConfirm = { morningDialog = false; viewModel.startMorning(requireStreaming = status.streaming) },
        )
    }
    if (nightDialog) {
        NightDialog(
            onDismiss = { nightDialog = false },
            onUploadOnly = { nightDialog = false; viewModel.startNight(shutdown = false) },
            onUploadAndShutDown = { nightDialog = false; viewModel.startNight(shutdown = true) },
        )
    }
    ui.draft?.let { ScheduleEditor(viewModel, it, ui.error, ui.busy != null) }
}

@Composable
private fun NowCard(s: SchedStatus, zone: ZoneId) {
    SectionCard("Laptop now") {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Pill(stateText(s), if (s.recording) MaterialTheme.colorScheme.secondary else MaterialTheme.colorScheme.primary)
            if (!s.obsReachable) Pill("OBS not reachable", MaterialTheme.colorScheme.error)
        }
        s.upload.lastOkAt?.let { Text("Last upload finished ${whenText(it, zone)}", style = MaterialTheme.typography.bodySmall) }
        s.upload.lastError?.let { StatusRow("Last upload problem: $it", Tone.Bad) }
        if (s.upload.filesPending > 0) Text("${s.upload.filesPending} file(s) waiting to be uploaded", style = MaterialTheme.typography.bodySmall)
    }
}

@Composable
private fun RunningCard(s: SchedStatus, busy: Boolean, onCancel: () -> Unit) {
    val run = s.activeRun ?: return
    SectionCard("Running now") {
        Text("${if (run.kind == "night") "Night job" else "Morning job"} (${if (run.source == "manual") "started from the phone" else "scheduled"})", style = MaterialTheme.typography.bodyMedium)
        Text(run.message, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        OutlinedButton(onClick = onCancel, enabled = !busy, modifier = Modifier.fillMaxWidth()) { Text("Cancel (shutdown countdown or before the upload)") }
        Text(
            "A recording or an upload that is already running is never killed.",
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun ScheduleCard(s: SchedStatus, zone: ZoneId, enabled: Boolean, onEdit: () -> Unit) {
    SectionCard("Schedule") {
        Text("Morning: start recording", style = MaterialTheme.typography.titleSmall)
        Text(
            if (s.morning.enabled) "Every day at ${s.morning.time}. Next: ${if (s.morning.taskEnabled) whenText(s.morning.nextRunAt, zone) else "off in Windows"}" else "Off",
            style = MaterialTheme.typography.bodyMedium,
        )
        Text("Night: stop, upload, shut down", style = MaterialTheme.typography.titleSmall)
        if (s.night.enabled) {
            Text(nightRulesText(s.night.rules), style = MaterialTheme.typography.bodyMedium)
            Text("Next: ${if (s.night.taskEnabled) whenText(s.night.nextRunAt, zone) else "off in Windows"}", style = MaterialTheme.typography.bodySmall)
        } else {
            Text("Off", style = MaterialTheme.typography.bodyMedium)
        }
        OutlinedButton(onClick = onEdit, enabled = enabled, modifier = Modifier.fillMaxWidth()) { Text("Change the times") }
    }
}

@Composable
private fun RunsCard(s: SchedStatus, zone: ZoneId) {
    if (s.lastRuns.isEmpty()) return
    SectionCard("Recent jobs") {
        s.lastRuns.take(6).forEach { r ->
            val tone = when (r.ok) { true -> Tone.Good; false -> Tone.Bad; null -> Tone.Neutral }
            StatusRow("${whenText(r.startedAt, zone)} · ${r.kind} (${r.source}): ${r.message}", tone)
        }
    }
}

@Composable
private fun MorningDialog(streaming: Boolean, onDismiss: () -> Unit, onConfirm: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Start recording now?") },
        text = {
            Text(
                if (streaming) "OBS on the laptop starts recording, the same as the morning job."
                else "OBS is not streaming right now. The morning job would normally skip it. Start recording anyway?",
            )
        },
        confirmButton = { TextButton(onClick = onConfirm) { Text(if (streaming) "Start recording" else "Record anyway") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Never mind") } },
    )
}

@Composable
private fun NightDialog(onDismiss: () -> Unit, onUploadOnly: () -> Unit, onUploadAndShutDown: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Run the night job now?") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text("This stops the stream and the recording, then uploads the file to R2.")
                Text("If you also shut down: 60 seconds after a verified upload the laptop closes OBS and Chrome and powers off. Nothing is shut down if the upload fails. This phone cannot turn it back on.")
                OutlinedButton(onClick = onUploadOnly, modifier = Modifier.fillMaxWidth()) { Text("Stop and upload only (laptop stays on)") }
                Button(onClick = onUploadAndShutDown, modifier = Modifier.fillMaxWidth()) { Text("Stop, upload and shut down") }
            }
        },
        confirmButton = {},
        dismissButton = { TextButton(onClick = onDismiss) { Text("Never mind") } },
    )
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ScheduleEditor(vm: SchedulerViewModel, d: ScheduleDraft, error: String?, busy: Boolean) {
    val context = LocalContext.current
    val problem = vm.draftProblem(d)
    AlertDialog(
        onDismissRequest = vm::closeEditor,
        title = { Text("Change the times") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.verticalScroll(rememberScrollState())) {
                error?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("Morning: start recording", style = MaterialTheme.typography.titleSmall, modifier = Modifier.weight(1f))
                    Switch(checked = d.morningEnabled, onCheckedChange = vm::setMorningEnabled)
                }
                OutlinedButton(
                    onClick = { pickTime(context, d.morningTime, vm::setMorningTime) },
                    enabled = d.morningEnabled,
                    modifier = Modifier.fillMaxWidth(),
                ) { Text("Every day at ${d.morningTime}") }

                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("Night: stop, upload, shut down", style = MaterialTheme.typography.titleSmall, modifier = Modifier.weight(1f))
                    Switch(checked = d.nightEnabled, onCheckedChange = vm::setNightEnabled)
                }
                if (d.nightEnabled) {
                    d.nightRules.forEachIndexed { i, rule -> RuleEditor(vm, i, rule, context) }
                    OutlinedButton(onClick = vm::addRule, modifier = Modifier.fillMaxWidth()) { Text("Add another group of days") }
                    Text(
                        "A day name is the calendar day the job runs on: Monday 03:00 is the end of Sunday night. At most $MAX_NIGHT_TIMES different times in all.",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                problem?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
                Text(
                    "Saved on the laptop and applied straight away. A time that has already passed today waits for the next matching day.",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        },
        confirmButton = { TextButton(onClick = vm::saveSchedule, enabled = !busy && problem == null) { Text("Save") } },
        dismissButton = { TextButton(onClick = vm::closeEditor, enabled = !busy) { Text("Cancel") } },
    )
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun RuleEditor(vm: SchedulerViewModel, index: Int, rule: NightRule, context: Context) {
    SectionCard("Group ${index + 1}") {
        FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            ALL_DAYS.forEach { day ->
                FilterChip(selected = day in rule.days, onClick = { vm.toggleDay(index, day) }, label = { Text(dayName(day).take(3)) })
            }
        }
        FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            rule.times.forEach { t ->
                FilterChip(selected = true, onClick = { vm.removeNightTime(index, t) }, label = { Text("$t  ✕") })
            }
            if (rule.times.size < MAX_NIGHT_TIMES) {
                OutlinedButton(onClick = { pickTime(context, rule.times.lastOrNull() ?: "01:00") { vm.addNightTime(index, it) } }) { Text("+ Time") }
            }
        }
        TextButton(onClick = { vm.removeRule(index) }) { Text("Remove this group") }
    }
}
