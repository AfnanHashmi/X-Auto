package com.clipcut.domain.share

import com.clipcut.domain.taglines.CountReport
import com.clipcut.domain.taglines.TrendCheckResult
import com.clipcut.domain.taglines.trendCheckLines
import com.clipcut.domain.taglines.TaglineAnalysis
import com.clipcut.domain.taglines.GridException
import com.clipcut.domain.taglines.HOUR_MS
import com.clipcut.domain.taglines.HourlyGrid
import com.clipcut.domain.taglines.TaglineCount
import com.clipcut.domain.taglines.clockText
import com.clipcut.domain.taglines.hourlyGrid
import java.time.ZoneId
import kotlin.math.roundToLong

/** Trending positions the user may type in before sharing: 1 to [MAX_TRENDING_POSITION]. */
const val MAX_TRENDING_POSITION = 50

/** What the user typed for a trending position: blank is "not given" (null); "3" or "#3" is 3. */
sealed interface PositionInput {
    data class Ok(val position: Int?) : PositionInput
    data class Invalid(val reason: String) : PositionInput
}

fun parsePosition(raw: String): PositionInput {
    val t = raw.trim().removePrefix("#").trim()
    if (t.isEmpty()) return PositionInput.Ok(null)
    val n = t.toIntOrNull() ?: return PositionInput.Invalid("Type a whole number, like 3.")
    if (n < 1 || n > MAX_TRENDING_POSITION) return PositionInput.Invalid("Use 1 to $MAX_TRENDING_POSITION.")
    return PositionInput.Ok(n)
}

fun trendingText(position: Int) = "#$position Trending on X"

/** The busiest hour: its clock times (a partial first or last hour shows its real edges) and the tweets per minute in it. */
data class PeakInfo(val startMs: Long, val endMs: Long, val count: Long, val perMinute: Long)

/** A total the tagline passed ([tweets]) and how long after its first hour began it got there, to the end of that hour. */
data class Milestone(val tweets: Long, val withinMs: Long)

/** The last complete clock hour of the window and how it compares: the peak itself, rising on the hour before, or below the peak. */
data class LastHour(val count: Long, val previous: Long?, val isPeak: Boolean, val belowPeakPercent: Int?)

/** The round totals worth calling out. */
val MILESTONES = listOf(1_000L, 10_000L, 50_000L, 100_000L, 500_000L, 1_000_000L)

/** "Hours above" this many tweets: a sustained-volume figure. */
const val BUSY_HOUR_TWEETS = 1_000L

/**
 * One tagline on the share image. [colorIndex] is its place in the report (the graph line has the same colour).
 * [rank], [sharePercent] and [fraction] are only set when there are two or more taglines to compare.
 */
