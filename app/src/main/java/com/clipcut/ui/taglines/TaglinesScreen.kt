package com.clipcut.ui.taglines

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
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
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import com.clipcut.domain.taglines.COUNTS_LIMITS_NOTE
import com.clipcut.domain.taglines.CountReport
import com.clipcut.domain.taglines.TaglineCount
import com.clipcut.domain.taglines.clockText
import com.clipcut.domain.taglines.dateTimeText
import com.clipcut.domain.taglines.insightLines
import com.clipcut.domain.taglines.zoneLabel
import com.clipcut.ui.components.Screen
import com.clipcut.ui.components.SectionCard
import com.clipcut.ui.components.StatusRow
import com.clipcut.ui.components.Tone
import java.time.ZoneId

private val SERIES = listOf(Color(0xFF5B9BD5), Color(0xFFE07A5F), Color(0xFF6BBF8A), Color(0xFFF2B84B), Color(0xFFB39DDB))

@Composable
fun TaglinesScreen(
    modifier: Modifier = Modifier,
    viewModel: TaglinesViewModel = hiltViewModel(),
    onExit: () -> Unit = {},
) {
    val state by viewModel.state.collectAsState()
    val zone = remember { ZoneId.systemDefault() }

    Screen(title = "Tagline Counter", subtitle = "How many tweets used each phrase in the last 24 hours. Reads only; it never posts.", modifier = modifier) {
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
                items(report.counts, key = { it.tagline }) { c -> TaglineCard(c, zone) }
                item { Chart(report, zone) }
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

    if (state.confirming) {
        AlertDialog(
            onDismissRequest = viewModel::cancel,
            title = { Text("Count from X?") },
            text = {
                Text(
                    "This makes ${state.taglines.size * 2} calls to X (each phrase twice: all tweets, and without retweets), " +
                        "billed at about $${"%.3f".format(state.estimatedCostUsd)} in total. Nothing is posted.",
                )
            },
            confirmButton = { Button(onClick = viewModel::confirmCount) { Text("Count (about $${"%.2f".format(state.estimatedCostUsd)})") } },
            dismissButton = { TextButton(onClick = viewModel::cancel) { Text("Cancel") } },
        )
    }
}

@Composable
private fun PhraseList(state: TaglinesUiState, vm: TaglinesViewModel) {
    var draft by remember { mutableStateOf("") }
    SectionCard("Taglines to count") {
        state.taglines.forEach { t ->
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                Text(t, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
                TextButton(onClick = { vm.remove(t) }, enabled = !state.running) { Text("Remove") }
            }
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
private fun TaglineCard(c: TaglineCount, zone: ZoneId) {
    SectionCard(c.tagline) {
        Text("${"%,d".format(c.all.total)} tweets", style = MaterialTheme.typography.headlineSmall)
        Text(
            "${"%,d".format(c.originals.total)} original, ${"%,d".format(c.all.total - c.originals.total)} retweets (${"%.0f".format(c.retweetShare * 100)}% retweets)",
            style = MaterialTheme.typography.bodyMedium,
        )
        val first = c.firstActive
        val peak = c.peak
        if (first != null && peak != null) {
            Text(
                "First seen ${clockText(first.startMs, zone)}. Busiest hour ${clockText(peak.startMs, zone)} to ${clockText(peak.startMs + 3_600_000L, zone)}: ${"%,d".format(peak.count)} tweets.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        } else {
            Text("No tweets found in this period.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
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
