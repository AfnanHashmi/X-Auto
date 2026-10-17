package com.clipcut.domain.share

import com.clipcut.domain.taglines.CountReport
import com.clipcut.domain.taglines.CountSeries
import com.clipcut.domain.taglines.HOUR_MS
import com.clipcut.domain.taglines.HourBucket
import com.clipcut.domain.taglines.TaglineCount
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.ZoneId

private val IST = ZoneId.of("Asia/Kolkata")
private const val FETCHED = 1_792_170_839_216L // a real count's times, from the phone
private const val END = FETCHED - 30_000L
private const val START = END - 24 * HOUR_MS // 13 min 29 s past a UTC hour, as X's first bucket starts

private fun hour(i: Int) = Math.floorDiv(START, HOUR_MS) * HOUR_MS + i * HOUR_MS

/** A tagline with tweets in the given grid hours (0 = the partial first hour). Originals are a tenth, as in real counts. */
private fun tagline(name: String, vararg hours: Pair<Int, Long>): TaglineCount {
    val buckets = (0..24).map { i -> HourBucket(if (i == 0) START else hour(i), hours.firstOrNull { it.first == i }?.second ?: 0L) }
    val total = buckets.sumOf { it.count }
    return TaglineCount(name, CountSeries(total, buckets), CountSeries(total / 10, emptyList()))
}

private fun report(vararg t: TaglineCount) = CountReport(START, END, FETCHED, t.toList())

class TaglineShareTest {
    @Test
    fun oneTaglineHasAllItsDetailsButNoRankShareOrBar() {
        val c = taglineShareCard(report(tagline("QAZI BORN TO SHINE", 12 to 600, 13 to 18_000, 14 to 900)), "17 Oct 2026, 9:00 PM", IST)
        val s = c.stats.single()
        assertFalse(c.compares)
        assertNull(s.rank); assertNull(s.sharePercent); assertNull(s.fraction); assertNull(s.shareText); assertNull(s.trending)
        assertEquals(19_500L, s.total); assertEquals(1_950L, s.originals); assertEquals(17_550L, s.retweets); assertEquals(90, s.retweetPercent)
        assertEquals(18_000L, s.peak!!.count); assertEquals(300L, s.peak!!.perMinute) // a full hour: 18,000 / 60
        assertEquals(3, s.activeHours)
        assertEquals(108L, s.averagePerMinute) // 19,500 over 3 hours = 108.3 a minute
        val lines = s.detailLines(IST)
        assertEquals(5, lines.size)
        assertTrue(lines[0], lines[0].endsWith("9.0 retweets per original"))
        assertEquals("10K within 2 h  ·  1 hour above 1K/hour", lines[3]) // 1K and 10K both fell in the same hour: only 10K shown
        assertEquals("Last full hour: 0 (gone quiet)", lines[4]) // only 3 active hours, so no "3 busiest hours" figure
        assertTrue(lines[0], lines[0].startsWith("1,950 original  ·  17,550 retweets (90%)"))
        assertTrue(lines[1], lines[1].contains("18,000 tweets (about 300 a minute)"))
        assertTrue(lines[2], lines[2].startsWith("First seen") && lines[2].endsWith("active over 3 hours  ·  average 108 a minute"))
        assertNotNull(c.graph); assertEquals(25, c.graph!!.hours)
        assertFalse(c.footer.contains("across")) // no "total across taglines" for one tagline
        assertTrue(c.caption, c.caption.startsWith("📊 LIVE SCORE · \"QAZI BORN TO SHINE\" on X") && c.caption.contains("19,500 tweets"))
        assertTrue(xLength(c.caption) <= X_POST_LIMIT)
    }