data class TaglineStat(
    val tagline: String,
    val colorIndex: Int,
    val total: Long,
    val originals: Long,
    val retweets: Long,
    val retweetPercent: Int?,
    val peak: PeakInfo?,
    val firstSeenStartMs: Long?,
    val firstSeenEndMs: Long?,
    val activeHours: Int?,
    /** Tweets per minute across the active span (first to last hour with tweets), rounded. */
    val averagePerMinute: Long?,
    /** Retweets for every original tweet; null without originals. */
    val retweetsPerOriginal: Double?,
    /** The milestones reached, in order. */
    val milestones: List<Milestone>,
    /** Hours with at least [BUSY_HOUR_TWEETS] tweets. */
    val busyHours: Int,
    val lastHour: LastHour?,
    /** Share of the total that came in the three busiest hours; only when it was active for more than three hours. */
    val topThreeHoursPercent: Int?,
    val rank: Int?,
    val sharePercent: Double?,
    val fraction: Float?,
    val trending: Int?,
    /** How far ahead of the next tagline down (several taglines only, and only when actually ahead). */
    val leadOverNext: Long? = null,
    val nextRank: Int? = null,
    /** From a Deep look, when one was run for this tagline: what the posts say, and who drove them. Grok's sample, not counts. */
    val themes: List<String> = emptyList(),
    val voices: List<String> = emptyList(),
    val samplePosts: Int? = null,
) {
    val hasLook: Boolean get() = themes.isNotEmpty() || voices.isNotEmpty()

    val valueText: String get() = "${n(total)} tweets"
    val rankText: String? get() = rank?.let { "#$it" }
    val shareText: String?
        get() = listOfNotNull(
            sharePercent?.let { if (it > 0 && it < 1) "<1% of all" else "${"%.0f".format(it)}% of all" },
            leadOverNext?.let { lead -> "leads #$nextRank by ${n(lead)}" },
        ).joinToString("  ·  ").ifEmpty { null }
    val trendingText: String? get() = trending?.let(::trendingText)

    /** The small lines under the name, already in words. A tagline with no tweets gets one line saying so. */
    fun detailLines(zone: ZoneId): List<String> {
        if (total == 0L) return listOf("No tweets in this period")
        val lines = mutableListOf(
            listOfNotNull(
                "${n(originals)} original",
                "${n(retweets)} retweets (${retweetPercent ?: 0}%)",
                retweetsPerOriginal?.let { "${"%.1f".format(it)} retweets per original" },
            ).joinToString("  ·  "),
        )
        peak?.let { lines += "Busiest ${clockText(it.startMs, zone)}–${clockText(it.endMs, zone)}: ${n(it.count)} tweets (${perMinuteText(it.perMinute)})" }
        val when_ = listOfNotNull(
            if (firstSeenStartMs != null && firstSeenEndMs != null) "First seen ${clockText(firstSeenStartMs, zone)}–${clockText(firstSeenEndMs, zone)}" else null,
            activeHours?.let { "active over ${if (it == 1) "1 hour" else "$it hours"}" },
            averagePerMinute?.let { "average ${perMinuteText(it).removePrefix("about ")}" },
        )
        if (when_.isNotEmpty()) lines += when_.joinToString("  ·  ")
        val pace = milestones.groupBy { it.withinMs }.map { (_, same) -> same.maxBy { it.tweets } }.takeLast(2).map { "${short(it.tweets)} within ${durationText(it.withinMs)}" } +
            listOfNotNull(if (busyHours > 0) "$busyHours ${if (busyHours == 1) "hour" else "hours"} above ${short(BUSY_HOUR_TWEETS)}/hour" else null)
        if (pace.isNotEmpty()) lines += pace.joinToString("  ·  ")
        val now = listOfNotNull(
            lastHour?.let { h ->
                when {
                    h.isPeak -> "Last full hour was its busiest (${n(h.count)})"
                    h.count == 0L -> "Last full hour: 0 (gone quiet)"
                    h.previous != null && h.count > h.previous -> "Last full hour: ${n(h.count)} (↑ from ${n(h.previous)})"
                    else -> "Last full hour: ${n(h.count)} (↓ ${h.belowPeakPercent ?: 0}% from peak)"
                }
            },
            topThreeHoursPercent?.let { "3 busiest hours: $it% of tweets" },
        )
        if (now.isNotEmpty()) lines += now.joinToString("  ·  ")
        return lines
    }
}

/** 1,000 → 1K, 50,000 → 50K, 1,000,000 → 1M. */
private fun short(v: Long) = when {
    v >= 1_000_000 && v % 1_000_000 == 0L -> "${v / 1_000_000}M"
    v >= 1_000 && v % 1_000 == 0L -> "${v / 1_000}K"
    else -> n(v)
}

private fun durationText(ms: Long): String {
    val minutes = (ms + 59_999L) / 60_000L
    val h = minutes / 60
    val m = minutes % 60
    return when {
        h == 0L -> "$m min"
        m == 0L -> "$h h"
        else -> "$h h $m min"
    }
}

private fun perMinuteText(v: Long) = if (v < 1) "under 1 a minute" else "about ${n(v)} a minute"

