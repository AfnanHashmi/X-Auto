package com.clipcut.domain.publish

import com.clipcut.domain.taglines.CountReport
import com.clipcut.domain.taglines.CountSeries
import com.clipcut.domain.taglines.HourBucket
import com.clipcut.domain.taglines.TaglineCount
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.ZoneId

private const val LINK = "https://x.com/example_channel/status/2110272039504511091"

class UpdatesPayloadTest {
    @Test
    fun trendInsightsComeWithAZoneAndKeepTheExistingFields() {
        val end = 1_792_271_838_261L
        val r = CountReport(end - 24 * 3_600_000L, end, end + 30_000, listOf(TaglineCount("QAZI", CountSeries(500, listOf(HourBucket(end - 3_600_000L, 500))), CountSeries(50, emptyList()))))
        val j = JSONObject(trendPayload(r, ZoneId.of("Asia/Kolkata")).json)
        listOf("kind", "refreshedAt", "hourlyStart", "items", "insights").forEach { assertTrue(it, j.has(it)) }
        val item = j.getJSONArray("items").getJSONObject(0)
        assertTrue(item.has("hourly")); assertTrue(item.getJSONArray("insights").length() in 1..5)
        for (i in 0 until j.getJSONArray("insights").length()) assertTrue(j.getJSONArray("insights").getString(i).length <= 200)
        assertFalse(JSONObject(trendPayload(r).json).has("insights")) // without a zone: exactly as before
    }

    @Test
    fun insightLinesAreCleanedToTheWebsiteRules() {
        assertNull(cleanInsights(listOf(" ", "")))
        assertEquals(listOf("a", "b"), cleanInsights(listOf(" a ", "a", "b")))
        assertEquals(5, cleanInsights(List(8) { "line $it" })!!.size)
        val long = "First sentence is short. " + "x ".repeat(150)
        assertEquals("First sentence is short.", cleanInsights(listOf(long))!!.single())
        val noStop = "word ".repeat(60)
        cleanInsights(listOf(noStop))!!.single().let { assertTrue(it.length <= 200); assertTrue(it.endsWith("…")) }
    }

}