    @Test
    fun severalTaglinesAreRankedWithSharesAndBars() {
        val c = taglineShareCard(
            report(tagline("B", 10 to 300), tagline("A", 10 to 600), tagline("C", 11 to 100)),
            "today", IST,
        )
        assertTrue(c.compares)
        assertEquals(listOf("A", "B", "C"), c.stats.map { it.tagline }) // highest first
        assertEquals(listOf(1, 2, 3), c.stats.map { it.rank })
        assertEquals(listOf(1, 0, 2), c.stats.map { it.colorIndex }) // colour stays tied to the graph line (report order)
        assertEquals(100.0, c.stats.sumOf { it.sharePercent!! }, 1e-9)
        assertEquals("60% of all  ·  leads #2 by 300", c.stats[0].shareText)
        assertEquals(1f, c.stats[0].fraction!!, 0f); assertEquals(0.5f, c.stats[1].fraction!!, 1e-6f)
        assertTrue(c.footer.startsWith("1,000 tweets across 3 taglines"))
        assertTrue(c.caption.contains("🥇 A: 600") && c.caption.contains("🥈 B: 300") && c.caption.contains("🥉 C: 100"))
    }

    @Test
    fun equalTotalsShareARank() {
        val c = taglineShareCard(report(tagline("A", 5 to 50), tagline("B", 6 to 50), tagline("C", 7 to 10)), "today", IST)
        assertEquals(listOf(1, 1, 3), c.stats.map { it.rank })
    }

    @Test
    fun aTaglineWithNoTweetsDoesNotBreakAnything() {
        val c = taglineShareCard(report(tagline("A", 5 to 50), tagline("EMPTY")), "today", IST)
        val empty = c.stats.last()
        assertEquals(0L, empty.total); assertEquals(2, empty.rank); assertEquals(0.0, empty.sharePercent!!, 0.0); assertEquals(0f, empty.fraction!!, 0f)
        assertNull(empty.peak); assertNull(empty.activeHours); assertNull(empty.averagePerMinute); assertNull(empty.retweetPercent)
        assertEquals(listOf("No tweets in this period"), empty.detailLines(IST))
    }

    @Test
    fun allZeroLeavesOutTheGraphAndPercentages() {
        val one = taglineShareCard(report(tagline("A")), "today", IST)
        assertNull(one.graph)
        assertEquals(listOf("No tweets in this period"), one.stats.single().detailLines(IST))
        val two = taglineShareCard(report(tagline("A"), tagline("B")), "today", IST)
        assertNull(two.graph)
        assertTrue(two.stats.all { it.sharePercent == null && it.rank == 1 && it.fraction == 0f })
        assertTrue(two.footer.startsWith("0 tweets across 2 taglines"))
    }

    @Test
    fun aPeakInThePartialFirstHourUsesItsRealLength() {
        // The first bucket runs from START (13.5 min past the hour) to the next hour: about 46.5 minutes.
        val s = taglineShareCard(report(tagline("A", 0 to 930)), "today", IST).stats.single()
        assertEquals(START, s.peak!!.startMs); assertEquals(hour(1), s.peak!!.endMs)
        assertEquals(20L, s.peak!!.perMinute) // 930 / 46.5
        assertEquals(1, s.activeHours) // under an hour still reads as 1
        val slow = taglineShareCard(report(tagline("A", 4 to 20)), "today", IST).stats.single()
        assertTrue(slow.detailLines(IST)[1].contains("under 1 a minute"))
    }

    @Test
    fun trendingPositionsShowOnlyWhereTyped() {
        val c = taglineShareCard(report(tagline("A", 5 to 50), tagline("B", 6 to 20)), "today", IST, mapOf("B" to 3))
        assertNull(c.stats.first { it.tagline == "A" }.trendingText)
        assertEquals("#3 Trending on X", c.stats.first { it.tagline == "B" }.trendingText)
        assertTrue(c.caption, c.caption.contains("B: 20 (29%) · #3 Trending"))
        assertFalse(c.caption.contains("A: 50 ·"))
        val single = taglineShareCard(report(tagline("A", 5 to 50)), "today", IST, mapOf("A" to 1))
        assertTrue(single.caption.contains("🔥 #1 Trending on X"))
        val none = taglineShareCard(report(tagline("A", 5 to 50)), "today", IST)
        assertFalse(none.caption.contains("Trending"))
    }

