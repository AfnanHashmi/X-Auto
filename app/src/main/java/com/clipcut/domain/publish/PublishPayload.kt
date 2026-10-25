package com.clipcut.domain.publish

import com.clipcut.domain.taglines.CountReport
import com.clipcut.domain.taglines.GridException
import com.clipcut.domain.taglines.HOUR_MS
import com.clipcut.domain.taglines.hourlyGrid
import com.clipcut.domain.taglines.insightLines
import com.clipcut.domain.taglines.TrendCheckResult
import com.clipcut.domain.taglines.TaglineAnalysis
import com.clipcut.domain.share.taglineStat
import java.time.ZoneId
import org.json.JSONArray
import org.json.JSONObject
import java.time.Instant
import java.time.temporal.ChronoUnit

/** The website refuses this payload, or it cannot be built from what the app has. Nothing is sent. */
class PayloadException(message: String) : Exception(message)

enum class MeterKind(val wire: String, val label: String) {
    TREND("trend", "Tagline Counter"),
}

/** The website's limits on insight lines: at most this many per list, each 1 to [MAX_INSIGHT] characters. */
const val MAX_INSIGHTS = 5
const val MAX_INSIGHT = 200

/**
 * Lines fit for the website: trimmed, blanks and repeats dropped, at most [MAX_INSIGHTS]. A line over [MAX_INSIGHT]
 * keeps its first sentence if that fits, otherwise it is cut at a word and ends with "…". Null when nothing is left,
 * so no empty array is ever sent.
 */
fun cleanInsights(lines: List<String>): List<String>? = lines.asSequence()
    .map { it.replace(Regex("\\s+"), " ").trim() }
    .filter { it.isNotEmpty() }
    .map(::fitInsight)
    .distinct()
    .take(MAX_INSIGHTS)
    .toList()
    .takeIf { it.isNotEmpty() }

private fun fitInsight(line: String): String {
    if (line.length <= MAX_INSIGHT) return line
    val sentence = line.substringBefore(". ", line).let { if (it.length < line.length) "$it." else it }
    if (sentence.length <= MAX_INSIGHT) return sentence
    val cut = line.take(MAX_INSIGHT - 1).substringBeforeLast(' ').trimEnd(',', ';', ':', ' ')
    return "$cut…"
}

/** A ready-to-send body. [json] is exactly what is POSTed, and exactly what Retry resends. */
data class PublishPayload(val kind: MeterKind, val json: String)

const val MIN_ITEMS = 1
const val MAX_ITEMS = 50
const val MAX_LABEL = 120
const val MAX_HOURLY = 48

/**
 * [hourly] is the tweets in each hour, oldest first (Tagline Counter only). [insights] are short
 * lines about this item; null sends none.
 */
data class MeterItem(
    val label: String,
    val count: Long?,
    val hourly: List<Long>? = null,
    val insights: List<String>? = null,
)

private fun isoUtc(ms: Long): String = Instant.ofEpochMilli(ms).truncatedTo(ChronoUnit.SECONDS).toString()

/** Items must be 1 to 50, labels unique (any case) and 1 to 120 characters, counts whole numbers that are not negative. */
fun validateItems(items: List<MeterItem>) {
    if (items.size < MIN_ITEMS || items.size > MAX_ITEMS) throw PayloadException("The website takes $MIN_ITEMS to $MAX_ITEMS items; there are ${items.size}.")
    val seen = HashSet<String>()
    for (i in items) {
        if (i.label.isEmpty() || i.label.length > MAX_LABEL) throw PayloadException("A label must be 1 to $MAX_LABEL characters: \"${i.label.take(30)}\".")
        if ((i.count ?: 0) < 0) throw PayloadException("A count cannot be negative (${i.label}).")
        i.insights?.let(::validateInsights)
        if (!seen.add(i.label)) throw PayloadException("The label \"${i.label}\" appears twice.")
        i.hourly?.let { h ->
            if (h.isEmpty() || h.size > MAX_HOURLY) throw PayloadException("The hourly graph takes 1 to $MAX_HOURLY hours; there are ${h.size} (${i.label.take(30)}).")
            if (h.any { it < 0 }) throw PayloadException("An hourly count cannot be negative (${i.label.take(30)}).")
        }
    }
}

