package com.clipcut.domain.history

import com.clipcut.domain.bossmeter.BossMeterResult
import com.clipcut.domain.bossmeter.TagCount
import com.clipcut.domain.llm.LlmException
import com.clipcut.domain.taglines.CountReport
import com.clipcut.domain.taglines.CountSeries
import com.clipcut.domain.taglines.HOUR_MS
import com.clipcut.domain.taglines.HourBucket
import com.clipcut.domain.taglines.TaglineAnalysis
import com.clipcut.domain.taglines.TaglineCount
import com.clipcut.domain.taglines.checkNumbers
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.time.Instant
import java.time.ZoneId

private val IST = ZoneId.of("Asia/Kolkata")
// Friday 2026-10-16 18:00 IST, and a moment two days later.
private val WEEK = Instant.parse("2026-10-16T12:30:00Z").toEpochMilli()
private val NOW = WEEK + 2 * 24 * HOUR_MS

/** A count fetched at [at] with the given taglines, each with [perHour] tweets in each of [hours] hours before [at]. */
private fun count(at: Long, vararg t: Triple<String, Long, Int>) = CountReport(
    at - 24 * HOUR_MS, at, at,
    t.map { (name, perHour, hours) ->
        val buckets = (1..hours).map { HourBucket(Math.floorDiv(at, HOUR_MS) * HOUR_MS - it * HOUR_MS, perHour) }
        TaglineCount(name, CountSeries(perHour * hours, buckets), CountSeries(perHour * hours / 10, buckets.map { it.copy(count = it.count / 10) }))
    },
)

class RelevanceTest {
    @get:Rule val tmp = TemporaryFolder()
    private val links = mapOf("qazi the powerhouse" to "Qazi", "we want nakul back" to "Nakul", "qazi" to "Qazi", "nakul" to "Nakul", "uditi" to "Uditi")
    private val of: (String) -> String? = { links[contestantKey(it)] }

    @Test
    fun theWeekStartsFridaySixPm() {
        assertEquals(WEEK, weekStart(NOW, IST))
        assertEquals(WEEK, weekStart(WEEK, IST))
        assertEquals(WEEK - 7 * 24 * HOUR_MS, weekStart(WEEK - 1, IST)) // just before: still last week
    }

    @Test
    fun overlappingCountsAreNotCountedTwice() {
        val h = RunHistory(tmp.newFolder(), nowMs = { NOW })
        // Two counts 2 hours apart over the same hours: 1,000 tweets an hour for 10 hours, so 10,000, not 20,000.
        h.saveTagline(count(NOW - 2 * HOUR_MS, Triple("QAZI THE POWERHOUSE", 1_000, 10)))
        h.saveTagline(count(NOW, Triple("QAZI THE POWERHOUSE", 1_000, 10)))
        val q = weekStats(h, of, WEEK, NOW + 1).single()
        assertEquals("Qazi", q.name); assertEquals(12_000L, q.tweets) // 12 distinct hours across both counts
        assertEquals(1_200L, q.originals); assertEquals(1_000L, q.peakHour); assertEquals(2, q.taglineWins)
    }

    @Test
    fun everySourceAddsUpIntoOneScoreWithItsFacts() {
        val h = RunHistory(tmp.newFolder(), nowMs = { NOW })
        h.saveTagline(count(NOW, Triple("QAZI THE POWERHOUSE", 3_000, 10), Triple("WE WANT NAKUL BACK", 1_000, 10)))
        h.saveBoss(BossMeterResult("1", 100, listOf(TagCount("#Qazi", 30), TagCount("#Nakul", 50), TagCount("#Uditi", 20)), NOW - HOUR_MS), "l", "contestants")
        h.saveBoss(BossMeterResult("2", 100, listOf(TagCount("#Nakul", 60), TagCount("#Qazi", 40)), NOW), "l", "finalists", final = true)
        h.recordTrending(NOW, "QAZI THE POWERHOUSE", 2)
        val r = relevance(weekStats(h, of, WEEK, NOW + 1))
        assertEquals(listOf("Qazi", "Nakul", "Uditi"), r.map { it.week.name })
        val q = r[0]; val n = r[1]
        assertTrue(q.score in 1..100 && n.score < q.score)
        assertEquals(55.0, n.week.bossShare!!, 1e-9) // average over the two posts: (50% + 60%) / 2
        assertEquals(1, n.week.bossWins); assertEquals(0, q.week.bossWins)
        assertEquals(2, q.week.bestTrending); assertNull(n.week.bestTrending)
        assertTrue(q.factsLine, q.factsLine.startsWith("30K tagline tweets (3.0K original) · 1 tagline win · 35% Boss Meter votes · best #2 Trending"))
        assertEquals(0L, r[2].week.tweets) // Uditi only came from Boss Meter
    }

    @Test
    fun aFactNobodyHasIsLeftOutSoTheTopCanReach100() {
        val r = relevance(listOf(ContestantWeek("A", 100, 10, 1, 1, 50, null, 0, 0, null), ContestantWeek("B", 50, 5, 1, 0, 25, null, 0, 0, null)))
        assertEquals(100, r[0].score) // best at every fact that exists this week
        assertTrue(r[0].parts.keys.none { it.startsWith("Boss") || it == "Trending" })
    }

    @Test
    fun theReadoutMayOnlyQuoteNumbersFromTheData() {
        val facts = "\"Q\": 49,107 tweets (62% of all) · leads #2 by 32,746\n- Busiest 7:30 PM: 18,000 tweets"
        val ok = TaglineAnalysis("Q leads with 49,107 tweets", listOf("Leads by 32,746", "Grew 38% overnight", "Top 3 hours"), "Peak was 18,000 an hour", "", "", null)
        assertEquals(listOf("Leads by 32,746", "Top 3 hours"), checkNumbers(ok, facts).insights) // the made-up 38% line is dropped
        assertThrows(LlmException::class.java) { checkNumbers(ok.copy(headline = "Q leads with 51,000 tweets"), facts) }
    }
}
