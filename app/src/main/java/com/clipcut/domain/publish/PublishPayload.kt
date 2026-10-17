package com.clipcut.domain.publish

import com.clipcut.domain.bossmeter.BossMeterResult
import com.clipcut.domain.taglines.CountReport
import com.clipcut.domain.taglines.GridException
import com.clipcut.domain.taglines.HOUR_MS
import com.clipcut.domain.taglines.hourlyGrid
import org.json.JSONArray
import org.json.JSONObject
import java.time.Instant
import java.time.temporal.ChronoUnit

/** The website refuses this payload, or it cannot be built from what the app has. Nothing is sent. */
class PayloadException(message: String) : Exception(message)

enum class MeterKind(val wire: String, val label: String) {
    BOSS("boss", "Boss Meter"),
    TREND("trend", "Tagline Counter"),
}

/** A ready-to-send body. [json] is exactly what is POSTed, and exactly what Retry resends. */
data class PublishPayload(val kind: MeterKind, val json: String)

const val MIN_ITEMS = 1
const val MAX_ITEMS = 50
const val MAX_LABEL = 120
const val MAX_HOURLY = 48

/** [hourly] is the tweets in each hour, oldest first (Tagline Counter only); null for Boss Meter. */
data class MeterItem(val label: String, val count: Long, val hourly: List<Long>? = null)

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
        if (i.count < 0) throw PayloadException("A count cannot be negative (${i.label}).")
        if (!seen.add(i.label)) throw PayloadException("The label \"${i.label}\" appears twice.")
        i.hourly?.let { h ->
            if (h.isEmpty() || h.size > MAX_HOURLY) throw PayloadException("The hourly graph takes 1 to $MAX_HOURLY hours; there are ${h.size} (${i.label.take(30)}).")
            if (h.any { it < 0 }) throw PayloadException("An hourly count cannot be negative (${i.label.take(30)}).")
        }
    }
}

private fun itemsJson(items: List<MeterItem>) = JSONArray(items.map { item ->
    JSONObject().put("label", item.label).put("count", item.count).apply { item.hourly?.let { put("hourly", JSONArray(it)) } }
})

fun bossPayload(result: BossMeterResult, postLink: String): PublishPayload {
    val url = canonicalPostUrl(postLink) ?: throw PayloadException("The website needs a full post link with the user name (https://x.com/user/status/123…).")
    val items = result.ranked.map { MeterItem(it.tag, it.count) }
    validateItems(items)
    val body = JSONObject().put("kind", "boss").put("refreshedAt", isoUtc(result.fetchedAtMs)).put("postUrl", url).put("items", itemsJson(items))
    return PublishPayload(MeterKind.BOSS, body.toString())
}

/**
 * Count = every tweet with the phrase in the last 24 hours (retweets included), the headline number on the screen.
 * Each item also carries the tweets in each hour (the "Tweets per hour" graph), on one shared grid of whole UTC hours that
 * covers the counted window: [hourlyStart] is where the first hour begins, and the hours are oldest first. An hour X sent no
 * row for is 0; a bucket that starts part-way into an hour is counted in that hour. A bucket that falls outside the grid is an error, never dropped.
 */
fun trendPayload(report: CountReport): PublishPayload {
    val grid = try {
        hourlyGrid(report)
    } catch (e: GridException) {
        throw PayloadException(e.message ?: "The hourly graph could not be built.")
    }
    if (grid.hours < 1 || grid.hours > MAX_HOURLY) throw PayloadException("The counted period spans ${grid.hours} hours; the website takes up to $MAX_HOURLY.")
    val gridEnd = grid.hourStart(grid.hours)
    if (kotlin.math.abs(report.fetchedAtMs - gridEnd) > 2 * HOUR_MS) throw PayloadException("The hourly graph does not end within 2 hours of the refresh time.")
    val items = report.counts.mapIndexed { i, t -> MeterItem(t.tagline, t.all.total, grid.series[i]) }
    validateItems(items)
    val body = JSONObject().put("kind", "trend").put("refreshedAt", isoUtc(report.fetchedAtMs)).put("hourlyStart", isoUtc(grid.startMs)).put("items", itemsJson(items))
    return PublishPayload(MeterKind.TREND, body.toString())
}