/** The website's insight rules: 1 to [MAX_INSIGHTS] lines, each 1 to [MAX_INSIGHT] characters. */
fun validateInsights(lines: List<String>) {
    if (lines.isEmpty() || lines.size > MAX_INSIGHTS) throw PayloadException("Insights must be 1 to $MAX_INSIGHTS lines; there are ${lines.size}.")
    lines.forEach { if (it.isEmpty() || it.length > MAX_INSIGHT) throw PayloadException("An insight line must be 1 to $MAX_INSIGHT characters.") }
}

private fun itemsJson(items: List<MeterItem>) = JSONArray(items.map { item ->
    JSONObject().put("label", item.label).apply {
        item.count?.let { put("count", it) }
        item.hourly?.let { put("hourly", JSONArray(it)) }
        item.insights?.let { put("insights", JSONArray(it)) }
    }
})

/**
 * Count = every tweet with the phrase in the last 24 hours (retweets included), the headline number on the screen.
 * Each item also carries the tweets in each hour (the "Tweets per hour" graph), on one shared grid of whole UTC hours that
 * covers the counted window: [hourlyStart] is where the first hour begins, and the hours are oldest first. An hour X sent no
 * row for is 0; a bucket that starts part-way into an hour is counted in that hour. A bucket that falls outside the grid is an error, never dropped.
 */
/**
 * The Tagline Counter run. [readout] and [checks] are whatever AI results exist for this run at the moment of sending; the
 * same run (same refreshedAt) is sent again each time one is added, and the website updates that run in place. Anything
 * not there yet is simply left out: never null, never an empty object or array.
 */
fun trendPayload(
    report: CountReport,
    zone: ZoneId? = null,
    readout: TaglineAnalysis? = null,
    checks: Map<String, TrendCheckResult> = emptyMap(),
): PublishPayload {
    val grid = try {
        hourlyGrid(report)
    } catch (e: GridException) {
        throw PayloadException(e.message ?: "The hourly graph could not be built.")
    }
    if (grid.hours < 1 || grid.hours > MAX_HOURLY) throw PayloadException("The counted period spans ${grid.hours} hours; the website takes up to $MAX_HOURLY.")
    val gridEnd = grid.hourStart(grid.hours)
    if (kotlin.math.abs(report.fetchedAtMs - gridEnd) > 2 * HOUR_MS) throw PayloadException("The hourly graph does not end within 2 hours of the refresh time.")
    // With a [zone], the same facts as the screen go along as insights (times in that zone): the run's pattern, and each
    // tagline's split, busiest hour, speed, milestones and last hour.
    val items = report.counts.mapIndexed { i, t ->
        MeterItem(t.tagline, t.all.total, grid.series[i], insights = zone?.let { z -> cleanInsights(taglineStat(report, t).detailLines(z)) })
    }
    validateItems(items)
    val body = JSONObject().put("kind", "trend").put("refreshedAt", isoUtc(report.fetchedAtMs)).put("hourlyStart", isoUtc(grid.startMs))
    zone?.let { z -> cleanInsights(insightLines(report, z))?.let { body.put("insights", JSONArray(it)) } }
    readout?.let(::readoutJson)?.let { body.put("readout", it) }
    val itemsArray = itemsJson(items)
    report.counts.forEachIndexed { i, t ->
        checks[t.tagline]?.let { itemsArray.getJSONObject(i).put("trendCheck", trendCheckJson(it)) }
    }
    body.put("items", itemsArray)
    return PublishPayload(MeterKind.TREND, body.toString())
}

// ---- AI results on a Tagline Counter run -----------------------------------------------------------------------------

const val MAX_THEMES = 5
const val MAX_VOICES = 10
const val MAX_LOOK_POSTS = 3
const val MAX_MODEL = 60

