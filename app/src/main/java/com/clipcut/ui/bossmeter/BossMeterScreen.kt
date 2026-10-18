package com.clipcut.ui.bossmeter

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
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
import com.clipcut.domain.bossmeter.BossMeterResult
import com.clipcut.domain.publish.BossStage
import androidx.compose.material3.SegmentedButton
import com.clipcut.domain.share.bossMeterShare
import com.clipcut.domain.taglines.dateTimeText
import com.clipcut.ui.components.Screen
import com.clipcut.ui.components.SectionCard
import com.clipcut.ui.components.StatusRow
import com.clipcut.ui.components.WarningCard
import com.clipcut.ui.components.Tone
import com.clipcut.ui.publish.PublishBanner
import com.clipcut.ui.share.ShareToX
import com.clipcut.ui.history.ContestantDialog
import com.clipcut.ui.history.ContestantPill
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import java.time.ZoneId

@Composable
fun BossMeterScreen(
    modifier: Modifier = Modifier,
    viewModel: BossMeterViewModel = hiltViewModel(),
    onExit: () -> Unit = {},
) {
    val state by viewModel.state.collectAsState()
    val publish by viewModel.publishStatus.collectAsState()
    val context = LocalContext.current
    val zone = ZoneId.systemDefault()

    Screen(title = "Boss Meter", subtitle = "Hashtag votes in a post's replies. Reads only.", modifier = modifier) {
        LazyColumn(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(14.dp)) {
            item {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    state.error?.let { StatusRow(it, Tone.Bad) }
                    state.message?.let { StatusRow(it, Tone.Good) }
                    if (state.error != null || state.message != null) TextButton(onClick = viewModel::dismiss) { Text("Dismiss") }
                }
            }
            item {
                SectionCard("What to count") {
                    OutlinedTextField(
                        state.link, viewModel::setLink, label = { Text("Post link") }, singleLine = true, modifier = Modifier.fillMaxWidth(),
                        supportingText = {
                            Text(
                                if (state.link.isBlank()) "The post must be from the last 7 days." else state.postId?.let { "Post $it" } ?: "Not a post link",
                                color = if (state.link.isNotBlank() && state.postId == null) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        },
                    )
                    OutlinedTextField(
                        state.hashtagText, viewModel::setHashtags, label = { Text("Hashtags") }, minLines = 3, modifier = Modifier.fillMaxWidth(),
                        supportingText = { Text("Separate with spaces, commas or new lines. ${state.hashtags.size} hashtag(s): ${state.hashtags.joinToString(" ")}") },
                    )
                    if (state.lookalikes.isNotEmpty()) {
                        WarningCard(
                            "Check the spelling: " + state.lookalikes.entries.joinToString("; ") { "${it.key} may be ${it.value}" } +
                                ". A lowercase L (l) looks like a capital I; X counts them as different hashtags, so the votes would be far too low.",
                        )
                        OutlinedButton(onClick = viewModel::fixLookalikes, modifier = Modifier.fillMaxWidth()) { Text("Fix the spelling") }
                    }
                    RoundStage(state, viewModel)
                    if (state.running) {
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                            CircularProgressIndicator(modifier = Modifier.size(22.dp), strokeWidth = 2.dp)
                            Text(state.progress ?: "Asking X…", style = MaterialTheme.typography.bodyMedium)
                        }
                    } else {
                        Button(onClick = viewModel::askToCount, enabled = state.canCount, modifier = Modifier.fillMaxWidth()) {
                            Text("Count (${state.calls} calls, about $${"%.3f".format(state.costUsd)})")
                        }
                    }
                }
            }
            val r = state.result
            if (r != null) {
                item {
                    Column {
                        Text("From X's official API, counted ${dateTimeText(r.fetchedAtMs, zone)}", style = MaterialTheme.typography.labelLarge)
                        Text("Post ${r.conversationId}, replies from the last 7 days.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
                item { PublishBanner(publish, onRetry = viewModel::retryPublish) }
                item { Ranking(r, state.contestants, viewModel) }
                item { Breakdown(r) }
                item {
                    OutlinedButton(
                        onClick = { ShareToX.share(context, bossMeterShare(r, dateTimeText(r.fetchedAtMs, zone))).let { o -> if (o.error != null) viewModel.showError(o.error) else viewModel.showInfo(o.message) } },
                        modifier = Modifier.fillMaxWidth(),
                    ) { Text("Share on X") }
                }
                item {
                    Text(
                        "Opens X with the image and a caption filled in; you check it and post it yourself.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
        TextButton(onClick = onExit) { Text("Back") }
    }

    val payer by viewModel.payer.collectAsState()
    if (state.confirming) {
        AlertDialog(
            onDismissRequest = viewModel::cancel,
            title = { Text("Count from X?") },
            text = { Text("${state.calls} calls to X (the whole thread, then each hashtag), billed at about $${"%.3f".format(state.costUsd)}. Nothing is posted.\n\n$payer") },
            confirmButton = { Button(onClick = viewModel::confirmCount) { Text("Count") } },
            dismissButton = { TextButton(onClick = viewModel::cancel) { Text("Cancel") } },
        )
    }
}

@Composable
private fun Ranking(r: BossMeterResult, contestants: Map<String, String>, vm: BossMeterViewModel) {
    var linking by remember { mutableStateOf<String?>(null) }
    linking?.let { tag ->
        ContestantDialog(tag, contestants[tag], vm.knownContestants(), onSave = { vm.setContestant(tag, it); linking = null }, onCancel = { linking = null })
    }
    SectionCard("Boss Meter (share of hashtag votes)") {
        val top = r.ranked.firstOrNull()?.count?.coerceAtLeast(1L) ?: 1L
        r.ranked.forEach { t ->
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    Text(t.tag, style = MaterialTheme.typography.titleSmall, modifier = Modifier.weight(1f))
                    Text("${"%,d".format(t.count)} (${"%.1f".format(r.percent(t))}%)", style = MaterialTheme.typography.bodyMedium)
                }
                LinearProgressIndicator(progress = { t.count.toFloat() / top }, modifier = Modifier.fillMaxWidth())
                ContestantPill(contestants[t.tag], onClick = { linking = t.tag })
            }
        }
    }
}

@Composable
private fun Breakdown(r: BossMeterResult) {
    SectionCard("Thread breakdown") {
        Text("Total replies in thread: ${"%,d".format(r.totalThread)}")
        Text("Tracked hashtag votes: ${"%,d".format(r.trackedSum)}")
        Text("Uncounted: ${"%,d".format(r.uncounted)}" + (r.uncountedPercentOfThread?.let { " (${"%.1f".format(it)}% of thread)" } ?: ""))
        Text(
            "Uncounted = no-hashtag replies + untracked variants + deleted or filtered. Not verifiable as spam without reading each tweet.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/**
 * Which post of the round is being counted, for the website: the 5 contestants' post or the finalists'. "Final result"
 * marks the finalists' run as the end of the round (the website shows the winner and the margin); it switches itself off
 * once that run is on the website.
 */
@OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
@Composable
private fun RoundStage(state: BossMeterUiState, vm: BossMeterViewModel) {
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text("ROUND STAGE", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        androidx.compose.material3.SingleChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth()) {
            BossStage.entries.forEachIndexed { i, st ->
                SegmentedButton(
                    selected = state.stage == st,
                    onClick = { vm.setStage(st) },
                    enabled = !state.running,
                    shape = androidx.compose.material3.SegmentedButtonDefaults.itemShape(i, BossStage.entries.size),
                    icon = {},
                ) { Text(st.label) }
            }
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(modifier = Modifier.weight(1f)) {
                Text("Final result", style = MaterialTheme.typography.titleSmall)
                Text(
                    if (state.stage == BossStage.FINALISTS) "The website shows the highest count as the winner, with the margin." else "Only for the finalists' post.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            androidx.compose.material3.Switch(
                checked = state.final,
                onCheckedChange = vm::setFinal,
                enabled = state.stage == BossStage.FINALISTS && !state.running,
            )
        }
    }
}
