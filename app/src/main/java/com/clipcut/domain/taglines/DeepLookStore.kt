package com.clipcut.domain.taglines

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.time.LocalDate

/** A read-out together with the totals it was written from, so the next one can compare against it. */
data class ReadoutRecord(val reportAtMs: Long, val totals: Map<String, Long>, val analysis: TaglineAnalysis)

/** How many Deep looks are kept per tagline, and how many read-outs in all. Old ones are dropped first. */
const val LOOKS_KEPT_PER_TAGLINE = 30
const val READOUTS_KEPT = 30

/**
 * Keeps every Deep look and AI read-out (they are paid for, and later runs build on them), plus the day's Deep look
 * spend for the cap. Results are tied to the count they were made for, but earlier ones stay as history.
 */
class DeepLookStore(context: Context) : DeepLookLedger {
    private val prefs = context.getSharedPreferences("deep_look", Context.MODE_PRIVATE)

    override fun spentOn(day: LocalDate): Double = prefs.getFloat("spent_$day", 0f).toDouble()

    override fun add(day: LocalDate, usd: Double) {
        val e = prefs.edit()
        // Only today's line is needed; older days are dropped as they come up.
        prefs.all.keys.filter { it.startsWith("spent_") && it != "spent_$day" }.forEach(e::remove)
        e.putFloat("spent_$day", (spentOn(day) + usd).toFloat()).apply()
    }

    // ---- Deep looks ------------------------------------------------------------------------------------------------

    /** Every kept Deep look, newest first. */
    fun allLooks(): List<DeepLook> {
        migrateOldLooks()
        val raw = prefs.getString("look_history", null) ?: return emptyList()
        return runCatching { JSONArray(raw).let { a -> List(a.length()) { decodeLook(a.getJSONObject(it)) } } }.getOrDefault(emptyList())
            .sortedByDescending { it.atMs }
    }

    /** The newest look per tagline taken for the count fetched at [reportAtMs]. */
    fun looksFor(reportAtMs: Long): Map<String, DeepLook> =
        allLooks().filter { it.reportAtMs == reportAtMs }.groupBy { it.tagline }.mapValues { it.value.first() }

    /** Earlier looks at [tagline], newest first. */
    fun history(tagline: String): List<DeepLook> = allLooks().filter { it.tagline.equals(tagline, ignoreCase = true) }

    fun addLook(look: DeepLook) {
        val kept = (listOf(look) + allLooks())
            .groupBy { it.tagline.lowercase() }
            .flatMap { (_, l) -> l.sortedByDescending { it.atMs }.take(LOOKS_KEPT_PER_TAGLINE) }
        prefs.edit().putString("look_history", JSONArray(kept.map(::encodeLook)).toString()).apply()
    }

    /** The first version kept only the latest count's looks under "looks"; carry them into the history once. */
    private fun migrateOldLooks() {
        val old = prefs.getString("looks", null) ?: return
        val moved = runCatching {
            val o = JSONObject(old)
            val at = o.getLong("report")
            val a = o.getJSONArray("items")
            List(a.length()) { decodeLook(a.getJSONObject(it)).copy(reportAtMs = at) }
        }.getOrDefault(emptyList())
        val existing = prefs.getString("look_history", null)?.let { runCatching { JSONArray(it) }.getOrNull() } ?: JSONArray()
        moved.forEach { existing.put(encodeLook(it)) }
        prefs.edit().putString("look_history", existing.toString()).remove("looks").apply()
    }

    // ---- read-outs -------------------------------------------------------------------------------------------------

    /** Every kept read-out, newest first. */
    fun readouts(): List<ReadoutRecord> {
        val raw = prefs.getString("readouts", null) ?: return emptyList()
        return runCatching { JSONArray(raw).let { a -> List(a.length()) { decodeReadout(a.getJSONObject(it)) } } }.getOrDefault(emptyList())
            .sortedByDescending { it.analysis.atMs }
    }

    fun readoutFor(reportAtMs: Long): TaglineAnalysis? = readouts().firstOrNull { it.reportAtMs == reportAtMs }?.analysis

