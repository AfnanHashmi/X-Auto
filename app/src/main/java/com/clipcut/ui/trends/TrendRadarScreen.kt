package com.clipcut.ui.trends

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Surface
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.repeatOnLifecycle
import com.clipcut.domain.radar.FindingRow
import com.clipcut.domain.radar.RadarConfig
import com.clipcut.domain.radar.RadarReport
import com.clipcut.domain.radar.RunKind
import com.clipcut.domain.radar.RunSummary
import com.clipcut.domain.radar.SubtopicRow
import com.clipcut.ui.caption.CaptionGeneratorActivity
import com.clipcut.ui.components.Screen
import com.clipcut.ui.components.SectionCard
import com.clipcut.ui.components.StatusRow
import com.clipcut.ui.components.Tone
import com.clipcut.ui.components.WarningCard
import kotlinx.coroutines.delay
import org.json.JSONArray
import org.json.JSONObject
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

private val WHEN = DateTimeFormatter.ofPattern("d MMM, HH:mm", Locale.getDefault())
private val CLOCK = DateTimeFormatter.ofPattern("HH:mm", Locale.getDefault())

private fun at(ms: Long?): String = ms?.let { Instant.ofEpochMilli(it).atZone(ZoneId.systemDefault()).format(WHEN) } ?: "never"

private fun clock(ms: Long?): String = ms?.let { Instant.ofEpochMilli(it).atZone(ZoneId.systemDefault()).format(CLOCK) } ?: "-"

private fun usd(v: Double) = "$" + "%.2f".format(v)

private fun openPost(context: Context, url: String) = context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))

private val POST_HANDLE = Regex("^https://(?:x|twitter)\\.com/([A-Za-z0-9_]{1,15})/status/\\d+$")

private fun handleOf(url: String): String? = POST_HANDLE.matchEntire(url)?.groupValues?.get(1)?.takeIf { it != "i" }

