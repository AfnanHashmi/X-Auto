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

    /** The post whose hashtags were already read from X, so pasting the same link again costs nothing. */
    var readPostId: String
        get() = prefs.getString("readPostId", "").orEmpty()
        set(v) = prefs.edit().putString("readPostId", v).apply()

    /** The Instagram post being counted (its link or shortcode). */
    var igPost: String
        get() = prefs.getString("igPost", "").orEmpty()
        set(v) = prefs.edit().putString("igPost", v).apply()

    /** Leave out the comments Instagram hides behind "View hidden comments". */
    var igExcludeHidden: Boolean
        get() = prefs.getBoolean("igExcludeHidden", false)
        set(v) = prefs.edit().putBoolean("igExcludeHidden", v).apply()

    /** The last Instagram reply (raw JSON) and when it arrived. */
    var igRaw: String
        get() = prefs.getString("igRaw", "").orEmpty()
        set(v) = prefs.edit().putString("igRaw", v).apply()
    var igFetchedAt: Long
        get() = prefs.getLong("igFetchedAt", 0L)
        set(v) = prefs.edit().putLong("igFetchedAt", v).apply()

    /** The last Instagram result as the app can read it, or null. */
    fun igResult(): IgBossResult? = igRaw.takeIf { it.isNotEmpty() }?.let { runCatching { parseIgBoss(it, igFetchedAt) }.getOrNull() }

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