    /** The newest read-out written for an earlier count than [reportAtMs]: what the next one compares against. */
    fun previousReadout(reportAtMs: Long): ReadoutRecord? = readouts().firstOrNull { it.reportAtMs < reportAtMs }

    fun addReadout(record: ReadoutRecord) {
        val kept = (listOf(record) + readouts()).take(READOUTS_KEPT)
        prefs.edit().putString("readouts", JSONArray(kept.map(::encodeReadout)).toString()).apply()
    }

    // ---- encoding --------------------------------------------------------------------------------------------------

    private fun encodeLook(d: DeepLook) = JSONObject()
        .put("tagline", d.tagline).put("posts", d.postsRead).put("label", d.label).put("at", d.atMs)
        .put("cost", d.costUsd ?: -1.0).put("changes", d.changes).put("report", d.reportAtMs)
        .put("themes", JSONArray(d.themes)).put("posts_urls", JSONArray(d.examplePosts))
        .put("voices", JSONArray(d.voices.map { JSONObject().put("h", it.handle).put("w", it.why) }))
        .put("starters", JSONArray(d.starters.map { JSONObject().put("h", it.handle).put("w", it.why) }))
        .put("rivals", JSONArray(d.rivals)).put("paired", JSONArray(d.pairedHashtags)).put("size", d.size)

    private fun decodeLook(o: JSONObject): DeepLook {
        fun strs(k: String) = o.getJSONArray(k).let { a -> List(a.length()) { a.getString(it) } }
        return DeepLook(
            tagline = o.getString("tagline"),
            postsRead = o.getInt("posts"),
            themes = strs("themes"),
            voices = o.getJSONArray("voices").let { a -> List(a.length()) { a.getJSONObject(it).let { v -> Voice(v.getString("h"), v.getString("w")) } } },
            examplePosts = strs("posts_urls"),
            costUsd = o.getDouble("cost").takeIf { it >= 0 },
            label = o.getString("label"),
            atMs = o.getLong("at"),
            changes = o.optString("changes"),
            reportAtMs = o.optLong("report"),
            starters = o.optJSONArray("starters")?.let { a -> List(a.length()) { a.getJSONObject(it).let { v -> Voice(v.getString("h"), v.getString("w")) } } }.orEmpty(),
            rivals = o.optJSONArray("rivals")?.let { a -> List(a.length()) { a.getString(it) } }.orEmpty(),
            pairedHashtags = o.optJSONArray("paired")?.let { a -> List(a.length()) { a.getString(it) } }.orEmpty(),
            size = o.optString("size"),
        )
    }

    private fun encodeReadout(r: ReadoutRecord) = JSONObject()
        .put("report", r.reportAtMs)
        .put("totals", JSONObject(r.totals))
        .put("headline", r.analysis.headline).put("insights", JSONArray(r.analysis.insights))
        .put("momentum", r.analysis.momentum).put("angle", r.analysis.captionAngle)
        .put("label", r.analysis.label).put("cost", r.analysis.costUsd ?: -1.0).put("at", r.analysis.atMs)

    private fun decodeReadout(o: JSONObject): ReadoutRecord {
        val t = o.getJSONObject("totals")
        val i = o.getJSONArray("insights")
        return ReadoutRecord(
            reportAtMs = o.getLong("report"),
            totals = t.keys().asSequence().associateWith { t.getLong(it) },
            analysis = TaglineAnalysis(
                headline = o.getString("headline"),
                insights = List(i.length()) { i.getString(it) },
                momentum = o.getString("momentum"),
                captionAngle = o.getString("angle"),
                label = o.getString("label"),
                costUsd = o.getDouble("cost").takeIf { it >= 0 },
                atMs = o.getLong("at"),
            ),
        )
    }
}

/** Accounts that were top voices in at least two of the [looks]: the steady drivers, with how many looks they appeared in. */
fun regulars(looks: List<DeepLook>): List<Pair<String, Int>> =
    looks.flatMap { l -> l.voices.map { it.handle.lowercase() to it.handle }.distinctBy { it.first } }
        .groupBy({ it.first }, { it.second })
        .filter { it.value.size >= 2 }
        .map { (_, handles) -> handles.first() to handles.size }
        .sortedByDescending { it.second }
