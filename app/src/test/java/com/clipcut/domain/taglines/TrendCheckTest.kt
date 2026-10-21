package com.clipcut.domain.taglines

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

private const val T0 = 1_792_000_000_000L - (1_792_000_000_000L % HOUR_MS)

private fun hours(vararg counts: Long, from: Long = T0) = counts.mapIndexed { i, c -> HourBucket(from + i * HOUR_MS, c) }

class TrendCheckTest {
    // ---- the range -------------------------------------------------------------------------------------------------

    @Test
    fun theRangeStartsAtTheFirstBusyHourAndEndsAfterTheLastOne() {
        val h = hours(0, 0, 0, 2, 200, 3000, 5000, 2500, 800, 100, 0, 0, 0, 0)
        val r = detectRange(h, nowMs = T0 + 20 * HOUR_MS)!!
        assertEquals(T0 + 4 * HOUR_MS, r.startMs) // 200 is the first hour above 2% of the 5,000 peak; 2 is background
        assertEquals(T0 + 10 * HOUR_MS, r.endMs)  // the 100-tweet hour is the last busy one, so it ends when that hour does
        assertTrue(r.ended)
        assertEquals(6, r.hours)
    }

    @Test
    fun aTrendThatIsStillRunningIsNotCalledEnded() {
        val h = hours(0, 0, 400, 2000, 4000, 4500)
        val now = T0 + 5 * HOUR_MS + 30 * 60_000L
        val r = detectRange(h, now)!!
        assertFalse(r.ended)
        assertEquals(now, r.endMs) // cut at now, not at the end of the current hour
        // two quiet hours after the last busy one is what ends a trend
        assertTrue(detectRange(hours(0, 400, 4000, 0, 0), T0 + 5 * HOUR_MS)!!.ended)
        assertFalse(detectRange(hours(0, 400, 4000, 0, 0), T0 + 4 * HOUR_MS + 30 * 60_000L)!!.ended)
    }

    @Test
    fun nothingBusyMeansNoRange() {
        assertNull(detectRange(hours(0, 1, 3, 2, 0), T0 + 10 * HOUR_MS)) // never reached 5 an hour
        assertNull(detectRange(emptyList(), T0))
    }

    @Test
    fun aConstantlyBusyBackgroundDoesNotCountAsTheTrend() {
        // 40 tweets an hour all week, then a burst: the threshold is 3 times the typical hour (120), so the 90 is not "busy".
        val h = hours(*(List(30) { 40L } + listOf(90L, 3000L, 6000L, 2000L, 80L, 40L, 40L, 40L, 40L)).toLongArray())
        val r = detectRange(h, T0 + 100 * HOUR_MS)!!
        assertEquals(T0 + 31 * HOUR_MS, r.startMs)
    }

    // ---- launch and waves ------------------------------------------------------------------------------------------

    @Test
    fun launchIsSuddenFastOrGradual() {
        assertEquals(LaunchType.SUDDEN, launchType(hours(10, 2000, 5000, 3000, 900)))
        assertEquals(LaunchType.FAST, launchType(hours(100, 300, 700, 1200, 2000, 3200, 4300, 5000)))
        assertEquals(LaunchType.GRADUAL, launchType(hours(500, 600, 900, 1400, 2000, 2600, 3300, 4200, 5000)))
        assertNull(launchType(hours(0, 0, 0)))
    }

    @Test
    fun pushWavesAreTheHoursThatJumpToAtLeastAThirdOfThePeak() {
        val h = hours(0, 10, 2000, 2100, 300, 3000, 100)
        assertEquals(listOf(T0 + 2 * HOUR_MS, T0 + 5 * HOUR_MS), pushWaves(h)) // 2,100 is not a new wave: the hour before was already 2,000
        assertTrue(pushWaves(hours(0, 0)).isEmpty())
    }

    // ---- spending the budget -----------------------------------------------------------------------------------------

