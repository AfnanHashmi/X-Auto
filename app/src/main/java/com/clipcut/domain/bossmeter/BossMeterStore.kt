package com.clipcut.domain.bossmeter

import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import org.json.JSONArray
import org.json.JSONObject
import javax.inject.Inject
import javax.inject.Singleton

/** The last link, hashtags and result, kept on the phone so reopening costs nothing. */
@Singleton
class BossMeterStore @Inject constructor(@ApplicationContext context: Context) {
    private val prefs = context.getSharedPreferences("boss_meter", Context.MODE_PRIVATE)

    var link: String
        get() = prefs.getString("link", "").orEmpty()
        set(v) = prefs.edit().putString("link", v).apply()

    /** The last "Round stage" choice ("contestants" or "finalists"); blank before the first. */
    var stage: String
        get() = prefs.getString("stage", "").orEmpty()
        set(v) = prefs.edit().putString("stage", v).apply()

    var hashtags: String
        get() = prefs.getString("hashtags", "").orEmpty()
        set(v) = prefs.edit().putString("hashtags", v).apply()

    fun lastResult(): BossMeterResult? = prefs.getString("result", null)?.let { text ->
        runCatching {
            val o = JSONObject(text)
            val a = o.getJSONArray("tags")
            BossMeterResult(
                o.getString("id"), o.getLong("total"),
                List(a.length()) { i -> a.getJSONObject(i).let { TagCount(it.getString("tag"), it.getLong("count")) } },
                o.getLong("fetched"),
            )
        }.getOrNull()
    }

    fun saveResult(r: BossMeterResult) {
        val o = JSONObject().put("id", r.conversationId).put("total", r.totalThread).put("fetched", r.fetchedAtMs)
            .put("tags", JSONArray(r.tags.map { JSONObject().put("tag", it.tag).put("count", it.count) }))
        prefs.edit().putString("result", o.toString()).apply()
    }
}
