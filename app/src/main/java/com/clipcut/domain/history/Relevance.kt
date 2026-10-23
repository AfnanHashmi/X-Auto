package com.clipcut.domain.history

import com.clipcut.domain.taglines.HOUR_MS
import com.clipcut.domain.taglines.TrendCheckResult
import java.time.DayOfWeek
import java.time.Instant
import java.time.ZoneId
import java.time.temporal.TemporalAdjusters
import kotlin.math.roundToInt

/** The ranking week starts every Friday at this hour (phone's time zone): the Top 5 is published Friday evening. */
const val WEEK_START_HOUR = 18

/** The start of the ranking week that [nowMs] falls in: the latest Friday 6 PM at or before it. */
fun weekStart(nowMs: Long, zone: ZoneId): Long {
    val now = Instant.ofEpochMilli(nowMs).atZone(zone)
    var start = now.with(TemporalAdjusters.previousOrSame(DayOfWeek.FRIDAY)).toLocalDate().atTime(WEEK_START_HOUR, 0).atZone(zone)
    if (start.isAfter(now)) start = start.minusWeeks(1)
    return start.toInstant().toEpochMilli()
}

/**
 * One contestant's week, from facts only. Tweets are counted once per tagline per hour even when several counts
 * covered the same hour (the latest count of that hour wins), and only hours inside the week count.
 */
data class ContestantWeek(
    val name: String,
    /** Tagline tweets this week, retweets included, and the original (non-retweet) part. */
    val tweets: Long,
    val originals: Long,
    /** Different taglines run for them this week. */
    val taglines: Int,
    /** Counts in which their tagline came first. */
    val taglineWins: Int,
    /** Their busiest single tagline hour. */
    val peakHour: Long,
    /** Average share of hashtag votes over the Boss Meter posts they were in (latest run of each post), and how many. */
    val bossShare: Double?,
    val bossPosts: Int,
    /** Boss Meter final results they won. */
    val bossWins: Int,
    /** Best trending position typed when sharing (1 is best), if any. */
    val bestTrending: Int?,
    /**
     * Tagline tweets this week that look natural (tweets x the natural share of the Trend check made for that tagline), or
     * null when none of their taglines had a Trend check with a sample. Missing is not zero.
     */
    val naturalTweets: Long? = null,
    /** How the sampled posts about them felt, from -1 (all negative) to +1 (all positive), and how many posts it rests on; null: too few. */
    val mood: Double? = null,
    val moodPosts: Int = 0,
)

/** How much each fact weighs in the score. Each fact is scored against the week's best contestant for it (0 to 1). */
val RELEVANCE_WEIGHTS = linkedMapOf(
    "Tagline tweets" to 0.17,
    "Original tweets" to 0.09,
    "Tagline wins" to 0.09,
    "Peak hour" to 0.05,
    "Boss Meter share" to 0.23,
    "Boss Meter wins" to 0.09,
    "Trending" to 0.11,
    "Natural support" to 0.11,
    "Positive mood" to 0.06,
)

/** Facts that only exist for contestants whose taglines had a Trend check. Without one, a contestant is scored on the rest, not given 0. */
private val CHECK_FACTS = setOf("Natural support", "Positive mood")

/** At least this many sampled posts about a contestant before their mood counts. */
const val MIN_MOOD_POSTS = 8

/** A contestant's score (0 to 100) with the facts that justify it. */
data class Relevance(val week: ContestantWeek, val score: Int, val parts: Map<String, Double>) {
    /** One plain line of facts, fit for the website (under 200 characters). */
    val factsLine: String
        get() = listOfNotNull(
            if (week.tweets > 0) "${short(week.tweets)} tagline tweets (${short(week.originals)} original)" else null,
            if (week.taglineWins > 0) "${week.taglineWins} tagline ${if (week.taglineWins == 1) "win" else "wins"}" else null,
            week.bossShare?.let { "${"%.0f".format(it)}% Boss Meter votes" },
            if (week.bossWins > 0) "${week.bossWins} Boss Meter ${if (week.bossWins == 1) "win" else "wins"}" else null,
            week.naturalTweets?.takeIf { week.tweets > 0 }?.let { "~${(100.0 * it / week.tweets).roundToInt().coerceIn(0, 100)}% natural" },
            week.mood?.let { "mood ${if (it >= 0) "+" else ""}${(it * 100).roundToInt()}" },
            week.bestTrending?.let { "best #$it Trending" },
        ).joinToString(" · ").take(200)
}

private fun short(v: Long): String = when {
    v >= 1_000_000 -> "%.1fM".format(v / 1_000_000.0)
    v >= 10_000 -> "${(v / 1000.0).roundToInt()}K"
    v >= 1_000 -> "%.1fK".format(v / 1000.0)
    else -> "%,d".format(v)
}

