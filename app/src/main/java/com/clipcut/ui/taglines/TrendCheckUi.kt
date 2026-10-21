package com.clipcut.ui.taglines

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.clipcut.domain.taglines.DEFAULT_COST_PER_POST_USD
import com.clipcut.domain.taglines.SampleStats
import com.clipcut.domain.taglines.Sentiment
import com.clipcut.domain.taglines.TREND_CHECK_DAILY_CAP_USD
import com.clipcut.domain.taglines.TREND_MAX_BUDGET_USD
import com.clipcut.domain.taglines.TREND_MIN_BUDGET_USD
import com.clipcut.domain.taglines.TrendCheckResult
import com.clipcut.domain.taglines.clockText
import com.clipcut.domain.taglines.dateTimeText
import com.clipcut.domain.taglines.pct
import com.clipcut.domain.taglines.usdText
import java.time.ZoneId

private val GOOD = Color(0xFF6BBF8A)
private val BAD = Color(0xFFE07A5F)
private val NEUTRAL = Color(0xFF9AA0A6)
private val ORGANIZED = Color(0xFFF2B84B)

/** A short card on the tagline: the headline figures of its newest Trend check, always marked as a sample. */
@Composable
fun TrendCheckSummary(check: TrendCheckResult, zone: ZoneId) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp), modifier = Modifier.padding(top = 6.dp)) {
        Text("TREND CHECK  ·  ${dateTimeText(check.startMs, zone)} to ${dateTimeText(check.endMs, zone)}", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary)
        Text(
            "${check.shares.retweetPct}% retweets · ${check.shares.replyPct}% replies · ${check.shares.quotePct}% quotes" +
                (check.launch?.let { " · launch " + it.label.substringBefore(':').lowercase() } ?: ""),
            style = MaterialTheme.typography.bodyMedium,
        )
        check.stats?.let { s ->
            Text(
                "About ${s.naturalPct}% natural · ${s.botLikePct}% bot-like · ${s.copyPastePct}% copy-pasted (sample of ${s.sample} posts)",
                style = MaterialTheme.typography.bodyMedium,
            )
        }
        check.sentiment?.takeIf { it.total > 0 }?.let { m ->
            Text(
                "Mood: ${pct(m.positive.toLong(), m.total.toLong())}% positive · ${pct(m.negative.toLong(), m.total.toLong())}% negative · ${pct(m.neutral.toLong(), m.total.toLong())}% neutral",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun TrendCheckDialog(vm: TaglinesViewModel, ui: TrendUi, zone: ZoneId, onOpen: (String) -> Unit) {
    val busy = ui.busy != null
    val check = ui.check
    val range = ui.range
    AlertDialog(
        onDismissRequest = vm::closeTrend,
        title = { Text("Trend check: ${ui.tagline}") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.verticalScroll(rememberScrollState())) {
                ui.busy?.let {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp)
                        Text(it, style = MaterialTheme.typography.bodyMedium)
                    }
                }
                ui.error?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodyMedium) }
                ui.notice?.let { Text(it, color = GOOD, style = MaterialTheme.typography.bodyMedium) }

                // 1. When the trend ran
                Text("1. When did it run?", style = MaterialTheme.typography.titleSmall)
                if (range == null) {
                    Text("X keeps the last 7 days. One count (about $0.005) finds when this tagline started and when it died down, so you do not have to guess.", style = MaterialTheme.typography.bodySmall)
                } else {
                    Text(
                        "${dateTimeText(range.startMs, zone)}  to  ${dateTimeText(range.endMs, zone)}" + if (range.ended) "  (ended)" else "  (still running)",
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                        OutlinedButton(onClick = { vm.trendShift(-1, 0) }, enabled = !busy) { Text("Start −1h") }
                        OutlinedButton(onClick = { vm.trendShift(1, 0) }, enabled = !busy) { Text("Start +1h") }
                        OutlinedButton(onClick = { vm.trendShift(0, -1) }, enabled = !busy) { Text("End −1h") }
                        OutlinedButton(onClick = { vm.trendShift(0, 1) }, enabled = !busy) { Text("End +1h") }
                    }
                }
                OutlinedButton(onClick = vm::trendDetect, enabled = !busy, modifier = Modifier.fillMaxWidth()) {
                    Text(if (range == null) "Find the trend range (about $0.005)" else "Find it again (about $0.005)")
                }

                // 2. The exact numbers
                if (range != null) {
                    Text("2. Exact numbers", style = MaterialTheme.typography.titleSmall)
                    Text(
                        "Four count calls (about $0.02) for this range. Free of any guessing; refresh as often as you like while it runs.",
                        style = MaterialTheme.typography.bodySmall,
                    )
                    Button(onClick = vm::trendLive, enabled = !busy, modifier = Modifier.fillMaxWidth()) {
                        Text(if (check == null) "Count this range (about $0.02)" else "Refresh counts (about $0.02)")
                    }
                }
                if (check != null) {
                    ExactSection(check, zone)

                    // 3. The sample
                    Text("3. Read a sample of the posts", style = MaterialTheme.typography.titleSmall)
                    FullSection(vm, ui, check, zone)
                    check.stats?.let { StatsSection(it, check, onOpen) }
                    check.sentiment?.takeIf { it.total > 0 }?.let { SentimentSection(it) }
                }
            }
        },
        confirmButton = { TextButton(onClick = vm::closeTrend, enabled = !busy) { Text("Close") } },
    )
}

