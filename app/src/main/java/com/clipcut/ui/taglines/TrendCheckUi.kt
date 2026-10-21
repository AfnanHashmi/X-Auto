package com.clipcut.ui.taglines

import android.app.DatePickerDialog
import android.app.TimePickerDialog
import android.content.Context
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.clipcut.domain.taglines.DEFAULT_COST_PER_POST_USD
import com.clipcut.domain.taglines.SampleStats
import com.clipcut.domain.taglines.Sentiment
import com.clipcut.domain.taglines.TREND_CHECK_DAILY_CAP_USD
import com.clipcut.domain.taglines.TREND_MAX_BUDGET_USD
import com.clipcut.domain.taglines.TREND_LOOKBACK_MS
import com.clipcut.domain.taglines.TREND_MIN_BUDGET_USD
import com.clipcut.domain.taglines.TrendCheckResult
import com.clipcut.domain.taglines.clockText
import com.clipcut.domain.taglines.dateTimeText
import com.clipcut.domain.taglines.pct
import com.clipcut.domain.taglines.usdText
import java.time.Instant
import java.time.ZoneId
import java.time.ZonedDateTime

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

/** Android's date picker, then its time picker, in the phone's time zone. Dates are limited to what X keeps (the last 7 days). */
private fun pickDateTime(context: Context, initialMs: Long, zone: ZoneId, onPicked: (Long) -> Unit) {
    val now = System.currentTimeMillis()
    val init = Instant.ofEpochMilli(initialMs.coerceIn(now - TREND_LOOKBACK_MS + 60_000L, now)).atZone(zone)
    val dates = DatePickerDialog(context, { _, y, m, d ->
        TimePickerDialog(context, { _, h, min ->
            onPicked(ZonedDateTime.of(y, m + 1, d, h, min, 0, 0, zone).toInstant().toEpochMilli())
        }, init.hour, init.minute, false).show()
    }, init.year, init.monthValue - 1, init.dayOfMonth)
    dates.datePicker.minDate = now - TREND_LOOKBACK_MS
    dates.datePicker.maxDate = now
    dates.show()
}

/** One end of the range: its label and a button showing the chosen date and time (or "Pick"). */
@Composable
private fun RangeField(label: String, ms: Long?, zone: ZoneId, enabled: Boolean, onPick: () -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.fillMaxWidth()) {
        Text(label, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.width(44.dp))
        OutlinedButton(onClick = onPick, enabled = enabled, modifier = Modifier.weight(1f)) {
            Text(ms?.let { dateTimeText(it, zone) } ?: "Pick date and time")
        }
    }
}

/**
 * The Trend check panel. The user sets the time range and the budget; Run counts that range and then reads posts inside the
 * budget. "Suggest a range" is optional help that only fills the two fields.
 */
@Composable
fun TrendCheckDialog(vm: TaglinesViewModel, ui: TrendUi, zone: ZoneId, onOpen: (String) -> Unit) {
    val context = LocalContext.current
    val busy = ui.busy != null
    val check = ui.check
    val now = System.currentTimeMillis()
    val rangeProblem = vm.trendRangeProblem(ui, now)
    val budgetProblem = vm.trendBudgetProblem(ui)
    val plan = vm.trendPlan()
    val perPost = vm.trendCostPerPost()
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

                // 1. The time range: the user's
                Text("1. Time range", style = MaterialTheme.typography.titleSmall)
                Text("When to look. Any time in the last 7 days (that is all X keeps).", style = MaterialTheme.typography.bodySmall)
                RangeField("From", ui.startMs, zone, !busy) { pickDateTime(context, ui.startMs ?: (now - 6 * 3_600_000L), zone, vm::trendSetStart) }
                RangeField("To", ui.endMs, zone, !busy) { pickDateTime(context, ui.endMs ?: now, zone, vm::trendSetEnd) }
                if (ui.startMs != null || ui.endMs != null) {
                    rangeProblem?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error) }
                }
                TextButton(onClick = vm::trendSuggest, enabled = !busy) { Text("Not sure? Suggest a range from the counts (about $0.005)") }

                // 2. The budget: the user's
                Text("2. Budget", style = MaterialTheme.typography.titleSmall)
                OutlinedTextField(
                    value = ui.budgetText, onValueChange = vm::trendSetBudget, label = { Text("Budget in USD") }, placeholder = { Text("for example 0.50") },
                    singleLine = true, enabled = !busy,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal), modifier = Modifier.fillMaxWidth(),
                    supportingText = {
                        Text("${usdText(TREND_MIN_BUDGET_USD)} to ${usdText(TREND_MAX_BUDGET_USD)}. Today: ${usdText(vm.trendSpentToday())} of the ${usdText(TREND_CHECK_DAILY_CAP_USD)} daily limit.")
                    },
                )
                if (ui.budgetText.isNotBlank()) budgetProblem?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error) }
                val price = if (vm.trendCostMeasured()) "measured at ${"$"}${"%.4f".format(perPost)} a post" else "assumed ${"$"}${"%.3f".format(DEFAULT_COST_PER_POST_USD)} a post until the first run measures it"
                val budget = ui.budget
                when {
                    budgetProblem != null || budget == null -> Unit
                    plan != null && plan.slices.isEmpty() -> Text("Every hour of this range has already been read. Pick a different range to read more.", style = MaterialTheme.typography.bodySmall)
                    plan != null -> Text(
                        "Plan: up to ${plan.postsPlanned} posts in ${plan.slices.size} search${if (plan.slices.size == 1) "" else "es"} across ${plan.hoursCovered.size} busy hour${if (plan.hoursCovered.size == 1) "" else "s"}, " +
                            "at most ${usdText(plan.maxUsd)} (price $price).",
                        style = MaterialTheme.typography.bodySmall,
                    )
                    else -> Text(
                        "Up to ${(budget / perPost).toInt()} posts, at most ${usdText(budget)} (price $price). They are spread over the busy hours of your range, busier hours getting more.",
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
                if (rangeProblem == null) {
                    val end = ui.endMs!!
                    if (now - end < 10 * 60_000L) {
                        Text("Your range runs up to now. If the trend is still going, posts after now are not included.", style = MaterialTheme.typography.bodySmall, color = ORGANIZED)
                    } else if (now - end >= 24 * 3_600_000L) {
                        Text("This range ended over a day ago. X removes many spam accounts by then, so bot-like figures may read low.", style = MaterialTheme.typography.bodySmall, color = ORGANIZED)
                    }
                }

                // 3. Run
                Text(vm.trendPayer(), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                val canRun = !busy && rangeProblem == null && budgetProblem == null && budget != null && (plan == null || plan.slices.isNotEmpty())
                Button(onClick = vm::trendRun, enabled = canRun, modifier = Modifier.fillMaxWidth()) {
                    Text(if (budget != null && budgetProblem == null) "Run (up to ${usdText(plan?.maxUsd ?: budget)} + about $0.02 to count)" else "Run")
                }
                Text(
                    "Run counts your range first (about $0.02), then reads posts inside your budget and judges them. Nothing is posted.",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                if (check?.isFull == true) {
                    Text(
                        "Last sample for this range: ${check.postsRead} posts" + (check.costUsd?.let { ", X charged ${usdText(it)} in all" } ?: "") + ".",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                if (rangeProblem == null) {
                    OutlinedButton(onClick = vm::trendLive, enabled = !busy, modifier = Modifier.fillMaxWidth()) { Text("Refresh counts only (about $0.02)") }
                }

                // Results
                if (check != null) {
                    ExactSection(check, zone)
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
