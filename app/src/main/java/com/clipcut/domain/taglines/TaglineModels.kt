package com.clipcut.domain.taglines

import org.json.JSONException
import org.json.JSONObject
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

/** A reply from X that cannot be trusted: wrong shape, or hourly numbers that do not add up to X's own total. */
class TaglineParseException(message: String) : Exception(message)

/** One hour of tweets. [startMs] is when X's bucket starts (X cuts buckets on the UTC hour; the first and last can be partial). */
data class HourBucket(val startMs: Long, val count: Long)

/** The result of one counts call: X's total and its hourly buckets, which always add up to that total. */
data class CountSeries(val total: Long, val hours: List<HourBucket>)

data class TaglineCount(
    val tagline: String,
    /** Every tweet that has the phrase, retweets and quotes included. */
    val all: CountSeries,
    /** The same phrase, retweets left out. */
    val originals: CountSeries,
) {
    val retweetShare: Double get() = if (all.total == 0L) 0.0 else 1.0 - originals.total.toDouble() / all.total
    val firstActive: HourBucket? get() = all.hours.firstOrNull { it.count > 0 }
    val peak: HourBucket? get() = all.hours.maxByOrNull { it.count }?.takeIf { it.count > 0 }
}

data class CountReport(val startMs: Long, val endMs: Long, val fetchedAtMs: Long, val counts: List<TaglineCount>) {
    val combinedAll: Long get() = counts.sumOf { it.all.total }
    val combinedOriginals: Long get() = counts.sumOf { it.originals.total }
}

/** What X bills per call to the tweet-counts endpoint (pay-per-use price list). */
const val COUNTS_CALL_USD = 0.005

/** A tagline is counted with two calls: everything, and without retweets. */
const val CALLS_PER_TAGLINE = 2

const val MAX_TAGLINES = 10
const val MAX_TAGLINE_LENGTH = 120

fun estimatedCostUsd(taglineCount: Int): Double = taglineCount * CALLS_PER_TAGLINE * COUNTS_CALL_USD

/** Trims, collapses spaces and drops quotes (they would break the exact-phrase search). Blank becomes null. */
fun cleanTagline(raw: String): String? =
    raw.replace("\"", "").replace(Regex("\\s+"), " ").trim().take(MAX_TAGLINE_LENGTH).takeIf { it.isNotBlank() }

/** The X search query for an exact phrase, with or without retweets. */
fun phraseQuery(tagline: String, withRetweets: Boolean): String = "\"$tagline\"" + if (withRetweets) "" else " -is:retweet"

/**
 * Reads one `GET /2/tweets/counts/recent` reply. Strict: the hourly numbers must add up to X's `total_tweet_count`,
 * otherwise the figures are refused rather than shown.
 */
fun parseCounts(body: String): CountSeries {
    val root = try {
        JSONObject(body)
    } catch (e: JSONException) {
        throw TaglineParseException("X sent a reply that is not valid JSON")
    }
    val meta = root.optJSONObject("meta") ?: throw TaglineParseException("X's reply has no totals")
    if (!meta.has("total_tweet_count")) throw TaglineParseException("X's reply has no total")
    val total = meta.optLong("total_tweet_count", -1)
    if (total < 0) throw TaglineParseException("X's total is not a number")
    // With no matching tweets X may leave `data` out; that is a valid zero.
    val data = root.optJSONArray("data")
    val hours = List(data?.length() ?: 0) { i ->
        val o = data!!.optJSONObject(i) ?: throw TaglineParseException("An hourly row is not an object")
        val start = runCatching { Instant.parse(o.getString("start")).toEpochMilli() }.getOrElse { throw TaglineParseException("An hourly row has no valid start time") }
        if (!o.has("tweet_count")) throw TaglineParseException("An hourly row has no count")
        HourBucket(start, o.optLong("tweet_count"))
    }
    val sum = hours.sumOf { it.count }
    if (sum != total) throw TaglineParseException("The hourly numbers ($sum) do not add up to X's total ($total)")
    return CountSeries(total, hours.sortedBy { it.startMs })
}