private val HANDLE = Regex("""^@[A-Za-z0-9_]{1,15}$""")
private val HASHTAG_OUT = Regex("""^#[\p{L}\p{N}_]{2,60}$""")
private val X_POST = Regex("""^https://x\.com/[A-Za-z0-9_]{1,15}/status/\d{5,25}$""")

/** "xAI · grok-4.3 (OpenAI failed)" → ("xai", "grok-4.3"). */
private fun providerAndModel(label: String): Pair<String, String>? {
    val parts = label.substringBefore(" (").split(" · ")
    if (parts.size < 2) return null
    return parts[0].trim().lowercase() to parts[1].trim().take(MAX_MODEL)
}

private fun line(text: String): String? = cleanInsights(listOf(text))?.first()

/**
 * The AI read-out, as the website takes it: a headline, up to 5 findings, the momentum line, which model wrote it and
 * when. The "post angle" is advice for the user, not for the site, so it is not sent. Null when nothing usable is left.
 */
fun readoutJson(a: TaglineAnalysis): JSONObject? {
    val headline = line(a.headline) ?: return null
    val o = JSONObject().put("headline", headline)
    cleanInsights(a.insights)?.let { o.put("insights", JSONArray(it)) }
    line(a.momentum)?.let { o.put("momentum", it) }
    providerAndModel(a.label)?.let { (p, m) -> o.put("provider", p).put("model", m) }
    if (a.atMs > 0) o.put("writtenAt", isoUtc(a.atMs))
    return o
}

/**
 * One tagline's Trend check: the range, the exact shares from counts, and (after a Full analysis) the sample figures. Sample
 * figures are estimates and always go with the number of posts read. Accounts are only ever @handles that look real.
 */
fun trendCheckJson(c: TrendCheckResult): JSONObject {
    val o = JSONObject().put("rangeStart", isoUtc(c.startMs)).put("rangeEnd", isoUtc(c.endMs)).put("ended", c.ended).put("checkedAt", isoUtc(c.atMs))
    o.put("retweetPct", c.shares.retweetPct).put("replyPct", c.shares.replyPct).put("quotePct", c.shares.quotePct).put("originalPct", c.shares.originalPct)
    c.launch?.let { o.put("launch", it.name.lowercase()).put("launchText", it.label) }
    if (c.waves.isNotEmpty()) o.put("waves", JSONArray(c.waves.map { isoUtc(it) }))
    c.stats?.let { s ->
        o.put("sample", s.sample).put("naturalPct", s.naturalPct).put("botLikePct", s.botLikePct).put("copyPastePct", s.copyPastePct)
        o.put("newAccountPct", s.newAccountPct).put("lowReachPct", s.lowReachPct).put("fanClubPct", s.fanClubPct)
        o.put("top10AuthorsPct", s.top10AuthorsPct).put("medianLikes", s.medianLikes).put("zeroLikePct", s.zeroLikePct)
        s.organizers.filter { HANDLE.matches(it.first) }.takeIf { it.isNotEmpty() }?.let { l ->
            o.put("organizers", JSONArray(l.map { JSONObject().put("handle", it.first).put("retweets", it.second) }))
        }
        s.pairedHashtags.filter { HASHTAG_OUT.matches(it.first) }.takeIf { it.isNotEmpty() }?.let { l -> o.put("pairedHashtags", JSONArray(l.map { it.first })) }
        s.indiaPctOfLocated?.let { o.put("indiaPctOfLocated", it) }
    }
    c.sentiment?.takeIf { it.total > 0 }?.let { m ->
        o.put(
            "sentiment",
            JSONObject().put("positive", m.positive).put("negative", m.negative).put("neutral", m.neutral).put("judged", m.judged)
                .put("byContestant", JSONObject(m.byContestant.mapValues { JSONArray().put(it.value.first).put(it.value.second).put(it.value.third) })),
        )
        providerAndModel(m.label)?.let { (p, md) -> o.put("provider", p).put("model", md) }
    }
    return o
}
