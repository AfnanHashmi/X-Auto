package com.clipcut.ui.broadcast

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
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
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.text.KeyboardOptions
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import com.clipcut.data.db.BroadcastLogEntry
import com.clipcut.data.db.BroadcastTarget
import com.clipcut.domain.broadcast.BroadcastSettings
import com.clipcut.ui.components.EmptyState
import com.clipcut.ui.components.Screen
import com.clipcut.ui.components.SectionCard
import com.clipcut.ui.components.StatusRow
import com.clipcut.ui.components.Tone
import com.clipcut.ui.components.WarningCard
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

private val WHEN: DateTimeFormatter = DateTimeFormatter.ofPattern("d MMM HH:mm")

@Composable
fun BroadcastScreen(
    modifier: Modifier = Modifier,
    viewModel: BroadcastViewModel = hiltViewModel(),
    onBack: () -> Unit = {},
) {
    val state by viewModel.uiState.collectAsState()
    val targets by viewModel.targets.collectAsState()
    val log by viewModel.log.collectAsState()

    Screen(
        title = "Daily Link",
        subtitle = "Sends the current viewing link to your group chats every morning.",
        modifier = modifier.imePadding(),
        onBack = onBack,
    ) {
        Column(
            modifier = Modifier.verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            SectionCard {
                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            if (state.enabled) "Scheduled" else "Off",
                            style = MaterialTheme.typography.titleMedium,
                        )
                        Text(
                            if (state.enabled) {
                                "Next: ${state.nextRun}"
                            } else {
                                "Nothing is sent while this is off."
                            },
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    Switch(checked = state.enabled, onCheckedChange = viewModel::setEnabled)
                }
                if (!state.exactAlarms) {
                    Text(
                        "Exact alarms are not permitted for this app, so the send may run a few minutes late.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                    )
                }
                StatusRow(
                    if (state.telegramConfigured) "Telegram bot token is set" else "No Telegram bot token in local.properties",
                    if (state.telegramConfigured) Tone.Good else Tone.Bad,
                )
            }

            SectionCard("Time (India)") {
                TimePicker(state.hour, state.minute, viewModel::setTime)
            }

            SectionCard("Message") {
                OutlinedTextField(
                    value = state.template,
                    onValueChange = viewModel::setTemplate,
                    modifier = Modifier.fillMaxWidth(),
                    minLines = 3,
                    supportingText = { Text("${BroadcastSettings.LINK} is replaced with the fresh link.") },
                )
            }

            SectionCard("Groups") {
                if (targets.isEmpty()) {
                    EmptyState("No groups yet. Add one below.")
                }
                targets.forEach { target ->
                    TargetRow(target, viewModel)
                }
                AddTarget(viewModel)
            }

            SectionCard("Check it works") {
                WarningCard(
                    "Send now delivers a real message to every group that is switched on. " +
                        "Test setup only fetches the link and checks the bot, and sends nothing.",
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                    OutlinedButton(onClick = viewModel::testSetup, enabled = !state.busy, modifier = Modifier.weight(1f)) {
                        Text("Test setup")
                    }
                    Button(onClick = viewModel::sendNow, enabled = !state.busy && targets.any { it.enabled }, modifier = Modifier.weight(1f)) {
                        Text("Send now")
                    }
                }
                if (state.busy) {
                    Box(modifier = Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                        CircularProgressIndicator()
                    }
                }
                state.message?.let {
                    Text(it, style = MaterialTheme.typography.bodySmall)
                    TextButton(onClick = viewModel::clearMessage) { Text("Dismiss") }
                }
                state.error?.let {
                    Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
                    TextButton(onClick = viewModel::clearMessage) { Text("Dismiss") }
                }
            }

            SectionCard("Recent sends") {
                if (log.isEmpty()) EmptyState("Nothing sent yet.")
                log.take(25).forEach { entry -> LogRow(entry) }
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun TimePicker(hour: Int, minute: Int, onSet: (Int, Int) -> Unit) {
    var h by remember(hour) { mutableStateOf(hour.toString()) }
    var m by remember(minute) { mutableStateOf("%02d".format(minute)) }
    Row(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
        OutlinedTextField(
            value = h,
            onValueChange = { v -> h = v.filter { it.isDigit() }.take(2) },
            label = { Text("Hour") },
            singleLine = true,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
            modifier = Modifier.weight(1f),
        )
        OutlinedTextField(
            value = m,
            onValueChange = { v -> m = v.filter { it.isDigit() }.take(2) },
            label = { Text("Minute") },
            singleLine = true,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
            modifier = Modifier.weight(1f),
        )
        TextButton(
            onClick = { onSet(h.toIntOrNull() ?: 7, m.toIntOrNull() ?: 0) },
            enabled = h.toIntOrNull() in 0..23 && m.toIntOrNull() in 0..59,
        ) { Text("Set") }
    }
}

@Composable
private fun TargetRow(target: BroadcastTarget, vm: BroadcastViewModel) {
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.weight(1f)) {
            Text(target.label, style = MaterialTheme.typography.bodyLarge)
            Text(
                "${if (target.platform == BroadcastTarget.PLATFORM_X) "X" else "Telegram"}  ${target.chatId}",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                fontFamily = FontFamily.Monospace,
            )
        }
        Switch(checked = target.enabled, onCheckedChange = { vm.toggleTarget(target) })
        TextButton(onClick = { vm.removeTarget(target) }) { Text("Remove") }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun AddTarget(vm: BroadcastViewModel) {
    var platform by remember { mutableStateOf(BroadcastTarget.PLATFORM_TELEGRAM) }
    var chatId by remember { mutableStateOf("") }
    var label by remember { mutableStateOf("") }
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            FilterChip(
                selected = platform == BroadcastTarget.PLATFORM_TELEGRAM,
                onClick = { platform = BroadcastTarget.PLATFORM_TELEGRAM },
                label = { Text("Telegram") },
            )
            FilterChip(
                selected = platform == BroadcastTarget.PLATFORM_X,
                onClick = { platform = BroadcastTarget.PLATFORM_X },
                label = { Text("X group DM") },
            )
        }
        OutlinedTextField(
            value = label,
            onValueChange = { label = it },
            label = { Text("Name") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )
        OutlinedTextField(
            value = chatId,
            onValueChange = { chatId = it },
            label = {
                Text(if (platform == BroadcastTarget.PLATFORM_X) "DM conversation id" else "Chat id (e.g. -1001234567890)")
            },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )
        Button(
            onClick = {
                vm.addTarget(platform, chatId, label)
                chatId = ""
                label = ""
            },
            enabled = chatId.isNotBlank(),
        ) { Text("Add group") }
    }
}

@Composable
private fun LogRow(entry: BroadcastLogEntry) {
    val ok = entry.outcome == BroadcastLogEntry.SENT
    Column(modifier = Modifier.padding(vertical = 2.dp)) {
        StatusRow(
            "${entry.label}  -  ${if (ok) "sent" else "failed"}${if (entry.manual) "  (manual)" else ""}",
            if (ok) Tone.Good else Tone.Bad,
        )
        Text(
            "${WHEN.format(Instant.ofEpochMilli(entry.runAtEpochMs).atZone(ZoneId.systemDefault()))}  -  ${entry.detail}",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(start = 18.dp),
        )
    }
}
