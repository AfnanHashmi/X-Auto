package com.clipcut.domain.publish

import com.clipcut.domain.taglines.CountReport
import com.clipcut.domain.taglines.CountSeries
import com.clipcut.domain.taglines.HourBucket
import com.clipcut.domain.taglines.TaglineAnalysis
import com.clipcut.domain.taglines.TaglineCount
import com.clipcut.domain.taglines.fxCheck
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.ZoneId

private val IST = ZoneId.of("Asia/Kolkata")
private const val END = 1_792_271_838_261L

private fun t(name: String, n: Long) = TaglineCount(name, CountSeries(n, listOf(HourBucket(END - 3_600_000L, n))), CountSeries(n / 10, emptyList()))
private val RUN = CountReport(END - 24 * 3_600_000L, END, END + 30_000, listOf(t("QAZI THE POWERHOUSE OF BB20", 49_107), t("WE WANT NAKUL BACK", 16_361)))

private val READOUT = TaglineAnalysis("Qazi leads with 49,107 tweets", listOf("Leads #2 by 32,746", "Most retweet-driven"), "Fading fast", "Frame the lead", "OpenAI · gpt-5.6-terra", 0.02, END + 600_000)

class AiPublishTest {
    private fun send(readout: TaglineAnalysis? = null, checks: Map<String, com.clipcut.domain.taglines.TrendCheckResult> = emptyMap()) =
        JSONObject(trendPayload(RUN, IST, readout, checks).json)

    @Test
    fun withNoAiTheRunIsExactlyAsBefore() {
        val j = send()
        assertFalse(j.has("readout"))
        for (i in 0 until j.getJSONArray("items").length()) {
            assertFalse(j.getJSONArray("items").getJSONObject(i).has("trendCheck"))
            assertFalse(j.getJSONArray("items").getJSONObject(i).has("deepLook")) // Deep look is gone
        }
    }

    @Test
    fun aReadoutIsAddedToTheSameRun() {
        val j = send(readout = READOUT)
        assertEquals(JSONObject(trendPayload(RUN, IST).json).getString("refreshedAt"), j.getString("refreshedAt")) // same run
        val r = j.getJSONObject("readout")
        assertEquals("Qazi leads with 49,107 tweets", r.getString("headline"))
        assertEquals(2, r.getJSONArray("insights").length()); assertEquals("Fading fast", r.getString("momentum"))
        assertEquals("openai", r.getString("provider")); assertEquals("gpt-5.6-terra", r.getString("model"))
        assertTrue(r.has("writtenAt")); assertFalse(r.has("captionAngle")); assertFalse(r.toString().contains("Frame the lead"))
    }

    @Test
    fun trendChecksGoOnlyOnTheTaglinesThatHaveOne() {
        val c = fxCheck("QAZI THE POWERHOUSE OF BB20")
        val j = send(checks = mapOf(c.tagline to c))
        val items = j.getJSONArray("items")
        assertFalse(items.getJSONObject(1).has("trendCheck")) // Nakul had no Trend check
        val d = items.getJSONObject(0).getJSONObject("trendCheck")
        assertEquals(38, d.getInt("naturalPct")); assertEquals(22, d.getInt("botLikePct")); assertEquals(41, d.getInt("copyPastePct"))
        assertEquals(200, d.getInt("sample")); assertEquals("sudden", d.getString("launch")); assertTrue(d.getBoolean("ended"))
        assertEquals(90, d.getInt("retweetPct")); assertEquals(2, d.getInt("replyPct")); assertEquals(1, d.getInt("quotePct"))
        assertEquals(1, d.getJSONArray("organizers").length()) // the fake handle is dropped
        assertEquals("@fan_account_one", d.getJSONArray("organizers").getJSONObject(0).getString("handle"))
        assertEquals(1, d.getJSONArray("pairedHashtags").length()) // "bad tag" is dropped
        val s = d.getJSONObject("sentiment")
        assertEquals(60, s.getInt("positive")); assertEquals(20, s.getInt("negative")); assertEquals(30, s.getJSONObject("byContestant").getJSONArray("Qazi Jee").getInt(0))
        assertEquals("openai", d.getString("provider"))
        assertFalse(j.has("readout")) // a check without a read-out
    }

    @Test
    fun aCheckWithOnlyTheExactCountsSendsNoSampleFigures() {
        val d = JSONObject(trendCheckJson(fxCheck("Q", withSample = false)).toString())
        assertTrue(d.has("retweetPct")); assertTrue(d.has("launch"))
        assertFalse(d.has("sample")); assertFalse(d.has("naturalPct")); assertFalse(d.has("sentiment")); assertFalse(d.has("organizers"))
    }

    @Test
    fun bothTogetherAndNothingEmptyIsEverSent() {
        val j = send(readout = READOUT, checks = mapOf("QAZI THE POWERHOUSE OF BB20" to fxCheck("QAZI THE POWERHOUSE OF BB20")))
        assertTrue(j.has("readout")); assertTrue(j.getJSONArray("items").getJSONObject(0).has("trendCheck"))
        assertNull(readoutJson(READOUT.copy(headline = "  ")))
        assertFalse(readoutJson(READOUT.copy(insights = emptyList()))!!.has("insights"))
    }
}