/** Everything on the Tagline Counter share image, worked out from the count already on screen. Costs nothing. */
data class TaglineShareCard(
    val title: String,
    val subtitle: String,
    /** Highest first; for several taglines, in rank order. */
    val stats: List<TaglineStat>,
    /** Null when the hours cannot be laid out or there are no tweets at all: the image then has no graph. */
    val graph: HourlyGrid?,
    val footer: String,
    val caption: String,
    /** The AI read-out, when one was written: headline first, then up to three findings. Empty when there is none. */
    val readout: List<String> = emptyList(),
    /** Who wrote the AI parts, for the small print ("AI notes: xAI · grok-4.3"). */
    val aiNote: String? = null,
) {
    val compares: Boolean get() = stats.size >= 2
}

private fun n(v: Long) = "%,d".format(v)

/** Real edges of the hour that [hourStart] falls in, clipped to the counted window (the first and last hours are partial). */
private fun bucketEdges(r: CountReport, startMs: Long): Pair<Long, Long> {
    val hourStart = Math.floorDiv(startMs, HOUR_MS) * HOUR_MS
    return maxOf(hourStart, r.startMs) to minOf(hourStart + HOUR_MS, r.endMs)
}

/** One tagline's figures, as the share image and the screen show them. */
fun taglineStat(r: CountReport, c: TaglineCount, index: Int = 0, trending: Int? = null): TaglineStat {
    val total = c.all.total
    // X's no-retweets total can never really exceed the full total; clamp so a mismatch can't print a negative number.
    val originals = c.originals.total.coerceIn(0, total)
    val peak = c.peak?.let { b ->
        val (s, e) = bucketEdges(r, b.startMs)
        val minutes = ((e - s) / 60_000.0).coerceAtLeast(1.0)
        PeakInfo(s, e, b.count, (b.count / minutes).roundToLong())
    }
    val active = c.all.hours.filter { it.count > 0 }
    val firstEdges = active.firstOrNull()?.let { bucketEdges(r, it.startMs) }
    val lastEnd = active.lastOrNull()?.let { bucketEdges(r, it.startMs).second }
    val activeMs = if (firstEdges != null && lastEnd != null) lastEnd - firstEdges.first else null
    val activeHours = activeMs?.let { (it.toDouble() / HOUR_MS).roundToLong().coerceAtLeast(1).toInt() }
    val averagePerMinute = activeMs?.let { (total / (it / 60_000.0).coerceAtLeast(1.0)).roundToLong() }

    // Milestones: walk the hours from the first one with tweets; a milestone counts as reached by the end of its hour.
    val milestones = mutableListOf<Milestone>()
    if (firstEdges != null) {
        var running = 0L
        for (b in c.all.hours.sortedBy { it.startMs }) {
            if (b.startMs < active.first().startMs) continue
            running += b.count
            val end = bucketEdges(r, b.startMs).second
            MILESTONES.filter { it in (running - b.count + 1)..running }.forEach { milestones += Milestone(it, end - firstEdges.first) }
        }
    }

    // The last complete clock hour (the final bucket is usually a part hour).
    val full = c.all.hours.sortedBy { it.startMs }.filter { b -> bucketEdges(r, b.startMs).let { (s, e) -> e - s == HOUR_MS } }
    val lastHour = if (total > 0 && peak != null) full.lastOrNull()?.let { last ->
        val before = c.all.hours.sortedBy { it.startMs }.lastOrNull { it.startMs < last.startMs }
        LastHour(
            count = last.count,
            previous = before?.count,
            isPeak = last.startMs == c.peak?.startMs,
            belowPeakPercent = if (peak.count > 0) ((1 - last.count.toDouble() / peak.count) * 100).roundToLong().toInt() else null,
        )
    } else null

    val nonEmptyHours = c.all.hours.count { it.count > 0 }
    val topThree = if (total > 0 && nonEmptyHours > 3) (c.all.hours.map { it.count }.sortedDescending().take(3).sum() * 100.0 / total).roundToLong().toInt() else null
    return TaglineStat(
        tagline = c.tagline,
        colorIndex = index,
        total = total,
        originals = originals,
        retweets = total - originals,
        retweetPercent = if (total > 0) ((total - originals) * 100.0 / total).roundToLong().toInt() else null,
        peak = peak,
        firstSeenStartMs = firstEdges?.first,
        firstSeenEndMs = firstEdges?.second,
        activeHours = activeHours,
        averagePerMinute = averagePerMinute,
        retweetsPerOriginal = if (originals > 0) (total - originals).toDouble() / originals else null,
        milestones = milestones,
        busyHours = c.all.hours.count { it.count >= BUSY_HOUR_TWEETS },
        lastHour = lastHour,
        topThreeHoursPercent = topThree,
        rank = null,
        sharePercent = null,
        fraction = null,
        trending = trending,
    )
}

