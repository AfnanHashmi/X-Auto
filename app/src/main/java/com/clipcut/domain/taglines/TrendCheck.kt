package com.clipcut.domain.taglines

import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/** How far back X's recent endpoints reach. A little less than 7 days, so the oldest hour is never refused. */
const val TREND_LOOKBACK_MS = 7 * 24 * HOUR_MS - 2 * HOUR_MS

/** A trend's life: the first and last busy hours. [endMs] is the end of the last busy hour; [ended] is false while it is still running. */
data class TrendRange(val startMs: Long, val endMs: Long, val ended: Boolean) {
    val hours: Int get() = ((endMs - startMs) / HOUR_MS).toInt()
}

/** Quiet hours in a row after the last busy hour before the trend counts as ended. */
const val QUIET_HOURS_TO_END = 2

/**
 * Finds the trend inside up to 7 days of hourly counts. Busy means at least [busyThreshold]: 2% of the peak, but never
 * below 5 tweets, and never below 3 times the typical hour (the lower quarter of the week, which a long trend cannot lift). The first busy hour is the start; the last busy hour
 * is the end; the trend has ended when [QUIET_HOURS_TO_END] or more full hours have passed since. Null when nothing was busy.
 */
fun detectRange(hours: List<HourBucket>, nowMs: Long): TrendRange? {
    val sorted = hours.sortedBy { it.startMs }
    val peak = sorted.maxOfOrNull { it.count } ?: return null
    if (peak < 5) return null
    val typical = sorted.map { it.count }.sorted().let { it[it.size / 4] }
    val threshold = max(5.0, max(0.02 * peak, 3.0 * typical))
    val busy = sorted.filter { it.count >= threshold }
    if (busy.isEmpty()) return null
    val start = busy.first().startMs
    val end = busy.last().startMs + HOUR_MS
    val ended = nowMs - end >= QUIET_HOURS_TO_END * HOUR_MS
    return TrendRange(start, if (ended) end else min(end, nowMs), ended)
}

enum class LaunchType(val label: String) {
    SUDDEN("Sudden: from nothing to near its peak within 2 hours (looks planned)"),
    FAST("Fast: reached its peak within 5 hours"),
    GRADUAL("Gradual: built up over many hours (looks organic)"),
}

/**
 * How it started, from the hourly counts inside the range. Sudden when the hour before it was close to silent and it reached
 * 80% of the peak within 2 hours of reaching 10% of it; gradual when that took 5 hours or more.
 */
fun launchType(hours: List<HourBucket>): LaunchType? {
    val h = hours.sortedBy { it.startMs }
    val peak = h.maxOfOrNull { it.count }?.takeIf { it > 0 } ?: return null
    val first = h.indexOfFirst { it.count >= 0.10 * peak }
    val near = h.indexOfFirst { it.count >= 0.80 * peak }
    if (first < 0 || near < 0) return null
    val ramp = near - first
    val before = h.getOrNull(first - 1)?.count ?: 0L
    return when {
        ramp <= 2 && before <= 0.02 * peak -> LaunchType.SUDDEN
        ramp >= 5 -> LaunchType.GRADUAL
        else -> LaunchType.FAST
    }
}

/** Start times of push waves: an hour with at least 30% of the peak that is at least twice the hour before it. */
fun pushWaves(hours: List<HourBucket>): List<Long> {
    val h = hours.sortedBy { it.startMs }
    val peak = h.maxOfOrNull { it.count }?.takeIf { it > 0 } ?: return emptyList()
    return h.indices.filter { i ->
        h[i].count >= 0.30 * peak && (i == 0 || h[i - 1].count * 2 <= h[i].count)
    }.map { h[it].startMs }
}

// ---- spending the budget across the hours -----------------------------------------------------------------------------------

/** X's search takes 10 to 100 results per call. */
const val MIN_PER_CALL = 10
const val MAX_PER_CALL = 100

/** What X charged per post read when this was written; the first real run replaces it with the measured figure. */
const val DEFAULT_COST_PER_POST_USD = 0.005

/** One search call: the window it covers and how many posts to ask for. */
data class Slice(val startMs: Long, val endMs: Long, val posts: Int)

/** The calls a budget buys. [maxUsd] is the most they can cost at [costPerPost]; it is never above the budget. */
data class TrendPlan(val slices: List<Slice>, val postsPlanned: Int, val maxUsd: Double, val hoursCovered: List<Long>)

