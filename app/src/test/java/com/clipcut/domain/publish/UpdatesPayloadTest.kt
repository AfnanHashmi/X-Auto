package com.clipcut.domain.publish

import com.clipcut.domain.bossmeter.BossMeterResult
import com.clipcut.domain.bossmeter.TagCount
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
private val FINALISTS = BossMeterResult("2110272039504511091", 80_000, listOf(TagCount("#Qazi", 36_120), TagCount("#Uditi", 40_210)), 1_792_272_600_000L)

class UpdatesPayloadTest {
    @Test
    fun bossCarriesStageAndFinalAsTheWebsiteExpects() {
        val j = JSONObject(bossPayload(FINALISTS, LINK, BossStage.FINALISTS, final = true).json)
        assertEquals("boss", j.getString("kind")); assertEquals("finalists", j.getString("stage")); assertTrue(j.getBoolean("final"))
        assertEquals(LINK, j.getString("postUrl"))
        assertEquals("#Uditi", j.getJSONArray("items").getJSONObject(0).getString("label")) // highest first, as before
        val notFinal = JSONObject(bossPayload(FINALISTS, LINK, BossStage.CONTESTANTS).json)
        assertEquals("contestants", notFinal.getString("stage")); assertFalse(notFinal.has("final")) // never "final": false
        assertThrows(PayloadException::class.java) { bossPayload(FINALISTS, LINK, BossStage.CONTESTANTS, final = true) }
        assertThrows(PayloadException::class.java) { bossPayload(FINALISTS, LINK, null, final = true) }
    }

    @Test
    fun theRoundStageDefaultsFromTheHashtagCount() {
        assertEquals(BossStage.FINALISTS, BossStage.defaultFor(2))
        assertEquals(BossStage.CONTESTANTS, BossStage.defaultFor(5))
        assertEquals(BossStage.CONTESTANTS, BossStage.defaultFor(1))
    }

    @Test
    fun bossInsightsAreShortFactsAndOnlyWhenAsked() {
        val j = JSONObject(bossPayload(FINALISTS, LINK, BossStage.FINALISTS, withInsights = true).json)
        val top = j.getJSONArray("insights")
        assertEquals("76,330 of 80,000 replies used a tracked hashtag (95%).", top.getString(0))
        assertEquals("#Uditi leads #Qazi by 4,090 votes.", top.getString(1))
        val first = j.getJSONArray("items").getJSONObject(0).getJSONArray("insights")
        assertEquals("52.7% of hashtag votes", first.getString(0)); assertEquals("4,090 ahead of #Qazi", first.getString(1))
        assertEquals(1, j.getJSONArray("items").getJSONObject(1).getJSONArray("insights").length()) // last: no "ahead of"
        assertFalse(JSONObject(bossPayload(FINALISTS, LINK).json).has("insights"))
    }

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

    @Test
    fun top5MatchesTheSpecAndNeverSendsNull() {
        val p = top5Payload(
            "Week 3",
            listOf(Top5Row("Qazi", "152,000", "Captain this week"), Top5Row("Uditi", "131000"), Top5Row("Nakul"), Top5Row(""), Top5Row(" ")),
            nowMs = 1_792_272_600_000L,
        )
        assertEquals(MeterKind.TOP5, p.kind)
        val j = JSONObject(p.json)
        assertEquals(setOf("kind", "refreshedAt", "week", "items"), j.keys().asSequence().toSet())
        assertEquals("top5", j.getString("kind")); assertEquals("Week 3", j.getString("week"))
        val items = j.getJSONArray("items")
        assertEquals(3, items.length())
        fun keys(i: Int) = items.getJSONObject(i).keys().asSequence().toSet()
        items.getJSONObject(0).let { assertEquals("Qazi", it.getString("label")); assertEquals(152_000L, it.getLong("count")); assertEquals("Captain this week", it.getJSONArray("insights").getString(0)) }
        assertEquals(setOf("label", "count", "insights"), keys(0))
        assertEquals(setOf("label", "count"), keys(1)); assertEquals(131_000L, items.getJSONObject(1).getLong("count"))
        assertEquals(setOf("label"), keys(2)); assertEquals("Nakul", items.getJSONObject(2).getString("label"))
        assertFalse(JSONObject(top5Payload("", listOf(Top5Row("A")), 0).json).has("week"))
    }

    @Test
    fun top5RulesAreCheckedBeforeSending() {
        assertThrows(PayloadException::class.java) { top5Payload("", List(5) { Top5Row("") }, 0) } // nothing filled
        assertThrows(PayloadException::class.java) { top5Payload("", listOf(Top5Row("Qazi"), Top5Row("qazi")), 0) } // names unique
        assertThrows(PayloadException::class.java) { top5Payload("", listOf(Top5Row("", "5")), 0) } // a number without a name
        assertThrows(PayloadException::class.java) { top5Payload("", listOf(Top5Row("A", "1.5")), 0) }
        assertThrows(PayloadException::class.java) { top5Payload("x".repeat(41), listOf(Top5Row("A")), 0) }
        assertThrows(PayloadException::class.java) { top5Payload("", listOf(Top5Row("A", note = "n".repeat(201))), 0) }
        assertThrows(PayloadException::class.java) { top5Payload("", listOf(Top5Row("x".repeat(121))), 0) }
        assertThrows(PayloadException::class.java) { top5Payload("", List(6) { Top5Row("C$it") }, 0) }
    }
}
