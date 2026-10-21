package com.clipcut.domain.history

import com.clipcut.domain.taglines.CountReport
import com.clipcut.domain.taglines.CountSeries
import com.clipcut.domain.taglines.FX_END
import com.clipcut.domain.taglines.FX_START
import com.clipcut.domain.taglines.HOUR_MS
import com.clipcut.domain.taglines.HourBucket
import com.clipcut.domain.taglines.Sentiment
import com.clipcut.domain.taglines.TaglineCount
import com.clipcut.domain.taglines.fxCheck
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

private const val UNTIL = FX_END + 24 * HOUR_MS

private fun count(tagline: String, n: Long) = TaglineCount(tagline, CountSeries(n, listOf(HourBucket(FX_END - HOUR_MS, n))), CountSeries(n / 10, listOf(HourBucket(FX_END - HOUR_MS, n / 10))))

class TrendRelevanceTest {
    @get:Rule val tmp = TemporaryFolder()

    private val of: (String) -> String? = { t -> if (t.contains("QAZI")) "Qazi Jee" else if (t.contains("NAKUL")) "Nakul" else null }

    private fun week(checks: List<com.clipcut.domain.taglines.TrendCheckResult>, tweets: Pair<Long, Long> = 40_000L to 40_000L): List<ContestantWeek> {
        val h = RunHistory(tmp.newFolder(), nowMs = { UNTIL - 1 })
        h.saveTagline(CountReport(FX_START, FX_END, FX_END + 30_000, listOf(count("QAZI RISING", tweets.first), count("WE WANT NAKUL", tweets.second))))
        return weekStats(h, of, FX_START, UNTIL, checks = checks)
    }

    @Test
    fun theWeightsStillSumToOne() {
        assertEquals(1.0, RELEVANCE_WEIGHTS.values.sum(), 1e-9)
        assertTrue("Natural support" in RELEVANCE_WEIGHTS && "Positive mood" in RELEVANCE_WEIGHTS)
    }

    @Test
    fun naturalTweetsAreTheTweetsTimesTheNaturalShareOfTheCheck() {
        val w = week(listOf(fxCheck("QAZI RISING", natural = 80, all = 40_000), fxCheck("WE WANT NAKUL", natural = 20, all = 40_000)))
        assertEquals(32_000L, w.first { it.name == "Qazi Jee" }.naturalTweets)
        assertEquals(8_000L, w.first { it.name == "Nakul" }.naturalTweets)
    }

    @Test
    fun theMoreNaturalSupportScoresHigherWhenEverythingElseIsEqual() {
        val w = week(listOf(fxCheck("QAZI RISING", natural = 80, all = 40_000), fxCheck("WE WANT NAKUL", natural = 20, all = 40_000)))
        val r = relevance(w)
        val q = r.first { it.week.name == "Qazi Jee" }; val n = r.first { it.week.name == "Nakul" }
        assertTrue("${q.score} vs ${n.score}", q.score > n.score)
        assertTrue("Natural support" in q.parts)
        assertTrue(q.factsLine.contains("~80% natural"))
    }

    @Test
    fun noCheckMeansTheFactIsLeftOutNotScoredAsZero() {
        // Only Qazi's tagline was checked. Nakul is scored on the rest, with his weights scaled up, and is not punished.
        val w = week(listOf(fxCheck("QAZI RISING", natural = 80, all = 40_000).copy(sentiment = null)))
        val n = w.first { it.name == "Nakul" }
        assertNull(n.naturalTweets); assertNull(n.mood)
        val r = relevance(w)
        val nr = r.first { it.week.name == "Nakul" }
        assertFalse("Natural support" in nr.parts)
        assertTrue(nr.score > 0)
        // equal tweets and nothing else: both land on the same score on the facts they share, Qazi's natural support only adds
        assertTrue(r.first { it.week.name == "Qazi Jee" }.score >= nr.score)
        assertFalse(nr.factsLine.contains("natural"))
    }

    @Test
    fun moodNeedsEnoughSampledPostsAndRunsFromMinusOneToPlusOne() {
        val enough = fxCheck("QAZI RISING").copy(sentiment = Sentiment(30, 10, 10, mapOf("Qazi Jee" to Triple(30, 5, 5), "Nakul" to Triple(1, 3, 1)), 50, "OpenAI", null))
        val w = week(listOf(enough))
        assertEquals(0.625, w.first { it.name == "Qazi Jee" }.mood!!, 1e-9) // (30 - 5) / 40
        assertNull(w.firstOrNull { it.name == "Nakul" }?.mood) // 5 posts about him: below the minimum
        assertEquals(40, w.first { it.name == "Qazi Jee" }.moodPosts)
        val r = relevance(w).first { it.week.name == "Qazi Jee" }
        assertTrue("Positive mood" in r.parts); assertTrue(r.factsLine.contains("mood +63"))
    }

    @Test
    fun aCheckFromOutsideTheWeekIsIgnored() {
        val old = fxCheck("QAZI RISING", atMs = FX_START - 40 * HOUR_MS)
        assertNull(week(listOf(old)).first { it.name == "Qazi Jee" }.naturalTweets)
    }

    @Test
    fun aCheckWithOnlyExactCountsAddsNoNaturalFigure() {
        assertNotNull(week(listOf(fxCheck("QAZI RISING"))).first { it.name == "Qazi Jee" }.naturalTweets)
        assertNull(week(listOf(fxCheck("QAZI RISING", withSample = false))).first { it.name == "Qazi Jee" }.naturalTweets)
    }
}