    @Test
    fun positionInputIsOptionalAndChecked() {
        assertEquals(PositionInput.Ok(null), parsePosition(""))
        assertEquals(PositionInput.Ok(null), parsePosition("   "))
        assertEquals(PositionInput.Ok(3), parsePosition("3"))
        assertEquals(PositionInput.Ok(3), parsePosition(" #3 "))
        assertEquals(PositionInput.Ok(50), parsePosition("50"))
        assertTrue(parsePosition("0") is PositionInput.Invalid)
        assertTrue(parsePosition("51") is PositionInput.Invalid)
        assertTrue(parsePosition("#") is PositionInput.Ok) // just "#" is treated as blank
        assertTrue(parsePosition("3a") is PositionInput.Invalid)
    }

    @Test
    fun tenLongTaglinesStillFitOnePost() {
        val many = (1..10).map { tagline("A VERY LONG TAGLINE NUMBER $it FOR THE SHOW THAT KEEPS GOING", it to it * 1000L) }
        val c = taglineShareCard(report(*many.toTypedArray()), "17 Oct 2026, 9:00 PM", IST, many.associate { it.tagline to 1 })
        assertEquals(10, c.stats.size)
        assertTrue(xLength(c.caption) <= X_POST_LIMIT)
        assertTrue(c.caption.contains("more")) // lines that do not fit are summarised, not cut mid-word
        assertTrue(c.caption.endsWith("Counted with the X API")) // no AI parts, so no Grok credit
    }

    @Test
    fun freeStatsFromTheHourlyNumbers() {
        // Hours 10..15: 400, 800, 5,000, 20,000, 30,000, 9,000, then the last full hour (23) has 2,000 after 1,000 in hour 22.
        val t = tagline("A", 10 to 400, 11 to 800, 12 to 5_000, 13 to 20_000, 14 to 30_000, 15 to 9_000, 22 to 1_000, 23 to 2_000)
        val s = taglineStat(report(t), t)
        assertEquals(68_200L, s.total)
        assertEquals(listOf(1_000L, 10_000L, 50_000L), s.milestones.map { it.tweets })
        assertEquals(listOf(2 * HOUR_MS, 4 * HOUR_MS, 5 * HOUR_MS), s.milestones.map { it.withinMs })
        assertEquals(6, s.busyHours) // 5,000 20,000 30,000 9,000 1,000 2,000
        assertEquals(2_000L, s.lastHour!!.count); assertEquals(1_000L, s.lastHour!!.previous); assertFalse(s.lastHour!!.isPeak)
        assertEquals(87, s.topThreeHoursPercent) // (30,000 + 20,000 + 9,000) / 68,200
        assertEquals(9.0, s.retweetsPerOriginal!!, 0.01)
        val lines = s.detailLines(IST)
        assertTrue(lines.toString(), lines.contains("10K within 4 h  ·  50K within 5 h  ·  6 hours above 1K/hour"))
        assertTrue(lines.toString(), lines.contains("Last full hour: 2,000 (↑ from 1,000)  ·  3 busiest hours: 87% of tweets"))

        val falling = tagline("B", 20 to 5_000, 21 to 3_000, 22 to 2_000, 23 to 1_000)
        assertTrue(taglineStat(report(falling), falling).detailLines(IST).any { it.startsWith("Last full hour: 1,000 (↓ 80% from peak)") })
        val atPeak = tagline("C", 21 to 100, 22 to 300, 23 to 900)
        assertTrue(taglineStat(report(atPeak), atPeak).detailLines(IST).any { it.startsWith("Last full hour was its busiest (900)") })
        val allRetweets = TaglineCount("D", t.all, CountSeries(0, emptyList()))
        assertNull(taglineStat(report(allRetweets), allRetweets).retweetsPerOriginal)
    }

    @Test
    fun leadOverTheNextTaglineOnlyWhenAhead() {
        val c = taglineShareCard(report(tagline("A", 5 to 900), tagline("B", 6 to 500), tagline("C", 7 to 500), tagline("D")), "today", IST)
        assertEquals(listOf(400L, null, null, null), c.stats.map { it.leadOverNext }) // B and C are tied; C is not said to lead D, which has no tweets
        assertEquals("47% of all  ·  leads #2 by 400", c.stats[0].shareText)
        assertEquals("26% of all", c.stats[1].shareText)
        assertEquals("26% of all", c.stats[2].shareText)
        val one = taglineShareCard(report(tagline("A", 5 to 900)), "today", IST).stats.single()
        assertNull(one.leadOverNext); assertNull(one.shareText)
    }
}