    @Test
    fun theBudgetIsSpreadByVolumeAndNeverPassed() {
        val h = hours(500, 3000, 5000, 2000, 500)
        val p = planSlices(h, emptySet(), 1.00, 0.005)!!
        assertTrue(p.postsPlanned <= 200)
        assertTrue(p.maxUsd <= 1.00 + 1e-9)
        assertTrue(p.slices.all { it.posts >= MIN_PER_CALL })
        assertEquals(h.map { it.startMs }, p.hoursCovered) // every busy hour is in some call, the launch and the tail included
        assertEquals(T0, p.slices.first().startMs); assertEquals(T0 + 5 * HOUR_MS, p.slices.last().endMs)
    }

    @Test
    fun noBudgetEverBuysMoreThanItsMoney() {
        val shapes = listOf(
            hours(500, 3000, 5000, 2000, 500),
            hours(1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1),
            hours(9000, 8, 3, 7000, 2, 0, 0, 120),
            hours(100000),
        )
        for (shape in shapes) for (b in listOf(0.10, 0.17, 0.25, 0.5, 1.0, 2.0, 3.0)) for (c in listOf(0.005, 0.0078, 0.02)) {
            val p = planSlices(shape, emptySet(), b, c) ?: continue
            assertTrue("budget $b cost $c planned ${p.postsPlanned}", p.postsPlanned * c <= b + 1e-9)
            assertEquals(p.postsPlanned, p.slices.sumOf { it.posts })
        }
    }

    @Test
    fun aBudgetThatCannotBuyOneCallPlansNothing() {
        assertNull(planSlices(hours(500, 3000), emptySet(), 0.04, 0.005)) // 8 posts, X's minimum is 10
        assertNull(planSlices(hours(500, 3000), emptySet(), 1.0, 0.0))
    }

    @Test
    fun hoursAlreadyReadAreNotBoughtAgain() {
        val h = hours(500, 3000, 5000, 2000, 500)
        val read = setOf(T0, T0 + HOUR_MS, T0 + 2 * HOUR_MS)
        val p = planSlices(h, read, 1.00, 0.005)!!
        assertEquals(listOf(T0 + 3 * HOUR_MS, T0 + 4 * HOUR_MS), p.hoursCovered)
        assertTrue(p.slices.all { it.startMs >= T0 + 3 * HOUR_MS })
        assertTrue(planSlices(h, h.map { it.startMs }.toSet(), 1.00, 0.005)!!.slices.isEmpty()) // all read: nothing to buy
    }

    @Test
    fun aGapOfSkippedHoursIsNeverSpannedByOneCall() {
        val h = hours(2000, 2000, 2000, 2000, 2000)
        val p = planSlices(h, setOf(T0 + 2 * HOUR_MS), 1.00, 0.005)!!
        assertTrue(p.slices.size >= 2)
        assertTrue(p.slices.none { it.startMs <= T0 + 2 * HOUR_MS && it.endMs > T0 + 2 * HOUR_MS })
    }

    @Test
    fun aSmallTrendIsReadInFullAndNotPaddedBeyondTheMinimumCall() {
        val p = planSlices(hours(3, 4), emptySet(), 1.00, 0.005)!!
        assertEquals(1, p.slices.size); assertEquals(MIN_PER_CALL, p.slices[0].posts) // 7 posts exist; X's smallest call asks for 10
    }

    // ---- exact shares ----------------------------------------------------------------------------------------------

    @Test
    fun sharesAreWholePercentsOfTheCounts() {
        val s = CountShares(all = 49_107, originals = 4_910, replies = 983, quotes = 491)
        assertEquals(90, s.retweetPct); assertEquals(10, s.originalPct); assertEquals(2, s.replyPct); assertEquals(1, s.quotePct)
        assertEquals(0, CountShares(0, 0, 0, 0).retweetPct)
        assertNotNull(usdText(0.5)); assertEquals("$0.50", usdText(0.5))
    }
}