/**
 * Builds each contestant's week from the history: tagline counts, Boss Meter runs and trending positions between
 * [sinceMs] and [untilMs]. [contestantOf] links a tagline or hashtag to a contestant (null: not counted for anyone).
 */
fun weekStats(
    history: RunHistory,
    contestantOf: (String) -> String?,
    sinceMs: Long,
    untilMs: Long,
    /** Trend checks made in the week (any of them): their natural share and the mood of the sampled posts feed the score. */
    checks: List<TrendCheckResult> = emptyList(),
): List<ContestantWeek> {
    // Tagline counts: one value per tagline per hour, from the newest count that covered that hour.
    data class Hour(val all: Long, val originals: Long, val fetched: Long)
    val hours = HashMap<String, HashMap<Long, Hour>>() // tagline key -> hour start -> value
    val wins = HashMap<String, Int>()
    val taglineName = HashMap<String, String>()
    val runs = history.taglineRuns(0).filter { it.fetchedAtMs >= sinceMs && it.fetchedAtMs < untilMs + HOUR_MS * 24 }
    for (r in runs) {
        for (c in r.counts) {
            val key = contestantKey(c.tagline)
            taglineName[key] = c.tagline
            val originalsByHour = c.originals.hours.associate { Math.floorDiv(it.startMs, HOUR_MS) * HOUR_MS to it.count }
            for (b in c.all.hours) {
                val h = Math.floorDiv(b.startMs, HOUR_MS) * HOUR_MS
                if (h < sinceMs || h >= untilMs) continue
                val m = hours.getOrPut(key) { HashMap() }
                val prev = m[h]
                // The newest count wins; between equally new ones, the larger (a full hour beats a partial edge hour).
                if (prev == null || r.fetchedAtMs > prev.fetched || (r.fetchedAtMs == prev.fetched && b.count > prev.all)) {
                    m[h] = Hour(b.count, originalsByHour[h] ?: 0L, r.fetchedAtMs)
                }
            }
        }
        if (r.fetchedAtMs in sinceMs until untilMs) {
            r.counts.filter { it.all.total > 0 }.maxByOrNull { it.all.total }?.let { top ->
                if (r.counts.count { it.all.total == top.all.total } == 1) contestantOf(top.tagline)?.let { n -> wins[n.lowercase()] = (wins[n.lowercase()] ?: 0) + 1 }
            }
        }
    }

    // Boss Meter: the latest run of each post in the week; finals give a win to the highest count.
    val boss = history.bossRuns(sinceMs).filter { it.result.fetchedAtMs < untilMs }
    val shares = HashMap<String, MutableList<Double>>()
    val bossWins = HashMap<String, Int>()
    boss.groupBy { it.result.conversationId }.values.map { runsOfPost -> runsOfPost.maxBy { it.result.fetchedAtMs } }.forEach { run ->
        val r = run.result
        r.tags.forEach { t -> contestantOf(t.tag)?.let { n -> shares.getOrPut(n.lowercase()) { mutableListOf() } += r.percent(t) } }
    }
    boss.filter { it.final }.groupBy { it.result.conversationId }.values.map { it.maxBy { r -> r.result.fetchedAtMs } }.forEach { run ->
        val ranked = run.result.ranked
        if (ranked.isNotEmpty() && (ranked.size == 1 || ranked[0].count > ranked[1].count)) {
            contestantOf(ranked[0].tag)?.let { n -> bossWins[n.lowercase()] = (bossWins[n.lowercase()] ?: 0) + 1 }
        }
    }

    // Trending positions typed when sharing.
    val trending = HashMap<String, Int>()
    history.trending(sinceMs).filter { it.atMs < untilMs }.forEach { m ->
        contestantOf(m.tagline)?.let { n -> trending[n.lowercase()] = minOf(trending[n.lowercase()] ?: Int.MAX_VALUE, m.position) }
    }

    // Put it together per contestant (names compared without case; the first spelling seen is shown).
    val display = HashMap<String, String>()
    val tweets = HashMap<String, Long>()
    val originals = HashMap<String, Long>()
    val taglineCount = HashMap<String, Int>()
    val peak = HashMap<String, Long>()
    hours.forEach { (key, byHour) ->
        val n = contestantOf(taglineName.getValue(key)) ?: return@forEach
        val id = n.lowercase()
        display.putIfAbsent(id, n)
        val total = byHour.values.sumOf { it.all }
        if (total == 0L) return@forEach
        tweets[id] = (tweets[id] ?: 0) + total
        originals[id] = (originals[id] ?: 0) + byHour.values.sumOf { it.originals }
        taglineCount[id] = (taglineCount[id] ?: 0) + 1
        peak[id] = maxOf(peak[id] ?: 0, byHour.values.maxOf { it.all })
    }
    boss.flatMap { it.result.tags }.forEach { t -> contestantOf(t.tag)?.let { display.putIfAbsent(it.lowercase(), it) } }
    history.trending(sinceMs).forEach { m -> contestantOf(m.tagline)?.let { display.putIfAbsent(it.lowercase(), it) } }

    // Trend checks: natural tweets per contestant, and how the sampled posts about each contestant felt.
    val natural = HashMap<String, Long>()
    val moodPos = HashMap<String, Int>()
    val moodNeg = HashMap<String, Int>()
    val moodAll = HashMap<String, Int>()
    for (c in checks.filter { it.atMs in sinceMs until untilMs }) {
        val st = c.stats
        val owner = contestantOf(c.tagline)
        if (st != null && owner != null) {
            natural[owner.lowercase()] = (natural[owner.lowercase()] ?: 0) + Math.round(c.shares.all * st.naturalPct / 100.0)
            display.putIfAbsent(owner.lowercase(), owner)
        }
        c.sentiment?.byContestant?.forEach { (who, t) ->
            val k = who.lowercase()
            moodPos[k] = (moodPos[k] ?: 0) + t.first
            moodNeg[k] = (moodNeg[k] ?: 0) + t.second
            moodAll[k] = (moodAll[k] ?: 0) + t.first + t.second + t.third
        }
    }

    return display.map { (id, name) ->
        ContestantWeek(
            name = name,
            tweets = tweets[id] ?: 0,
            originals = originals[id] ?: 0,
            taglines = taglineCount[id] ?: 0,
            taglineWins = wins[id] ?: 0,
            peakHour = peak[id] ?: 0,
            bossShare = shares[id]?.average(),
            bossPosts = shares[id]?.size ?: 0,
            bossWins = bossWins[id] ?: 0,
            bestTrending = trending[id],
            naturalTweets = natural[id],
            mood = moodAll[id]?.takeIf { it >= MIN_MOOD_POSTS }?.let { n -> ((moodPos[id] ?: 0) - (moodNeg[id] ?: 0)).toDouble() / n },
            moodPosts = moodAll[id] ?: 0,
        )
    }
}

