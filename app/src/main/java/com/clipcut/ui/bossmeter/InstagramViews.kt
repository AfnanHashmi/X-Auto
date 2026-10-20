package com.clipcut.ui.bossmeter

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.clipcut.domain.bossmeter.IgBossResult
import com.clipcut.domain.bossmeter.Overall
import com.clipcut.domain.taglines.dateTimeText
import com.clipcut.ui.components.SectionCard
import com.clipcut.ui.components.StatusRow
import com.clipcut.ui.components.Tone
import com.clipcut.ui.components.WarningCard
import java.time.ZoneId

private fun n(v: Long) = "%,d".format(v)

/** X | Instagram | Overall. */
@Composable
fun BossTabs(state: BossMeterUiState, onSelect: (BossTab) -> Unit) {
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        BossTab.entries.forEach { tab ->
            FilterChip(selected = state.tab == tab, onClick = { onSelect(tab) }, label = { Text(tab.label) })
        }
    }
}

/** The messages that belong to the Instagram side, on its tab and on Overall. */
private fun LazyListScope.igNotices(state: BossMeterUiState) {
    item {
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            state.igError?.let { StatusRow(it, Tone.Bad) }
            state.igMessage?.let { StatusRow(it, Tone.Good) }
            if (!state.igConfigured) {
                WarningCard("Instagram is not set up on this build. Add IG_BASE_URL and IG_API_KEY to local.properties and rebuild.")
            }
            if (state.igWatch?.sessionExpired == true) {
                WarningCard("The Instagram session expired: the server stopped polling. Put a fresh sessionid cookie on the server and add the post again.")
            } else if (state.igWatch?.blocked == true) {
                WarningCard("Instagram is limiting the server (${state.igWatch.status}). It pauses and retries by itself.")
            }
        }
    }
}