// ---- what the numbers show -----------------------------------------------------------------------------------------------

private val CLOCK = DateTimeFormatter.ofPattern("h:mm a", Locale.ENGLISH)
private val DATE_TIME = DateTimeFormatter.ofPattern("d MMM yyyy, h:mm a", Locale.ENGLISH)

fun clockText(ms: Long, zone: ZoneId): String = Instant.ofEpochMilli(ms).atZone(zone).format(CLOCK).uppercase(Locale.ENGLISH)

fun dateTimeText(ms: Long, zone: ZoneId): String =
    Instant.ofEpochMilli(ms).atZone(zone).format(DATE_TIME).replace(" am", " AM").replace(" pm", " PM").replace(" a.m.", " AM").replace(" p.m.", " PM")

fun zoneLabel(zone: ZoneId, ms: Long): String =
    java.util.TimeZone.getTimeZone(zone).getDisplayName(java.util.TimeZone.getTimeZone(zone).inDaylightTime(java.util.Date(ms)), java.util.TimeZone.SHORT, Locale.ENGLISH)

/**
 * Plain statements that the counts themselves support. It never claims a cause: the counts show when and how many, not
 * who started it or why.
 */
fun insightLines(report: CountReport, zone: ZoneId): List<String> {
    val counts = report.counts.filter { it.all.total > 0 }
    if (counts.isEmpty()) return listOf("No tweets with these phrases were found in this period.")
    val lines = mutableListOf<String>()
    val firstStart = counts.mapNotNull { it.firstActive?.startMs }.min()
    val first = counts.first { it.firstActive?.startMs == firstStart }
    val quiet = firstStart - report.startMs
    lines += if (quiet >= 3 * 3_600_000L) {
        "A sudden start: no tweets with these phrases until about ${clockText(firstStart, zone)}. " +
            "\"${first.tagline}\" came first (${first.all.hours.first { it.startMs == firstStart }.count} in its first hour)."
    } else {
        "Already active at the start of the period; the earliest phrase was \"${first.tagline}\"."
    }
    val hourlyTotals = counts.flatMap { it.all.hours }.groupBy { it.startMs }.mapValues { (_, v) -> v.sumOf { it.count } }
    val peak = hourlyTotals.maxByOrNull { it.value }!!
    lines += "Combined volume peaked at ${clockText(peak.key, zone)} to ${clockText(peak.key + 3_600_000L, zone)} with ${"%,d".format(peak.value)} tweets in one hour."
    val shares = counts.map { c -> 100.0 * c.all.hours.sortedByDescending { it.count }.take(3).sumOf { it.count } / c.all.total }
    lines += "Volume was concentrated: each phrase's three busiest hours carried ${shares.joinToString(", ") { "%.0f%%".format(it) }} of its 24-hour total (in table order). A burst this fast is what lifts a phrase up the trending list."
    val rtShare = if (report.combinedAll == 0L) 0.0 else 100.0 * (1.0 - report.combinedOriginals.toDouble() / report.combinedAll)
    lines += "Mostly retweets: about ${"%.0f".format(rtShare)}% of the volume. ${"%,d".format(report.combinedOriginals)} original tweets were amplified by ${"%,d".format(report.combinedAll - report.combinedOriginals)} retweets."
    if (counts.size > 1) {
        val peakStarts = counts.mapNotNull { it.peak?.startMs }
        val together = peakStarts.max() - peakStarts.min() <= 3_600_000L
        lines += if (together) "The phrases moved together: their busiest hours were within an hour of each other." else "The phrases peaked at different times."
    }
    return lines
}

const val COUNTS_LIMITS_NOTE =
    "The counts show when and how much people tweeted; they do not show who started it or why, so the points above describe the pattern, not a verified cause. " +
        "Trending positions were not queried. The first and last hourly buckets are partial hours."