/**
 * Scores each contestant 0 to 100 from their week: every fact is measured against the week's best for that fact and
 * weighted by [RELEVANCE_WEIGHTS]. A fact nobody has this week (for example no Boss Meter runs) is left out and the
 * other weights are scaled up, so the score always uses the full 0 to 100. Highest first; equal scores keep name order.
 */
fun relevance(weeks: List<ContestantWeek>): List<Relevance> {
    fun ratio(v: Double, best: Double) = if (best <= 0.0) 0.0 else v / best
    // A null value means "not measured for this contestant" (only the Trend check facts can be null).
    val values: Map<String, (ContestantWeek) -> Double?> = mapOf(
        "Tagline tweets" to { w -> w.tweets.toDouble() },
        "Original tweets" to { w -> w.originals.toDouble() },
        "Tagline wins" to { w -> w.taglineWins.toDouble() },
        "Peak hour" to { w -> w.peakHour.toDouble() },
        "Boss Meter share" to { w -> w.bossShare ?: 0.0 },
        "Boss Meter wins" to { w -> w.bossWins.toDouble() },
        // Trending #1 scores 1.0, #50 about 0.02; none scores 0.
        "Trending" to { w -> w.bestTrending?.let { (51.0 - it.coerceIn(1, 50)) / 50.0 } ?: 0.0 },
        "Natural support" to { w -> w.naturalTweets?.toDouble() },
        // -1..+1 mapped to 0..1, so an all-negative contestant scores 0 and an all-positive one 1.
        "Positive mood" to { w -> w.mood?.let { (it + 1.0) / 2.0 } },
    )
    val best = values.mapValues { (_, f) -> weeks.maxOfOrNull { w -> f(w) ?: 0.0 } ?: 0.0 }
    val used = RELEVANCE_WEIGHTS.filterKeys { (best[it] ?: 0.0) > 0.0 }
    return weeks.map { w ->
        // Each contestant's weights are scaled to fill 0 to 100 over the facts that were measured for them.
        val mine = used.filterKeys { k -> k !in CHECK_FACTS || values.getValue(k)(w) != null }
        val totalWeight = mine.values.sum()
        val parts = mine.mapValues { (k, weight) -> 100.0 * weight / totalWeight * ratio(values.getValue(k)(w) ?: 0.0, best.getValue(k)) }
        Relevance(w, parts.values.sum().roundToInt().coerceIn(0, 100), parts)
    }.sortedWith(compareByDescending<Relevance> { it.score }.thenBy { it.week.name.lowercase() })
}
