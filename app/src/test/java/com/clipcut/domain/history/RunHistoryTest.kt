package com.clipcut.domain.history

import com.clipcut.domain.taglines.CountReport
import com.clipcut.domain.taglines.CountSeries
import com.clipcut.domain.taglines.HourBucket
import com.clipcut.domain.taglines.TaglineCount
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

private const val DAY = 24 * 3_600_000L
private const val NOW = 1_792_300_000_000L

private fun report(at: Long, n: Long = 100) =
    CountReport(at - DAY, at, at, listOf(TaglineCount("QAZI THE POWERHOUSE OF BB20", CountSeries(n, listOf(HourBucket(at - 3_600_000L, n))), CountSeries(n / 10, emptyList()))))

class RunHistoryTest {
    @get:Rule val tmp = TemporaryFolder()

    @Test
    fun everyRunIsKeptNotReplaced() {
        val h = RunHistory(tmp.newFolder("history"), nowMs = { NOW })
        assertTrue(h.isEmpty)
        h.saveTagline(report(NOW - 2 * DAY, 10)); h.saveTagline(report(NOW - DAY, 20)); h.saveTagline(report(NOW, 30))
        assertEquals(listOf(10L, 20L, 30L), h.taglineRuns().map { it.counts.single().all.total }) // oldest first
        assertEquals(listOf(30L), h.taglineRuns(NOW).map { it.counts.single().all.total })
        assertEquals(HistorySummary(2, 0), h.summary(NOW - DAY))
        assertFalse(h.isEmpty)
    }

    @Test
    fun trendingPositionsAreKeptAndOldRunsArePruned() {
        val dir = tmp.newFolder("history")
        RunHistory(dir, nowMs = { NOW }).apply {
            saveTagline(report(NOW - 100 * DAY)); saveTagline(report(NOW))
            recordTrending(NOW, "QAZI THE POWERHOUSE OF BB20", 2)
            prune()
        }
        val h = RunHistory(dir, nowMs = { NOW }) // reopened: everything is on disk
        assertEquals(1, h.taglineRuns().size) // the 100-day-old count is gone
        assertEquals(TrendingMark(NOW, "QAZI THE POWERHOUSE OF BB20", 2, NOW), h.trending().single())
        assertTrue(dir.listFiles()!!.none { it.name.endsWith(".tmp") })
    }

    @Test
    fun contestantGuessesFromTaglinesAndHashtags() {
        assertEquals("Qazi", suggestContestant("QAZI THE POWERHOUSE OF BB20", emptyList()))
        assertEquals("Nakul", suggestContestant("WE WANT NAKUL BACK", emptyList()))
        assertEquals("Qazi", suggestContestant("STOP NEGATIVE PR AGAINST QAZI", emptyList()))
        assertEquals("Uditi", suggestContestant("UDITI STANDS BY HER PEOPLE", emptyList()))
        assertEquals("Kanika", suggestContestant("#KanikaIsTheBoss", emptyList()))
        assertEquals("Aamrapali", suggestContestant("#AamrapaliIsTheBoss", emptyList()))
        // A name linked before wins, wherever it sits in the tagline.
        assertEquals("Qazi", suggestContestant("JUSTICE FOR QAZI", listOf("Qazi")))
        assertNull(suggestContestant("WE WANT JUSTICE", emptyList()))
        assertEquals("kanikaistheboss", contestantKey("#KanikaIsTheBoss")); assertEquals("we want nakul back", contestantKey("  WE  WANT NAKUL BACK "))
    }
}