fun LazyListScope.instagramView(state: BossMeterUiState, vm: BossMeterViewModel) {
    igNotices(state)
    item {
        SectionCard("Instagram post") {
            OutlinedTextField(
                state.igPost, vm::setIgPost, label = { Text("Post link") }, singleLine = true, modifier = Modifier.fillMaxWidth(),
                supportingText = {
                    Text(
                        if (state.igPost.isBlank()) "Paste the Instagram post link." else state.igShortcode?.let { "Post $it" } ?: "Not an Instagram post link",
                        color = if (state.igPost.isNotBlank() && state.igShortcode == null) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                },
            )
            Text(
                "Uses the hashtags from the X tab: ${state.hashtags.joinToString(" ").ifEmpty { "none yet" }}",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            state.igWatch?.let { w ->
                Text(
                    "Server: ${w.status} · ${n(w.stored)} comments stored" + (w.lastPoll?.let { " · last poll $it UTC" } ?: ""),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(modifier = Modifier.weight(1f)) {
                    Text("Exclude hidden comments", style = MaterialTheme.typography.titleSmall)
                    Text("Leaves out the ones Instagram hides behind \"View hidden comments\".", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                Switch(checked = state.excludeHidden, onCheckedChange = vm::setExcludeHidden)
            }
            if (state.igBusy) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    CircularProgressIndicator(modifier = Modifier.size(22.dp), strokeWidth = 2.dp)
                    Text("Asking the Instagram server…", style = MaterialTheme.typography.bodyMedium)
                }
            } else {
                Button(onClick = vm::watchInstagram, enabled = state.igConfigured && state.igShortcode != null, modifier = Modifier.fillMaxWidth()) { Text("Watch this post") }
                OutlinedButton(onClick = vm::refreshInstagram, enabled = state.canAskInstagram, modifier = Modifier.fillMaxWidth()) { Text("↻ Refresh Instagram numbers") }
                Text(
                    "Free: the server does the counting and X is not called. Counting on the X tab also refreshes this, up to the same moment.",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
    val ig = state.ig
    if (ig == null) {
        item { Text("No Instagram numbers yet.", color = MaterialTheme.colorScheme.onSurfaceVariant) }
        return
    }
    if (ig.empty) {
        item { Text("The server has no comments for this post in this window yet.", color = MaterialTheme.colorScheme.onSurfaceVariant) }
        return
    }
    item { IgTable(ig) }
    item { IgSummary(ig) }
    item { IgExtras(ig) }
}

@Composable
private fun IgTable(ig: IgBossResult) {
    SectionCard("Instagram (share of hashtag votes)") {
        val top = ig.items.firstOrNull()?.count?.coerceAtLeast(1L) ?: 1L
        ig.items.forEachIndexed { i, t ->
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    Text("${i + 1}. ${t.label}", style = MaterialTheme.typography.titleSmall, modifier = Modifier.weight(1f))
                    Text("${n(t.count)} (${"%.1f".format(t.share)}%)", style = MaterialTheme.typography.bodyMedium)
                }
                LinearProgressIndicator(progress = { t.count.toFloat() / top }, modifier = Modifier.fillMaxWidth())
            }
        }
    }
}

@Composable
private fun IgSummary(ig: IgBossResult) {
    SectionCard("Instagram thread") {
        Text("Total comments in the thread: ${n(ig.total)}")
        Text("Tracked hashtag votes: ${n(ig.tracked)}")
        Text("Uncounted: ${n(ig.uncounted)} (${"%.1f".format(ig.uncountedPct)}% of the thread)")
        ig.leadText?.let { Text(it) }
        Text(
            "A comment with two tracked hashtags counts for each: ${n(ig.doubleCounted)} such comment(s). " +
                "Counting those for neither gives ${n(ig.trackedStrict)} tracked votes.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        val fresh = buildString {
            ig.lastCommentAtIst?.let { append("Last comment fetched at ${it.replace('T', ' ').removeSuffix("+05:30")} IST. ") }
            append("Fetched ${n(ig.total)}")
            ig.reported?.let { append(" · Instagram reports ${n(it)} for the whole post") }
            if (ig.untilTs != null) append(" · counted up to ${dateTimeText(ig.untilTs * 1000, ZoneId.systemDefault())}")
            if (ig.excludeHidden) append(" · hidden comments left out")
        }
        Text(fresh, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        if (!ig.checksOk) WarningCard("A built-in check failed: ${ig.checks.filterValues { !it }.keys.joinToString()}. Do not trust these numbers until it is looked into.")
    }
}

private val BREAKDOWN = listOf(
    "A" to "A hashtag that names a contestant (another spelling)",
    "B" to "Other hashtags",
    "C" to "A name in plain text, no hashtag",
    "D" to "Only @mentions",
    "E" to "Emoji or symbols only",
    "F" to "Other text",
)

@Composable
private fun IgExtras(ig: IgBossResult) {
    val looks = ig.items.filter { it.lookalikeTag != null }
    SectionCard("Checks and what is uncounted") {
        if (looks.isNotEmpty()) {
            Text("Lookalike spelling (a lowercase l instead of a capital I), counted apart and not merged:", style = MaterialTheme.typography.labelLarge)
            looks.forEach { Text("${it.lookalikeTag}: ${n(it.lookalikeCount)}", style = MaterialTheme.typography.bodyMedium) }
        }
        if (ig.topUntracked.isNotEmpty()) {
            Text("Top untracked hashtags:", style = MaterialTheme.typography.labelLarge)
            ig.topUntracked.forEach { (tag, c) -> Text("$tag: ${n(c)}", style = MaterialTheme.typography.bodyMedium) }
        }
        Text("Comments with no tracked hashtag, by kind:", style = MaterialTheme.typography.labelLarge)
        BREAKDOWN.forEach { (k, name) -> Text("$name: ${n(ig.breakdown[k] ?: 0)}", style = MaterialTheme.typography.bodyMedium) }
        Text(
            "Uncounted is never called spam: it is comments with no tracked hashtag, other spellings, or comments Instagram does not return.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

fun LazyListScope.overallView(state: BossMeterUiState, zone: ZoneId) {
    igNotices(state)
    val o = state.overall
    if (o == null) {
        item { Text("Count on the X tab, or refresh Instagram, to see both added together.", color = MaterialTheme.colorScheme.onSurfaceVariant) }
        return
    }
    item { OverallTable(o) }
    item {
        SectionCard("Both added together") {
            Text("Total in both threads: ${n(o.total)}")
            Text("Tracked hashtag votes: ${n(o.tracked)}")
            Text("Uncounted: ${n(o.uncounted)} (${"%.1f".format(o.uncountedPct)}%)")
            o.leadText?.let { Text(it) }
            Text(
                "Added up from the two sources; shares and uncounted are worked out again from the sums.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            state.result?.let { Text("X: counted ${dateTimeText(it.fetchedAtMs, zone)}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
            state.ig?.takeUnless { it.empty }?.let {
                val upTo = it.untilTs?.let { ts -> "up to ${dateTimeText(ts * 1000, zone)}" } ?: "as of ${dateTimeText(it.fetchedAtMs, zone)}"
                Text("Instagram: counted $upTo", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            if (!o.hasX) Text("X has no count yet, so only Instagram is shown.", color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
            if (!o.hasIg) Text("Instagram has no numbers yet, so only X is shown.", color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
            Text(
                "Lookalike spellings, untracked hashtags and the uncounted breakdown exist for Instagram only (see its tab): X would need a paid call for each reply.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun OverallTable(o: Overall) {
    SectionCard("Overall (X + Instagram)") {
        val top = o.rows.firstOrNull()?.total?.coerceAtLeast(1L) ?: 1L
        o.rows.forEachIndexed { i, r ->
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    Text("${i + 1}. ${r.label}", style = MaterialTheme.typography.titleSmall, modifier = Modifier.weight(1f))
                    Text("${n(r.total)} (${"%.1f".format(r.share)}%)", style = MaterialTheme.typography.bodyMedium)
                }
                LinearProgressIndicator(progress = { r.total.toFloat() / top }, modifier = Modifier.fillMaxWidth())
                Text(
                    "X ${r.x?.let(::n) ?: "no data"} · Instagram ${r.ig?.let(::n) ?: "no data"}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}
