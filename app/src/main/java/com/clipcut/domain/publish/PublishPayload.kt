package com.clipcut.domain.publish

import com.clipcut.domain.bossmeter.BossMeterResult
import com.clipcut.domain.taglines.CountReport
import com.clipcut.domain.taglines.GridException
import com.clipcut.domain.taglines.HOUR_MS
import com.clipcut.domain.taglines.hourlyGrid
import com.clipcut.domain.taglines.insightLines
import com.clipcut.domain.share.taglineStat
import java.time.ZoneId
import org.json.JSONArray
import org.json.JSONObject
import java.time.Instant
import java.time.temporal.ChronoUnit

/** The website refuses this payload, or it cannot be built from what the app has. Nothing is sent. */
class PayloadException(message: String) : Exception(message)

enum class MeterKind(val wire: String, val label: String) {
    BOSS("boss", "Boss Meter"),
    TREND("trend", "Tagline Counter"),
    TOP5("top5", "Top 5 of the week"),
}

/** Which post of a Boss Meter round a run is: the one with the 5 contestants, or the one with the 2 finalists. */
enum class BossStage(val wire: String, val label: String) {
    CONTESTANTS("contestants", "5 contestants"),
    FINALISTS("finalists", "Finalists"),
    ;

    companion object {
        fun from(wire: String?): BossStage? = entries.firstOrNull { it.wire == wire }

        /** Exactly 2 hashtags is the finalists' post; anything else is the 5 contestants' post. */
        fun defaultFor(hashtagCount: Int): BossStage = if (hashtagCount == 2) FINALISTS else CONTESTANTS
    }
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
 * [hourly] is the tweets in each hour, oldest first (Tagline Counter only); null for Boss Meter. [insights] are short
 * lines about this item; null sends none. [count] is null only for a Top 5 row without a number.
 */
data class MeterItem(val label: String, val count: Long?, val hourly: List<Long>? = null, val insights: List<String>? = null)

private val POST_LINK = Regex("""^(?:https?://)?(?:www\.|mobile\.)?(?:x|twitter)\.com/([A-Za-z0-9_]{1,15})/status(?:es)?/(\d{5,25})(?:[/?#].*)?$""", RegexOption.IGNORE_CASE)

/**
 * `https://x.com/<user>/status/<id>` from a pasted link, or null. A bare id, or a link without a user name
 * (x.com/i/web/status/...), cannot be turned into one.
 */
fun canonicalPostUrl(link: String): String? {
    val m = POST_LINK.matchEntire(link.trim()) ?: return null
    val user = m.groupValues[1]
    if (user.equals("i", ignoreCase = true)) return null
    return "https://x.com/$user/status/${m.groupValues[2]}"
}

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
 * [stage] says which post of the round this is ("contestants" starts a new round on the website when the post is new);
 * [final] marks the finalists' run as the final result, and is only allowed with [BossStage.FINALISTS]. With
 * [withInsights], short facts about the run and each hashtag go along.
 */
fun bossPayload(result: BossMeterResult, postLink: String, stage: BossStage? = null, final: Boolean = false, withInsights: Boolean = false): PublishPayload {
    val url = canonicalPostUrl(postLink) ?: throw PayloadException("The website needs a full post link with the user name (https://x.com/user/status/123…).")
    if (final && stage != BossStage.FINALISTS) throw PayloadException("\"Final result\" is only for the finalists' post.")
    val ranked = result.ranked
    val items = ranked.mapIndexed { i, t -> MeterItem(t.tag, t.count, insights = if (withInsights) cleanInsights(bossItemInsights(result, i)) else null) }
    validateItems(items)
    val body = JSONObject().put("kind", "boss").put("refreshedAt", isoUtc(result.fetchedAtMs)).put("postUrl", url)
    stage?.let { body.put("stage", it.wire) }
    if (final) body.put("final", true)
    if (withInsights) cleanInsights(bossInsights(result))?.let { body.put("insights", JSONArray(it)) }
    body.put("items", itemsJson(items))
    return PublishPayload(MeterKind.BOSS, body.toString())
}

/** About the whole run: how much of the thread voted with a tracked hashtag. */
fun bossInsights(r: BossMeterResult): List<String> = buildList {
    if (r.totalThread > 0) add("${"%,d".format(r.trackedSum)} of ${"%,d".format(r.totalThread)} replies used a tracked hashtag (${"%.0f".format(r.trackedSum * 100.0 / r.totalThread)}%).")
    val ranked = r.ranked
    if (ranked.size >= 2 && ranked[0].count > ranked[1].count) add("${ranked[0].tag} leads ${ranked[1].tag} by ${"%,d".format(ranked[0].count - ranked[1].count)} votes.")
}

/** About one hashtag: its share of the votes, and its lead over the next one when it has one. */
fun bossItemInsights(r: BossMeterResult, index: Int): List<String> {
    val ranked = r.ranked
    val t = ranked[index]
    val next = ranked.getOrNull(index + 1)
    return listOfNotNull(
        "${"%.1f".format(r.percent(t))}% of hashtag votes",
        next?.takeIf { t.count > it.count }?.let { "${"%,d".format(t.count - it.count)} ahead of ${it.tag}" },
    )
}

/**
 * Count = every tweet with the phrase in the last 24 hours (retweets included), the headline number on the screen.
 * Each item also carries the tweets in each hour (the "Tweets per hour" graph), on one shared grid of whole UTC hours that
 * covers the counted window: [hourlyStart] is where the first hour begins, and the hours are oldest first. An hour X sent no
 * row for is 0; a bucket that starts part-way into an hour is counted in that hour. A bucket that falls outside the grid is an error, never dropped.
 */
fun trendPayload(report: CountReport, zone: ZoneId? = null): PublishPayload {
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
    body.put("items", itemsJson(items))
    return PublishPayload(MeterKind.TREND, body.toString())
}

// ---- Top 5 of the week -------------------------------------------------------------------------------------------------

const val MAX_WEEK_LABEL = 40
const val MAX_TOP5_NOTE = 200

/** One rank as typed: [number] and [note] may be blank. */
data class Top5Row(val name: String, val number: String = "", val note: String = "")

/**
 * The weekly Top 5. Empty rows are skipped; order is rank. Names are required for a row to count, at most
 * [MAX_LABEL] characters and unique (any case). A number, if given, is a whole number of 0 or more. Nothing is null:
 * a missing week, number or note is simply left out.
 */
fun top5Payload(week: String, rows: List<Top5Row>, nowMs: Long): PublishPayload {
    val w = week.trim()
    if (w.length > MAX_WEEK_LABEL) throw PayloadException("The week label is at most $MAX_WEEK_LABEL characters.")
    val filled = rows.filter { it.name.isNotBlank() || it.number.isNotBlank() || it.note.isNotBlank() }
    if (filled.isEmpty()) throw PayloadException("Add at least one contestant.")
    if (filled.size > 5) throw PayloadException("At most 5 contestants.")
    val items = filled.mapIndexed { i, r ->
        val name = r.name.trim().replace(Regex("\\s+"), " ")
        if (name.isEmpty()) throw PayloadException("Rank ${i + 1} has a number or note but no name.")
        val number = r.number.trim().replace(",", "").takeIf { it.isNotEmpty() }?.let {
            it.toLongOrNull()?.takeIf { n -> n >= 0 } ?: throw PayloadException("Rank ${i + 1}: the number must be a whole number, 0 or more.")
        }
        val note = r.note.trim().replace(Regex("\\s+"), " ")
        if (note.length > MAX_TOP5_NOTE) throw PayloadException("Rank ${i + 1}: the note is at most $MAX_TOP5_NOTE characters.")
        MeterItem(name, number, insights = note.takeIf { it.isNotEmpty() }?.let(::listOf))
    }
    val seen = HashSet<String>()
    items.forEach { if (!seen.add(it.label.lowercase())) throw PayloadException("\"${it.label}\" appears twice.") }
    validateItems(items)
    val body = JSONObject().put("kind", "top5").put("refreshedAt", isoUtc(nowMs))
    if (w.isNotEmpty()) body.put("week", w)
    body.put("items", itemsJson(items))
    return PublishPayload(MeterKind.TOP5, body.toString())
}
