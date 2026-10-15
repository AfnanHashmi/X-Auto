package com.clipcut.domain.taglines

import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import org.json.JSONArray
import org.json.JSONObject
import java.time.Instant
import javax.inject.Inject
import javax.inject.Singleton

/** Keeps the tagline list and the last result on the phone, so the screen opens with them and costs nothing. */
@Singleton
class TaglineStore @Inject constructor(@ApplicationContext context: Context) {
    private val prefs = context.getSharedPreferences("taglines", Context.MODE_PRIVATE)

    fun taglines(): List<String> {
        val raw = prefs.getString(KEY_LIST, null) ?: return DEFAULTS
        return runCatching { JSONArray(raw).let { a -> List(a.length()) { a.getString(it) } } }.getOrDefault(DEFAULTS)
    }

    fun saveTaglines(list: List<String>) {
        prefs.edit().putString(KEY_LIST, JSONArray(list).toString()).apply()
    }

    fun lastReport(): CountReport? = prefs.getString(KEY_REPORT, null)?.let { runCatching { decode(it) }.getOrNull() }

    fun saveReport(report: CountReport) {
        prefs.edit().putString(KEY_REPORT, encode(report)).apply()
    }

    private fun encode(r: CountReport): String = JSONObject()
        .put("start", r.startMs).put("end", r.endMs).put("fetched", r.fetchedAtMs)
        .put("counts", JSONArray(r.counts.map { c -> JSONObject().put("tagline", c.tagline).put("all", series(c.all)).put("originals", series(c.originals)) }))
        .toString()

    private fun series(s: CountSeries) = JSONObject().put("total", s.total)
        .put("hours", JSONArray(s.hours.map { JSONArray(listOf(it.startMs, it.count)) }))

    private fun decode(text: String): CountReport {
        val o = JSONObject(text)
        fun series(j: JSONObject) = CountSeries(
            j.getLong("total"),
            j.getJSONArray("hours").let { a -> List(a.length()) { i -> a.getJSONArray(i).let { HourBucket(it.getLong(0), it.getLong(1)) } } },
        )
        val counts = o.getJSONArray("counts").let { a ->
            List(a.length()) { i -> a.getJSONObject(i).let { TaglineCount(it.getString("tagline"), series(it.getJSONObject("all")), series(it.getJSONObject("originals"))) } }
        }
        return CountReport(o.getLong("start"), o.getLong("end"), o.getLong("fetched"), counts)
    }

    companion object {
        private const val KEY_LIST = "list"
        private const val KEY_REPORT = "report"
        val DEFAULTS = listOf("STOP NEGATIVE PR AGAINST QAZI", "QAZI RISING ABOVE HATE", "QAZI BORN TO SHINE")
    }
}