/**
 * Spreads [budgetUsd] over the busy hours of the range, in proportion to each hour's volume (so a peak hour gets the most),
 * with every busy hour guaranteed a few posts so the launch and the tail are never left out. Hours in [alreadyRead] are
 * skipped, so a second run only buys what the first did not cover. Neighbouring hours are merged into one call until it
 * asks for at least [MIN_PER_CALL] posts, and no call asks for more than [MAX_PER_CALL]. The total never passes the budget.
 * Null when the budget cannot buy a single call.
 */
fun planSlices(hours: List<HourBucket>, alreadyRead: Set<Long>, budgetUsd: Double, costPerPost: Double): TrendPlan? {
    val cost = costPerPost.takeIf { it > 0 } ?: return null
    val total = Math.floor(budgetUsd / cost + 1e-9).toInt()
    if (total < MIN_PER_CALL) return null
    val pool = hours.sortedBy { it.startMs }.filter { it.count > 0 && it.startMs !in alreadyRead }
    if (pool.isEmpty()) return TrendPlan(emptyList(), 0, 0.0, emptyList())
    val volume = pool.sumOf { it.count }
    val want = if (volume <= total) pool.map { it.count.toInt() } else {
        val minEach = max(1, min(3, total / pool.size))
        val raw = pool.map { max(minEach.toDouble(), total.toDouble() * it.count / volume) }
        val scale = total / raw.sum()
        // Scale down so the sum fits, then hand out what rounding left to the busiest hours.
        val floors = raw.mapIndexed { i, r -> min(pool[i].count.toInt(), max(1, Math.floor(r * min(1.0, scale)).toInt())) }.toMutableList()
        var left = total - floors.sum()
        for (i in pool.indices.sortedByDescending { pool[it].count }) {
            if (left <= 0) break
            if (floors[i] < pool[i].count) { floors[i]++; left-- }
        }
        // Rounding up the minimums can still pass the budget: trim from the busiest hours.
        var over = floors.sum() - total
        for (i in pool.indices.sortedByDescending { floors[it] }) {
            if (over <= 0) break
            val cut = min(over, max(0, floors[i] - 1))
            floors[i] -= cut; over -= cut
        }
        floors
    }
    // Merge neighbouring busy hours into calls of at least MIN_PER_CALL posts; a call never spans an hour that is skipped.
    val slices = mutableListOf<Slice>()
    var s = pool[0].startMs; var e = s + HOUR_MS; var n = want[0]
    for (i in 1 until pool.size) {
        val contiguous = pool[i].startMs == e
        if (!contiguous || (n >= MIN_PER_CALL && n + want[i] > MAX_PER_CALL)) {
            slices += Slice(s, e, n); s = pool[i].startMs; n = 0
        }
        e = pool[i].startMs + HOUR_MS
        n += want[i]
    }
    slices += Slice(s, e, n)
    // A call asks for at least MIN_PER_CALL (X's minimum), so a very small one is lifted to it.
    val fixed = slices.map { if (it.posts < MIN_PER_CALL) it.copy(posts = MIN_PER_CALL) else it }
    var planned = fixed.sumOf { it.posts }
    // Never past the budget: take posts off the largest calls first (a call stays at 10 or more).
    val out = fixed.toMutableList()
    while (planned > total) {
        val i = out.indices.maxByOrNull { out[it].posts } ?: break
        if (out[i].posts <= MIN_PER_CALL) { out.removeAt(i); planned = out.sumOf { it.posts }; continue }
        out[i] = out[i].copy(posts = max(MIN_PER_CALL, out[i].posts - (planned - total)))
        planned = out.sumOf { it.posts }
    }
    if (out.isEmpty()) return null
    val covered = pool.map { it.startMs }.filter { h -> out.any { h >= it.startMs && h < it.endMs } }
    return TrendPlan(out, planned, planned * cost, covered)
}

/** The next calls' posts, rounded the way the screen shows them. */
fun usdText(v: Double): String = "$" + "%.2f".format(v)

/** Share of [part] in [whole] as a whole percent; 0 when there is nothing. */
fun pct(part: Long, whole: Long): Int = if (whole <= 0) 0 else (100.0 * part / whole).roundToInt()

/** The exact shares from the counts of one range: nothing here is sampled. */
data class CountShares(val all: Long, val originals: Long, val replies: Long, val quotes: Long) {
    val retweets: Long get() = max(0L, all - originals)
    val retweetPct: Int get() = pct(retweets, all)
    val replyPct: Int get() = pct(replies, all)
    val quotePct: Int get() = pct(quotes, all)
    val originalPct: Int get() = pct(originals, all)
}
