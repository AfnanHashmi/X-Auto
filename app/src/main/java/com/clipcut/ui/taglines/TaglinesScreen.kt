package com.clipcut.ui.taglines

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import com.clipcut.domain.share.PositionInput
import com.clipcut.ui.history.ContestantDialog
import com.clipcut.ui.history.ContestantPill
import com.clipcut.domain.share.ShareStage
import androidx.compose.material3.Switch
import com.clipcut.domain.share.parsePosition
import com.clipcut.domain.share.taglineShareCard
import com.clipcut.domain.share.taglineStat
import com.clipcut.domain.taglines.COUNTS_LIMITS_NOTE
import com.clipcut.domain.taglines.CountReport
import com.clipcut.domain.taglines.DEEP_LOOK_DAILY_CAP_USD
import com.clipcut.domain.taglines.DEEP_LOOK_ESTIMATE_USD
import com.clipcut.domain.taglines.DeepLook
import com.clipcut.domain.taglines.DeepLookSize
import com.clipcut.domain.taglines.TaglineCount
import com.clipcut.domain.taglines.clockText
import com.clipcut.domain.taglines.dateTimeText
import com.clipcut.domain.taglines.insightLines
import com.clipcut.domain.taglines.zoneLabel
import com.clipcut.ui.components.Screen
import com.clipcut.ui.components.SectionCard
import com.clipcut.ui.components.StatusRow
import com.clipcut.ui.components.Tone
import com.clipcut.ui.publish.PublishBanner
import com.clipcut.ui.share.ShareToX
import java.time.ZoneId

private val SERIES = listOf(Color(0xFF5B9BD5), Color(0xFFE07A5F), Color(0xFF6BBF8A), Color(0xFFF2B84B), Color(0xFFB39DDB))