@Composable
fun TrendRadarScreen(
    modifier: Modifier = Modifier,
    viewModel: TrendRadarViewModel = hiltViewModel(),
    onExit: () -> Unit = {},
) {
    val state by viewModel.state.collectAsState()
    val context = LocalContext.current

    // Look again every so often while this screen is on show: quickly while a run is on its way.
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    LaunchedEffect(lifecycle) {
        lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
            while (true) {
                viewModel.refresh()
                val phase = viewModel.state.value.report?.session?.phase
                delay(if (viewModel.state.value.busy) 4_000 else if (phase == "running" || phase == "armed") 20_000 else 60_000)
            }
        }
    }

    Screen(title = "Trend Radar", subtitle = "What X is saying about Bigg Boss 20. Reads only: it never posts, replies, likes or follows.", modifier = modifier) {
        TabRow(state.tab, viewModel::setTab)
        LazyColumn(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(14.dp)) {
            item { Banner(state, viewModel) }
            val report = state.report
            if (report == null) {
                item {
                    Text(
                        if (!state.configured) "The Trend Radar address and token are not in local.properties yet." else if (state.loading) "Loading…" else "Nothing loaded yet.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            } else {
                when (state.tab) {
                    RadarTab.REPORT -> reportItems(report, state, viewModel, context)
                    RadarTab.COST -> costItems(report)
                    RadarTab.SETTINGS -> item { SettingsTab(report.config, viewModel) }
                }
            }
        }
        TextButton(onClick = onExit) { Text("Back") }
    }

    state.pending?.let { ConfirmRunDialog(it, onConfirm = viewModel::confirmRun, onCancel = viewModel::cancelPending) }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun TabRow(tab: RadarTab, onTab: (RadarTab) -> Unit) {
    SingleChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth()) {
        RadarTab.entries.forEachIndexed { i, t ->
            SegmentedButton(selected = tab == t, onClick = { onTab(t) }, shape = SegmentedButtonDefaults.itemShape(i, RadarTab.entries.size)) { Text(t.label) }
        }
    }
}

@Composable
private fun Banner(state: RadarUiState, vm: TrendRadarViewModel) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        state.error?.let { StatusRow(it, Tone.Bad) }
        state.message?.let { StatusRow(it, Tone.Good) }
        if (state.error != null || state.message != null) TextButton(onClick = vm::dismissMessage) { Text("Dismiss") }
        if (state.busy) {
            SectionCard("A run is on its way") {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    CircularProgressIndicator(modifier = Modifier.padding(end = 12.dp))
                    Text(if (state.report?.job?.running == true) "Reading X now…" else "Queued: it starts within about a minute…")
                }
                Text(
                    "It runs on its own servers, so you can leave this screen. A deep scan on the bigger model can take a few minutes.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

// ------------------------------------------------------------------------------------------------------------
// Report
// ------------------------------------------------------------------------------------------------------------
private fun LazyListScope.reportItems(report: RadarReport, state: RadarUiState, vm: TrendRadarViewModel, context: Context) {
    item { SessionCard(report, state, vm) }
    item { RunControls(report, state, vm) }
    val latest = report.latest
    if (latest == null) {
        item {
            Text(
                "No report yet. Switch the day session on, run a pulse for a quick look, or a deep scan for the full picture.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        return
    }
    item { ReportHeader(latest.run, report) }
    if (latest.subtopics.isEmpty()) item { Text("This run found no sub-topics.", style = MaterialTheme.typography.bodyMedium) }
    items(latest.subtopics, key = { "s-" + it.key }) { s -> SubtopicCard(s, state, vm, context) }

    val questions = latest.findings.filter { it.kind == "question" }
    if (questions.isNotEmpty()) {
        item { Text("What people are asking", style = MaterialTheme.typography.titleSmall) }
        items(questions, key = { "q-" + it.text }) { Text("• " + it.text, style = MaterialTheme.typography.bodyMedium) }
    }
    val replies = latest.findings.filter { it.kind == "reply" }
    if (replies.isNotEmpty()) {
        item { Text("Posts worth a reply (you reply yourself, in X)", style = MaterialTheme.typography.titleSmall) }
        items(replies, key = { "r-" + it.url }) { f -> ReplyCard(f, vm, context) }
    }
    val angles = latest.findings.filter { it.kind == "angle" }
    if (angles.isNotEmpty()) {
        item { Text("Ideas for your own posts", style = MaterialTheme.typography.titleSmall) }
        items(angles, key = { "a-" + it.text }) { f ->
            SectionCard("Idea") {
                Text(f.text, style = MaterialTheme.typography.bodyMedium)
                OutlinedButton(onClick = { vm.draftIdea(f.text); CaptionGeneratorActivity.start(context) }) { Text("Draft in Caption Generator") }
            }
        }
    }
}

/** The day switch: nothing runs until it is on, and it ends by itself at the end of the window. */
@Composable
private fun SessionCard(report: RadarReport, state: RadarUiState, vm: TrendRadarViewModel) {
    val s = report.session
    val c = report.config
    var budget by remember(s.on) { mutableStateOf("") }
    val defaultBudget = minOf(c.dailyCapUsd - report.spend.todayUsd, report.spend.creditsLeftUsd ?: Double.MAX_VALUE).coerceAtLeast(0.0)
    SectionCard("Day session") {
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    when (s.phase) {
                        "running" -> "On: working through today's window"
                        "armed" -> "On: waiting for the window to open"
                        "ended" -> "Ended"
                        else -> "Off: nothing runs by itself"
                    },
                    style = MaterialTheme.typography.titleMedium,
                )
                if (s.on) {
                    Text(
                        "Window ${clock(s.windowStartMs)} to ${clock(s.windowEndMs)} · spent ${usd(s.spentUsd)} of ${usd(s.budgetUsd ?: 0.0)}",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            Switch(checked = s.on, onCheckedChange = { on -> vm.setSession(on, budget) }, enabled = !state.acting)
        }
        if (!s.on) {
            OutlinedTextField(
                value = budget,
                onValueChange = { budget = it },
                label = { Text("Spend at most, USD (blank = ${usd(defaultBudget)})") },
                singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                modifier = Modifier.fillMaxWidth(),
            )
            Text(
                "Switch it on in the morning, after adding credits. It runs today's window, then switches itself off. It never starts the next day on its own.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            s.endedReason?.let { Text("Last session ended: $it.", style = MaterialTheme.typography.bodySmall) }
        } else {
            val total = s.deepSlots.size
            Text(
                "Scheduled deep scans done: ${s.deepDone.size.coerceAtMost(total)} of $total · a pulse every ${c.pulseEveryMin} minutes · last look ${clock(s.lastLookAtMs)}" +
                    (if (s.spikeQueue.isNotEmpty()) " · following up: ${s.spikeQueue.joinToString(", ")}" else ""),
                style = MaterialTheme.typography.bodySmall,
            )
            Text(
                "At 80% of the limit it only does the scheduled deep scans; at 100% it stops.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun RunControls(report: RadarReport, state: RadarUiState, vm: TrendRadarViewModel) {
    val c = report.config
    var size by remember(c.pulseSizes) { mutableStateOf(c.pulseSizes.firstOrNull() ?: c.pulsePosts) }
    SectionCard("Run now") {
        Text(
            "A run on request, even with the session off. It costs real credits; you see the estimate first.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(
            "Spent today ${usd(report.spend.todayUsd)} of ${usd(c.dailyCapUsd)}" + (report.spend.creditsLeftUsd?.let { " · credits left ${usd(it)}" } ?: ""),
            style = MaterialTheme.typography.labelMedium,
        )
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            c.pulseSizes.forEach { n -> FilterChip(selected = size == n, onClick = { size = n }, label = { Text("$n posts") }) }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Button(onClick = { vm.requestRun(RunKind.PULSE, size) }, enabled = !state.busy && !state.acting, modifier = Modifier.weight(1f)) { Text("Run pulse") }
            Button(onClick = { vm.requestRun(RunKind.DEEP) }, enabled = !state.busy && !state.acting, modifier = Modifier.weight(1f)) { Text("Run deep scan") }
        }
        TextButton(onClick = { vm.sample(RunKind.DEEP) }, enabled = !state.busy && !state.acting) { Text("See a sample report (free)") }
    }
}

@Composable
private fun ReportHeader(run: RunSummary, report: RadarReport) {
    val kind = if (run.type == "PULSE") "Pulse" else "Deep scan"
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        if (run.status == "dry_run") WarningCard("This is a saved SAMPLE answer, not live data. It cost nothing and does not count in trends.")
        Text("$kind, ${at(run.startedAtMs)}", style = MaterialTheme.typography.titleMedium)
        Text(
            "${run.model} · ${run.postsFetched} posts read (asked for about ${run.targetPosts}) · ${usd(run.costUsd)}" + (run.focus?.let { " · focused on \"$it\"" } ?: ""),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        val past = report.runs.count { it.status == "ok" && it.startedAtMs < run.startedAtMs }
        Text(
            if (past == 0) "No earlier runs to compare with yet, so everything counts as new." else "Compared with the $past earlier run${if (past == 1) "" else "s"}.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        if (run.droppedUrls > 0) Text("${run.droppedUrls} link(s) were left out because they were not real post links.", style = MaterialTheme.typography.bodySmall)
    }
}

@Composable
private fun labelColor(label: String): Color = when (label) {
    "RISING" -> MaterialTheme.colorScheme.error
    "NEW" -> MaterialTheme.colorScheme.primary
    "PEAKING" -> MaterialTheme.colorScheme.tertiary
    "FADING" -> MaterialTheme.colorScheme.onSurfaceVariant
    else -> MaterialTheme.colorScheme.secondary
}

@Composable
private fun SubtopicCard(s: SubtopicRow, state: RadarUiState, vm: TrendRadarViewModel, context: Context) {
    val color = labelColor(s.label)
    SectionCard(s.name) {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            Surface(color = color.copy(alpha = 0.16f), shape = CircleShape) {
                Text(s.label, color = color, style = MaterialTheme.typography.labelLarge, modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp))
            }
            Text(
                if (s.mentions == 0) "not in this run" else "${s.mentions} mentions · ${"%.0f".format(s.share * 100)}% of the sample · ${s.sentiment}",
                style = MaterialTheme.typography.bodySmall,
            )
        }
        if (s.baseline > 0 || s.label == "RISING") {
            Text(
                "Usually about ${"%.0f".format(s.baseline * 100)}% of the sample; this time ${"%.0f".format(s.share * 100)}%.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        if (s.keywords.isNotEmpty()) Text(s.keywords.joinToString(", "), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        s.exampleUrls.forEach { url ->
            Row(verticalAlignment = Alignment.CenterVertically) {
                TextButton(onClick = { openPost(context, url) }) { Text(handleOf(url)?.let { "@$it's post" } ?: "Open post") }
                handleOf(url)?.let { h -> TextButton(onClick = { vm.excludeHandle(h) }) { Text("Exclude @$h") } }
            }
        }
        if (s.mentions > 0) {
            OutlinedButton(onClick = { vm.requestRun(RunKind.DEEP, focus = s.name) }, enabled = !state.busy && !state.acting) { Text("Deep scan on this") }
        }
    }
}

@Composable
private fun ReplyCard(f: FindingRow, vm: TrendRadarViewModel, context: Context) {
    val url = f.url.orEmpty()
    SectionCard(handleOf(url)?.let { "@$it" } ?: "A post") {
        Text(f.text, style = MaterialTheme.typography.bodyMedium)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(onClick = { openPost(context, url) }) { Text("Open in X") }
            handleOf(url)?.let { h -> TextButton(onClick = { vm.excludeHandle(h) }) { Text("Exclude @$h") } }
        }
    }
}

@Composable
private fun ConfirmRunDialog(p: PendingRun, onConfirm: () -> Unit, onCancel: () -> Unit) {
    val pv = p.preview
    val kind = if (p.type == RunKind.PULSE) "pulse" else "deep scan"
    val duration = if (pv.model.contains("4.7")) "1 to 3 minutes" else "under a minute"
    AlertDialog(
        onDismissRequest = onCancel,
        title = { Text(if (!pv.allowed) "Cannot run this $kind" else "Run a $kind?") },
        text = {
            if (!pv.allowed) {
                Text(pv.refusal ?: "The cost guard refused this run.")
            } else {
                Text(
                    "Model ${pv.model}, about ${pv.posts} posts${p.focus?.let { ", focused on \"$it\"" } ?: ""}.\n\n" +
                        "Likely cost ${usd(pv.likelyUsd)}, at most about ${usd(pv.worstUsd)}. It takes $duration, on the server, so you can leave the app.\n\n" +
                        "Spent today ${usd(pv.spentTodayUsd)}" + (pv.creditsLeftUsd?.let { ". Credits left ${usd(it)}" } ?: "") + ".\n\n" +
                        "It only reads X. Nothing is posted.",
                )
            }
        },
        confirmButton = { if (pv.allowed) TextButton(onClick = onConfirm) { Text("Run, ${usd(pv.likelyUsd)}") } else TextButton(onClick = onCancel) { Text("OK") } },
        dismissButton = { if (pv.allowed) TextButton(onClick = onCancel) { Text("Cancel") } },
    )
}

// ------------------------------------------------------------------------------------------------------------
// Cost
// ------------------------------------------------------------------------------------------------------------
private fun LazyListScope.costItems(report: RadarReport) {
    item {
        SectionCard("Spend") {
            StatusRow("Today ${usd(report.spend.todayUsd)} of the ${usd(report.config.dailyCapUsd)} limit", Tone.Neutral)
            StatusRow("This month ${usd(report.spend.monthUsd)}", Tone.Neutral)
            report.config.creditsAddedUsd?.let {
                StatusRow("Credits you added ${usd(it)}, left about ${usd(report.spend.creditsLeftUsd ?: 0.0)} (counted from the radar's own runs)", Tone.Neutral)
            }
            Text(
                "Costs are xAI's own figure for each request. The balance on xAI's site is the source of truth.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
    item { Text("Runs", style = MaterialTheme.typography.titleSmall) }
    if (report.runs.isEmpty()) item { Text("No runs yet.", style = MaterialTheme.typography.bodyMedium) }
    items(report.runs, key = { "run-" + it.id }) { r ->
        Column(modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text((if (r.type == "PULSE") "Pulse" else "Deep scan") + " · ${r.model}", style = MaterialTheme.typography.bodyLarge)
                Text(usd(r.costUsd), style = MaterialTheme.typography.bodyLarge)
            }
            Text(
                "${at(r.startedAtMs)} · ${r.trigger.replace('_', ' ')} · ${r.status.replace('_', ' ')} · ${r.postsFetched} posts (asked ${r.targetPosts}) · ${r.searchCalls} searches · tokens ${r.inputTokens} in, ${r.outputTokens} out",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            r.error?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error) }
        }
    }
}

// ------------------------------------------------------------------------------------------------------------
// Settings
// ------------------------------------------------------------------------------------------------------------
private fun lines(text: String) = text.split("\n", ",").map { it.trim() }.filter { it.isNotEmpty() }

@Composable
private fun Field(label: String, value: String, onChange: (String) -> Unit, number: Boolean = false, lines: Int = 1) {
    OutlinedTextField(
        value = value,
        onValueChange = onChange,
        label = { Text(label) },
        minLines = lines,
        singleLine = lines == 1,
        keyboardOptions = if (number) KeyboardOptions(keyboardType = KeyboardType.Decimal) else KeyboardOptions.Default,
        modifier = Modifier.fillMaxWidth(),
    )
}

@Composable
private fun SettingsTab(c: RadarConfig, vm: TrendRadarViewModel) {
    val t = c.topic
    var pulseModel by remember(c) { mutableStateOf(c.pulseModel) }
    var deepModel by remember(c) { mutableStateOf(c.deepModel) }
    var cap by remember(c) { mutableStateOf(c.dailyCapUsd.toString()) }
    var credits by remember(c) { mutableStateOf(c.creditsAddedUsd?.toString().orEmpty()) }
    var sizes by remember(c) { mutableStateOf(c.pulseSizes.joinToString(", ")) }
    var deepPosts by remember(c) { mutableStateOf(c.deepPosts.toString()) }
    var en by remember(c) { mutableStateOf(t.keywordsEn.joinToString("\n")) }
    var hi by remember(c) { mutableStateOf(t.keywordsHi.joinToString("\n")) }
    var hing by remember(c) { mutableStateOf(t.keywordsHinglish.joinToString("\n")) }
    var contestants by remember(c) { mutableStateOf(t.contestants.joinToString("\n")) }
    var tags by remember(c) { mutableStateOf(t.hashtags.joinToString("\n")) }
    var excluded by remember(c) { mutableStateOf(c.excludedHandles.joinToString("\n")) }

    Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
        SectionCard("Schedule (IST)") {
            c.profiles.forEach { p ->
                val days = c.days.filterValues { it == p.name }.keys.joinToString(", ").uppercase()
                Text("${p.name.replaceFirstChar { it.uppercase() }}: $days", style = MaterialTheme.typography.bodyMedium)
                Text(
                    "Window ${p.start} to ${p.end} · deep scans at ${p.deepTimes.joinToString(", ")}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Text(
                "A pulse every ${c.pulseEveryMin} minutes (${c.pulsePosts} posts, ${c.peakPosts} during ${c.peakStart}-${c.peakEnd}). A window that ends at 01:00 belongs to the day it started. Up to ${c.spikeMaxPerDay} focused follow-ups a day.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        SectionCard("Money") {
            Field("Daily limit, USD", cap, { cap = it }, number = true)
            Field("Credits I added, USD (optional)", credits, { credits = it }, number = true)
            Text(
                "Enter the amount when you add credits on xAI. The radar subtracts what its own runs spend and refuses a run that could exceed what is left. It cannot see your real xAI balance.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        SectionCard("Models and size") {
            Field("Pulse model", pulseModel, { pulseModel = it })
            Field("Deep scan model", deepModel, { deepModel = it })
            Field("Pulse sizes (posts), comma separated", sizes, { sizes = it })
            Field("Deep scan posts", deepPosts, { deepPosts = it }, number = true)
        }
        SectionCard("What to watch: ${t.name}") {
            Field("English keywords (one per line)", en, { en = it }, lines = 3)
            Field("Hindi keywords", hi, { hi = it }, lines = 2)
            Field("Hinglish keywords", hing, { hing = it }, lines = 3)
            Field("Contestants", contestants, { contestants = it }, lines = 3)
            Field("Hashtags", tags, { tags = it }, lines = 3)
        }
        SectionCard("Accounts to ignore") {
            Field("Handles to leave out (spam, bots), at most 20", excluded, { excluded = it }, lines = 3)
        }
        Button(
            onClick = {
                val topic = JSONObject().put("name", t.name)
                    .put("keywords_en", JSONArray(lines(en))).put("keywords_hi", JSONArray(lines(hi))).put("keywords_hinglish", JSONArray(lines(hing)))
                    .put("contestants", JSONArray(lines(contestants))).put("hashtags", JSONArray(lines(tags)))
                val patch = JSONObject()
                    .put("pulse_model", pulseModel.trim().ifBlank { c.pulseModel })
                    .put("deep_model", deepModel.trim().ifBlank { c.deepModel })
                    .put("pulse_sizes", JSONArray(lines(sizes).mapNotNull { it.toIntOrNull() }.ifEmpty { c.pulseSizes }))
                    .put("deep", JSONObject().put("posts", deepPosts.toIntOrNull() ?: c.deepPosts))
                    .put("excluded_x_handles", JSONArray(lines(excluded).map { it.removePrefix("@") }))
                    .put("topics", JSONArray().put(topic))
                cap.toDoubleOrNull()?.takeIf { it > 0 }?.let { patch.put("daily_cap_usd", it) }
                patch.put("credits_added_usd", credits.trim().let { if (it.isEmpty()) JSONObject.NULL else (it.toDoubleOrNull() ?: JSONObject.NULL) })
                vm.saveSettings(patch)
            },
            modifier = Modifier.fillMaxWidth(),
        ) { Text("Save settings") }
    }
}
