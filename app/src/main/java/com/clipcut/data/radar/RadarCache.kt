package com.clipcut.data.radar

import android.content.Context
import com.clipcut.domain.radar.RadarSummary
import dagger.hilt.android.qualifiers.ApplicationContext
import org.json.JSONObject
import javax.inject.Inject
import javax.inject.Singleton

/** The last Trend Radar summary seen, so the home card shows something at once, before (or without) a network reply. */
@Singleton
class RadarCache @Inject constructor(@ApplicationContext context: Context) {
    private val prefs = context.getSharedPreferences("radar_cache", Context.MODE_PRIVATE)

    fun load(): RadarSummary = try {
        prefs.getString("summary", null)?.let { s ->
            val o = JSONObject(s)
            RadarSummary(
                o.optString("phase", "off"), o.optInt("rising"), if (o.has("last")) o.optLong("last") else null,
                o.optDouble("spent", 0.0), if (o.has("budget")) o.optDouble("budget") else null, o.optBoolean("sample"),
            )
        } ?: RadarSummary.EMPTY
    } catch (e: Exception) {
        RadarSummary.EMPTY
    }

    fun save(s: RadarSummary) {
        val o = JSONObject().put("phase", s.phase).put("rising", s.rising).put("spent", s.spentUsd).put("sample", s.sample)
        s.lastRunAtMs?.let { o.put("last", it) }
        s.budgetUsd?.let { o.put("budget", it) }
        prefs.edit().putString("summary", o.toString()).apply()
    }
}