@Composable
fun TaglinesScreen(
    modifier: Modifier = Modifier,
    viewModel: TaglinesViewModel = hiltViewModel(),
    onExit: () -> Unit = {},
) {
    val state by viewModel.state.collectAsState()
    val publish by viewModel.publishStatus.collectAsState()
    val zone = remember { ZoneId.systemDefault() }
    val context = LocalContext.current
    var sharing by remember { mutableStateOf(false) }

    Screen(title = "Tagline Counter", subtitle = "Tweets per tagline in the last 24 hours. Reads only.", modifier = modifier) {
        LazyColumn(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(14.dp)) {
            item {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    state.error?.let { StatusRow(it, Tone.Bad) }
                    state.message?.let { StatusRow(it, Tone.Good) }
                    if (state.error != null || state.message != null) TextButton(onClick = viewModel::dismiss) { Text("Dismiss") }
                }
            }
            item { PhraseList(state, viewModel) }
            val report = state.report
            if (report == null) {
                item {
                    Text(
                        "No count yet. Tap \"Count last 24 hours\". You will see the cost first.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            } else {
                item { ReportHeader(report, zone) }
                item { PublishBanner(publish, onRetry = viewModel::retryPublish) }
                items(report.counts, key = { it.tagline }) { c ->
                    TaglineCard(
                        report, c, zone,
                        look = state.deepLooks[c.tagline],
                        lookCount = state.lookCounts[c.tagline] ?: 0,
                        regulars = state.regulars[c.tagline].orEmpty(),
                        looking = state.deepLooking == c.tagline,
                        lookBusy = state.deepLooking != null,
                        onDeepLook = { viewModel.askDeepLook(c.tagline) },
                        onOpen = { url -> context.startActivity(android.content.Intent(android.content.Intent.ACTION_VIEW, android.net.Uri.parse(url))) },
                    )
                }
                item { Chart(report, zone) }
                item {
                    OutlinedButton(
                        onClick = { sharing = true },
                        modifier = Modifier.fillMaxWidth(),
                    ) { Text("Share on X") }
                }
                item { AiReadout(state, onAsk = viewModel::askToAnalyse) }
                item {
                    SectionCard("What the numbers show") {
                        insightLines(report, zone).forEach { Text("• $it", style = MaterialTheme.typography.bodyMedium) }
                        Text(COUNTS_LIMITS_NOTE, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
        }
        TextButton(onClick = onExit) { Text("Back") }
    }

    val shareReport = state.report
    if (sharing && shareReport != null) {
        ShareDialog(
            taglines = shareReport.counts.map { it.tagline },
            longPost = state.longPosts,
            onLongPost = viewModel::setLongPosts,
            onCancel = { sharing = false },
            onShare = { positions, stage ->
                sharing = false
                val card = taglineShareCard(
                    shareReport, dateTimeText(shareReport.fetchedAtMs, zone), zone, positions,
                    looks = state.deepLooks, readout = state.analysis, stage = stage, longPost = state.longPosts,
                )
                viewModel.recordTrending(shareReport.fetchedAtMs, positions)
                ShareToX.share(context, card, zone).let { o -> if (o.error != null) viewModel.showError(o.error) else viewModel.showInfo(o.message) }
            },
        )
    }

    state.confirmDeepLook?.let { tagline ->
        val blocked = viewModel.lookSizeBlocked()
        AlertDialog(
            onDismissRequest = viewModel::cancelDeepLook,
            title = { Text("Deep look?") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text(
                        "Grok (xAI) searches X for \"$tagline\" in the counted period and reports themes, top voices, who started it, " +
                            "rival taglines, paired hashtags and posts to open. Bigger sizes read more posts.",
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        DeepLookSize.entries.forEach { sz ->
                            FilterChip(selected = state.lookSize == sz, onClick = { viewModel.setLookSize(sz) }, label = { Text("${sz.label} $" + "%.2f".format(sz.budgetUsd)) })
                        }
                    }
                    Text(
                        "About ${state.lookSize.posts} posts, up to ${state.lookSize.searches} searches. xAI's real cost is shown after; it can land a little over or under. " +
                            "Today: $" + "%.2f".format(viewModel.deepLookSpentToday()) + " of the $" + "%.2f".format(DEEP_LOOK_DAILY_CAP_USD) + " daily limit. Read only; nothing is posted.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    blocked?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error) }
                }
            },
            confirmButton = {
                Button(onClick = viewModel::confirmDeepLook, enabled = blocked == null) { Text("Look (about $" + "%.2f".format(state.lookSize.budgetUsd) + ")") }
            },
            dismissButton = { TextButton(onClick = viewModel::cancelDeepLook) { Text("Cancel") } },
        )
    }

    if (state.confirmingAnalysis) {
        AlertDialog(
            onDismissRequest = viewModel::cancelAnalysis,
            title = { Text("AI read-out?") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text(
                        "Sends the figures on this screen (no tweets, no X calls) to ${viewModel.analysisLabel() ?: "the LLM"}. " +
                            (viewModel.analysisEstimateUsd()?.let { "About $" + "%.3f".format(it) + ". " } ?: "") +
                            "It reads the numbers and any Deep looks; it does not search X itself.",
                    )
                    if (viewModel.analysisHasHigherTier()) {
                        FilterChip(
                            selected = state.analysisHighQuality,
                            onClick = { viewModel.setAnalysisHighQuality(!state.analysisHighQuality) },
                            label = { Text("Higher quality (about 2× the cost)") },
                        )
                    }
                }
            },
            confirmButton = { Button(onClick = viewModel::confirmAnalysis) { Text("Write it") } },
            dismissButton = { TextButton(onClick = viewModel::cancelAnalysis) { Text("Cancel") } },
        )
    }

    val payer by viewModel.payer.collectAsState()
    if (state.confirming) {
        AlertDialog(
            onDismissRequest = viewModel::cancel,
            title = { Text("Count from X?") },
            text = {
                Text(
                    "This makes ${state.taglines.size * 2} calls to X (each phrase twice: all tweets, and without retweets), " +
                        "billed at about $${"%.3f".format(state.estimatedCostUsd)} in total. Nothing is posted.\n\n$payer",
                )
            },
            confirmButton = { Button(onClick = viewModel::confirmCount) { Text("Count (about $${"%.2f".format(state.estimatedCostUsd)})") } },
            dismissButton = { TextButton(onClick = viewModel::cancel) { Text("Cancel") } },
        )
    }
}

/**
 * Asks for the trending position before sharing. Every field is optional: a tagline left blank is shared without a
 * position. With several taglines there is one field each, since each can trend at its own position.
 */
@Composable
private fun ShareDialog(
    taglines: List<String>,
    longPost: Boolean,
    onLongPost: (Boolean) -> Unit,
    onCancel: () -> Unit,
    onShare: (Map<String, Int>, ShareStage) -> Unit,
) {
    val typed = remember { mutableStateMapOf<String, String>() }
    var stage by remember { mutableStateOf<ShareStage?>(null) }
    val parsed = taglines.associateWith { parsePosition(typed[it].orEmpty()) }
    val valid = parsed.values.none { it is PositionInput.Invalid } && stage != null
    AlertDialog(
        onDismissRequest = onCancel,
        title = { Text("Share on X") },
        text = {
            Column(modifier = Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text("Is this a running score or the final result (the trend has ended)?", style = MaterialTheme.typography.bodyMedium)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    ShareStage.entries.forEach { st ->
                        FilterChip(selected = stage == st, onClick = { stage = st }, label = { Text(st.label) })
                    }
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text("Long post (X Premium)", style = MaterialTheme.typography.titleSmall)
                        Text(
                            if (longPost) "Full AI read-out and Deep looks, with top voices tagged." else "One ordinary post (280 characters): the main numbers only.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    Switch(checked = longPost, onCheckedChange = onLongPost)
                }
                Text(
                    "Trending position (optional): type it only if you saw it in X's Trending list.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                taglines.forEach { t ->
                    val error = (parsed[t] as? PositionInput.Invalid)?.reason
                    OutlinedTextField(
                        value = typed[t].orEmpty(),
                        onValueChange = { v -> typed[t] = v.filter { it.isDigit() || it == '#' }.take(4) },
                        label = { Text(if (taglines.size == 1) "Position, e.g. 3" else t, maxLines = 1) },
                        placeholder = { Text("Not trending / skip") },
                        isError = error != null,
                        supportingText = error?.let { e -> { Text(e) } },
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
            }
        },
        confirmButton = {
            Button(
                enabled = valid,
                onClick = { stage?.let { st -> onShare(parsed.mapNotNull { (t, p) -> (p as? PositionInput.Ok)?.position?.let { t to it } }.toMap(), st) } },
            ) { Text("Share") }
        },
        dismissButton = { TextButton(onClick = onCancel) { Text("Cancel") } },
    )
}

/** The LLM's read-out of the count, or the button that asks for one. Labelled with who wrote it and what it cost. */
@Composable
private fun AiReadout(state: TaglinesUiState, onAsk: () -> Unit) {
    val a = state.analysis
    if (a == null) {
        OutlinedButton(onClick = onAsk, enabled = !state.analysing, modifier = Modifier.fillMaxWidth()) {
            if (state.analysing) {
                CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp)
                Text("  Writing the read-out…")
            } else {
                Text("AI read-out of these numbers")
            }
        }
        return
    }
    SectionCard("AI read-out") {
        Text(a.headline, style = MaterialTheme.typography.titleMedium)
        a.insights.forEach { Text("• $it", style = MaterialTheme.typography.bodyMedium) }
        Text("Momentum: ${a.momentum}", style = MaterialTheme.typography.bodyMedium)
        Text("Post angle: ${a.captionAngle}", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.primary)
        Text(
            "Written by ${a.label} from the counts and any Deep looks" + (a.costUsd?.let { ", about $" + "%.4f".format(it) } ?: "") + ". Check it before you post.",
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        TextButton(onClick = onAsk, enabled = !state.analysing) { Text(if (state.analysing) "Writing…" else "Write again") }
    }
}

@Composable
private fun PhraseList(state: TaglinesUiState, vm: TaglinesViewModel) {
    var draft by remember { mutableStateOf("") }
    var linking by remember { mutableStateOf<String?>(null) }
    SectionCard("Taglines to count") {
        state.taglines.forEach { t ->
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(t, style = MaterialTheme.typography.bodyMedium)
                    ContestantPill(state.contestants[t], onClick = { linking = t })
                }
                TextButton(onClick = { vm.remove(t) }, enabled = !state.running) { Text("Remove") }
            }
        }
        state.collected?.let { c ->
            Text(
                "Kept for the weekly ranking (last 7 days): ${c.taglineRuns} counts" + (if (c.trendingMarks > 0) ", ${c.trendingMarks} trending positions" else ""),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedTextField(draft, { draft = it }, label = { Text("Add a tagline") }, singleLine = true, modifier = Modifier.weight(1f))
            TextButton(onClick = { vm.add(draft); draft = "" }, enabled = draft.isNotBlank()) { Text("Add") }
        }
        if (state.running) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                CircularProgressIndicator(modifier = Modifier.size(22.dp).padding(end = 0.dp), strokeWidth = 2.dp)
                Text("  Asking X…", style = MaterialTheme.typography.bodyMedium)
            }
        } else {
            Button(onClick = vm::askToCount, enabled = state.taglines.isNotEmpty(), modifier = Modifier.fillMaxWidth()) {
                Text("Count last 24 hours (about $${"%.2f".format(state.estimatedCostUsd)})")
            }
        }
    }
    linking?.let { t ->
        ContestantDialog(t, state.contestants[t], vm.knownContestants(), onSave = { vm.setContestant(t, it); linking = null }, onCancel = { linking = null })
    }
}

@Composable
private fun ReportHeader(r: CountReport, zone: ZoneId) {
    val z = zoneLabel(zone, r.fetchedAtMs)
    Column {
        Text("From X's official API, counted ${dateTimeText(r.fetchedAtMs, zone)} $z", style = MaterialTheme.typography.labelLarge)
        Text(
            "Period ${dateTimeText(r.startMs, zone)} to ${dateTimeText(r.endMs, zone)}. Every total equals the sum of its hourly buckets.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun TaglineCard(
    r: CountReport,
    c: TaglineCount,
    zone: ZoneId,
    look: DeepLook?,
    lookCount: Int,
    regulars: List<Pair<String, Int>>,
    looking: Boolean,
    lookBusy: Boolean,
    onDeepLook: () -> Unit,
    onOpen: (String) -> Unit,
) {
    val stat = remember(r, c) { taglineStat(r, c) }
    SectionCard(c.tagline) {
        Text("${"%,d".format(c.all.total)} tweets", style = MaterialTheme.typography.headlineSmall)
        // The same figures as the share image: split, busiest hour, first seen and speed, milestones, last hour.
        stat.detailLines(zone).forEach { line ->
            Text(line, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        if (look != null) {
            DeepLookSection(look, lookCount, regulars, onOpen)
        }
        if (c.all.total > 0) {
            TextButton(onClick = onDeepLook, enabled = !lookBusy) {
                if (looking) {
                    CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
                    Text("  Grok is reading posts…")
                } else {
                    Text((if (look != null) "Look again: what's new" else "Deep look: top voices and themes") + " (from $" + "%.2f".format(DEEP_LOOK_ESTIMATE_USD) + ")")
                }
            }
        }
    }
}

@Composable
private fun Chart(r: CountReport, zone: ZoneId) {
    val xs = r.counts.flatMap { it.all.hours }.map { it.startMs }.distinct().sorted()
    val maxV = r.counts.flatMap { it.all.hours }.maxOfOrNull { it.count } ?: 0L
    if (xs.size < 2 || maxV == 0L) return
    val grid = MaterialTheme.colorScheme.outlineVariant
    SectionCard("Tweets per hour") {
        Canvas(modifier = Modifier.fillMaxWidth().height(160.dp)) {
            val w = size.width
            val h = size.height
            for (i in 0..3) drawLine(grid, Offset(0f, h * i / 3f), Offset(w, h * i / 3f), strokeWidth = 1f)
            r.counts.forEachIndexed { si, t ->
                val byStart = t.all.hours.associate { it.startMs to it.count }
                val path = Path()
                xs.forEachIndexed { i, s ->
                    val x = w * i / (xs.size - 1)
                    val y = h - h * ((byStart[s] ?: 0L).toFloat() / maxV)
                    if (i == 0) path.moveTo(x, y) else path.lineTo(x, y)
                }
                drawPath(path, SERIES[si % SERIES.size], style = Stroke(width = 4f))
            }
        }
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            val step = ((xs.size + 3) / 4).coerceAtLeast(1)
            xs.filterIndexed { i, _ -> i % step == 0 }.forEach { Text(clockText(it, zone), style = MaterialTheme.typography.labelSmall) }
        }
        Text("Top of the chart = ${"%,d".format(maxV)} tweets in one hour.", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        r.counts.forEachIndexed { si, t ->
            Row(verticalAlignment = Alignment.CenterVertically) {
                Surface(color = SERIES[si % SERIES.size], shape = CircleShape, modifier = Modifier.size(10.dp), content = {})
                Text(t.tagline, style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(start = 8.dp))
            }
        }
    }
}

/** Grok's read of a sample of this tagline's posts. Handles and posts open in X. Always labelled as a sample. */
@Composable
private fun DeepLookSection(look: DeepLook, lookCount: Int, regulars: List<Pair<String, Int>>, onOpen: (String) -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.padding(top = 6.dp)) {
        Text(
            "DEEP LOOK" + if (lookCount > 1) "  ·  $lookCount looks kept" else "",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.primary,
        )
        look.themes.forEach { Text("• $it", style = MaterialTheme.typography.bodyMedium) }
        if (look.changes.isNotBlank()) {
            Text("New since earlier looks: ${look.changes}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.tertiary)
        }
        if (look.voices.isNotEmpty()) {
            Text("Top voices in the sample", style = MaterialTheme.typography.titleSmall, modifier = Modifier.padding(top = 4.dp))
            look.voices.forEach { v ->
                Column(modifier = Modifier.fillMaxWidth().clickable { onOpen("https://x.com/${v.handle.removePrefix("@")}") }.padding(vertical = 2.dp)) {
                    Text(v.handle, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.primary)
                    if (v.why.isNotBlank()) Text(v.why, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
        if (look.starters.isNotEmpty()) {
            Text(
                "Started by: " + look.starters.joinToString(", ") { v -> v.handle + (v.why.takeIf { it.isNotBlank() }?.let { " ($it)" } ?: "") },
                style = MaterialTheme.typography.bodySmall,
            )
        }
        if (look.rivals.isNotEmpty()) {
            Text("Rivals pushing at the same time: " + look.rivals.joinToString(", "), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        if (look.pairedHashtags.isNotEmpty()) {
            Text("Used with: " + look.pairedHashtags.joinToString(" "), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        if (regulars.isNotEmpty()) {
            Text(
                "Steady drivers: " + regulars.take(5).joinToString(", ") { (h, n) -> "$h ($n looks)" },
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        if (look.examplePosts.isNotEmpty()) {
            Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                look.examplePosts.forEachIndexed { i, url -> TextButton(onClick = { onOpen(url) }) { Text("Post ${i + 1}") } }
            }
        }
        Text(
            "Grok (${look.label}" + (look.size.takeIf { it.isNotBlank() }?.let { ", $it" } ?: "") + ") read ${look.postsRead} posts: a sample, not all of them" +
                (look.costUsd?.let { ". Cost $" + "%.3f".format(it) } ?: "") + ".",
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}