/**
 * Builds the share image's content and the post text. [positions] maps a tagline to the trending position the user typed
 * (optional; only what they typed is shown). Rank, share of the total and the comparison bars appear only when there are
 * two or more taglines; equal totals share a rank.
 */
fun taglineShareCard(
    r: CountReport,
    dateText: String,
    zone: ZoneId,
    positions: Map<String, Int> = emptyMap(),
    checks: Map<String, TrendCheckResult> = emptyMap(),
    readout: TaglineAnalysis? = null,
    stage: ShareStage = ShareStage.LIVE,
    longPost: Boolean = false,
): TaglineShareCard {
    var stats = r.counts.mapIndexed { i, c ->
        val check = checks[c.tagline]
        // Accounts found by a Trend check are never tagged in a public post, so no voices go along.
        taglineStat(r, c, i, positions[c.tagline]).copy(
            themes = check?.let(::trendCheckLines).orEmpty(),
            samplePosts = check?.stats?.sample,
        )
    }
    val compares = stats.size >= 2
    if (compares) {
        val combined = stats.sumOf { it.total }
        val top = stats.maxOf { it.total }
        stats = stats.sortedByDescending { it.total }.let { sorted ->
            sorted.mapIndexed { i, s ->
                val next = sorted.getOrNull(i + 1)
                s.copy(
                    leadOverNext = next?.takeIf { it.total > 0 }?.let { s.total - it.total }?.takeIf { it > 0 }, // never "leads" a tagline with no tweets
                    nextRank = next?.let { nx -> 1 + sorted.count { it.total > nx.total } },
                    rank = 1 + sorted.count { it.total > s.total },
                    sharePercent = if (combined > 0) s.total * 100.0 / combined else null,
                    fraction = if (top > 0) s.total.toFloat() / top else 0f,
                )
            }
        }
    }
    val graph = try {
        hourlyGrid(r).takeIf { g -> g.hours >= 2 && g.series.any { s -> s.any { it > 0 } } }
    } catch (e: GridException) {
        null
    }
    val combined = r.combinedAll

    val aiLabels = listOfNotNull(readout?.label) + checks.values.mapNotNull { it.sentiment?.label }
    val caption = taglineCaption(stats, combined, stage, dateText, readout, aiLabels, longPost)
    return TaglineShareCard(
        title = "TAGLINE COUNTER",
        subtitle = "${stage.imageTag}  ·  Tweets on X in the last 24 hours",
        stats = stats,
        graph = graph,
        footer = listOfNotNull(
            if (compares) "${n(combined)} tweets across ${stats.size} taglines" else null,
            "Counted with the X API",
            dateText,
        ).joinToString("  ·  "),
        caption = caption,
        readout = readout?.let { listOf(it.headline) + it.insights.take(3) }.orEmpty(),
        aiNote = listOfNotNull(readout?.label, checks.values.firstNotNullOfOrNull { it.sentiment?.label }).distinct().takeIf { it.isNotEmpty() }
            ?.let { "AI notes by ${it.joinToString(", ")}: from the counts and a sample of posts; check before relying on them" },
    )
}