@Composable
private fun ExactSection(c: TrendCheckResult, zone: ZoneId) {
    val peak = c.hourly.maxOfOrNull { it.count } ?: 0L
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text("${"%,d".format(c.shares.all)} tweets in this range", style = MaterialTheme.typography.titleMedium)
        Text(
            "${c.shares.retweetPct}% retweets · ${c.shares.originalPct}% not retweets (${c.shares.replyPct}% replies, ${c.shares.quotePct}% quotes)",
            style = MaterialTheme.typography.bodyMedium,
        )
        c.launch?.let { Text("Launch: ${it.label}", style = MaterialTheme.typography.bodyMedium) }
        if (c.waves.isNotEmpty()) {
            Text("Push waves began at: " + c.waves.joinToString(", ") { clockText(it, zone) }, style = MaterialTheme.typography.bodySmall)
        }
        if (peak > 0 && c.hourly.size >= 2) {
            val wave = c.waves.toSet()
            val bar = MaterialTheme.colorScheme.primary
            val mark = ORGANIZED
            Canvas(modifier = Modifier.fillMaxWidth().height(90.dp).padding(top = 4.dp)) {
                val w = size.width / c.hourly.size
                c.hourly.forEachIndexed { i, h ->
                    val bh = size.height * h.count.toFloat() / peak
                    drawRect(if (h.startMs in wave) mark else bar, Offset(i * w + 1f, size.height - bh), Size((w - 2f).coerceAtLeast(1f), bh))
                }
            }
            Text(
                "Tweets per hour, ${clockText(c.hourly.first().startMs, zone)} to ${clockText(c.hourly.last().startMs, zone)}. Top = ${"%,d".format(peak)}. Orange bars mark where a push wave began.",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun FullSection(vm: TaglinesViewModel, ui: TrendUi, check: TrendCheckResult, zone: ZoneId) {
    val busy = ui.busy != null
    val plan = vm.trendPlan()
    val budget = ui.budget
    val spent = vm.trendSpentToday()
    val perPost = vm.trendCostPerPost()
    val ageH = (System.currentTimeMillis() - check.endMs) / 3_600_000L
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(
            "Reads posts from X spread over the hours of the trend (busier hours get more), then works out how natural or organized it looked and how people felt. " +
                "It is a sample, so every figure is an estimate. Nothing is posted.",
            style = MaterialTheme.typography.bodySmall,
        )
        OutlinedTextField(
            value = ui.budgetText, onValueChange = vm::trendSetBudget, label = { Text("Budget (USD)") }, singleLine = true,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal), modifier = Modifier.fillMaxWidth(),
            supportingText = { Text("${usdText(TREND_MIN_BUDGET_USD)} to ${usdText(TREND_MAX_BUDGET_USD)}. Today: ${usdText(spent)} of ${usdText(TREND_CHECK_DAILY_CAP_USD)} daily limit.") },
        )
        val blocked = budget?.let { b -> if (b < TREND_MIN_BUDGET_USD || b > TREND_MAX_BUDGET_USD) "Budget must be ${usdText(TREND_MIN_BUDGET_USD)} to ${usdText(TREND_MAX_BUDGET_USD)}." else if (spent + b > TREND_CHECK_DAILY_CAP_USD) "That would pass today's limit." else null }
            ?: "Type a budget."
        when {
            blocked != null -> Text(blocked, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
            plan == null -> Text("That budget cannot buy a single search. Try at least ${usdText(10 * perPost)}.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
            plan.slices.isEmpty() -> Text("Every hour of this trend has already been read.", style = MaterialTheme.typography.bodySmall)
            else -> Text(
                "Plan: up to ${plan.postsPlanned} posts in ${plan.slices.size} search${if (plan.slices.size == 1) "" else "es"} across ${plan.hoursCovered.size} hour${if (plan.hoursCovered.size == 1) "" else "s"}, " +
                    "at most ${usdText(plan.maxUsd)}. " + if (vm.trendCostMeasured()) "Price per post measured at ${"$"}${"%.4f".format(perPost)}." else "Price per post assumed at ${"$"}${"%.3f".format(DEFAULT_COST_PER_POST_USD)}; the first run measures the real one.",
                style = MaterialTheme.typography.bodySmall,
            )
        }
        if (!check.ended) Text("This trend is still running, so only the hours so far are read. Best done once it has ended.", style = MaterialTheme.typography.bodySmall, color = ORGANIZED)
        else if (ageH >= 24) Text("It ended over a day ago. X removes many spam accounts by now, so bot-like figures may read low.", style = MaterialTheme.typography.bodySmall, color = ORGANIZED)
        Text(vm.trendPayer(), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Button(onClick = vm::trendFull, enabled = !busy && blocked == null && plan != null && plan.slices.isNotEmpty(), modifier = Modifier.fillMaxWidth()) {
            Text(if (plan != null && plan.slices.isNotEmpty()) "Read posts (up to ${usdText(plan.maxUsd)})" else "Read posts")
        }
        if (check.isFull) Text("Last sample: ${check.postsRead} posts" + (check.costUsd?.let { ", X charged ${usdText(it)} in all for this trend" } ?: "") + ".", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun TwoPartBar(parts: List<Pair<Float, Color>>) {
    Row(modifier = Modifier.fillMaxWidth().height(12.dp).clip(RoundedCornerShape(6.dp)).background(MaterialTheme.colorScheme.surfaceVariant)) {
        parts.filter { it.first > 0f }.forEach { (w, c) -> Box(modifier = Modifier.weight(w).fillMaxHeight().background(c)) }
    }
}

@Composable
private fun StatsSection(s: SampleStats, c: TrendCheckResult, onOpen: (String) -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text("Natural or organized? (sample of ${s.sample} posts, estimates ±5–7%)", style = MaterialTheme.typography.titleSmall)
        TwoPartBar(listOf(s.naturalPct.toFloat() to GOOD, (100 - s.naturalPct).toFloat() to ORGANIZED))
        Text("About ${s.naturalPct}% natural · ${100 - s.naturalPct}% organized (copy-pasted or bot-like accounts)", style = MaterialTheme.typography.bodyMedium)
        listOf(
            "Bot-like accounts" to "${s.botLikePct}%",
            "Copy-pasted text" to "${s.copyPastePct}%",
            "Accounts under 30 days old" to "${s.newAccountPct}%",
            "Very low reach (few followers or default picture)" to "${s.lowReachPct}%",
            "Fan-club style accounts" to "${s.fanClubPct}%",
            "Posts from the 10 busiest accounts" to "${s.top10AuthorsPct}%",
            "Median likes on a post (not retweets)" to "${s.medianLikes}",
            "Posts with 0 likes" to "${s.zeroLikePct}%",
            "With pictures or video" to "${s.mediaPct}%",
        ).forEach { (k, v) ->
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text(k, style = MaterialTheme.typography.bodySmall, modifier = Modifier.weight(1f))
                Text(v, style = MaterialTheme.typography.bodyMedium)
            }
        }
        s.indiaPctOfLocated?.let { Text("Of accounts that list a place, about $it% are in India.", style = MaterialTheme.typography.bodySmall) }
        if (s.languages.isNotEmpty()) Text("Languages: " + s.languages.joinToString(", ") { (l, n) -> "$l ${pct(n.toLong(), s.sample.toLong())}%" }, style = MaterialTheme.typography.bodySmall)
        if (s.organizers.isNotEmpty()) {
            Text("Most retweeted accounts in the sample", style = MaterialTheme.typography.titleSmall, modifier = Modifier.padding(top = 4.dp))
            s.organizers.forEach { (h, n) ->
                Text(
                    "$h  ·  $n retweets", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.fillMaxWidth().clickable { onOpen("https://x.com/${h.removePrefix("@")}") }.padding(vertical = 2.dp),
                )
            }
        }
        if (s.pairedHashtags.isNotEmpty()) Text("Used with: " + s.pairedHashtags.joinToString(" ") { it.first }, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        if (s.topMentions.isNotEmpty()) Text("Tagging: " + s.topMentions.joinToString(" ") { it.first }, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(
            "\"Bot-like\" means an account with two or more of: made within 30 days, very few followers or a default picture, or posting over 200 times a day. It is a signal, not proof.",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun SentimentSection(m: Sentiment) {
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text("How people felt (${m.judged} posts judged by ${m.label.ifBlank { "the AI" }})", style = MaterialTheme.typography.titleSmall)
        TwoPartBar(listOf(m.positive.toFloat() to GOOD, m.neutral.toFloat() to NEUTRAL, m.negative.toFloat() to BAD))
        Text(
            "${pct(m.positive.toLong(), m.total.toLong())}% positive · ${pct(m.neutral.toLong(), m.total.toLong())}% neutral · ${pct(m.negative.toLong(), m.total.toLong())}% negative",
            style = MaterialTheme.typography.bodyMedium,
        )
        m.byContestant.entries.sortedByDescending { it.value.first + it.value.second + it.value.third }.take(6).forEach { (name, t) ->
            val total = (t.first + t.second + t.third).toLong()
            Text("$name: ${pct(t.first.toLong(), total)}% positive · ${pct(t.second.toLong(), total)}% negative  ($total posts)", style = MaterialTheme.typography.bodySmall)
        }
    }
}
