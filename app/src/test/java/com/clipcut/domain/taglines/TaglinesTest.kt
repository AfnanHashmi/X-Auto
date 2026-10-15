package com.clipcut.domain.taglines

import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant
import java.time.ZoneId

private const val HOUR = 3_600_000L
private val T0 = Instant.parse("2026-10-14T08:36:00Z").toEpochMilli()
private val IST = ZoneId.of("Asia/Kolkata")

private fun json(counts: List<Long>, total: Long = counts.sum(), start: Long = T0): String {
    val rows = counts.mapIndexed { i, c -> """{"start":"${Instant.ofEpochMilli(start + i * HOUR)}","end":"${Instant.ofEpochMilli(start + (i + 1) * HOUR)}","tweet_count":$c}""" }
    return """{"data":[${rows.joinToString(",")}],"meta":{"total_tweet_count":$total}}"""
}

private fun series(counts: List<Long>, start: Long = T0) = CountSeries(counts.sum(), counts.mapIndexed { i, c -> HourBucket(start + i * HOUR, c) })

private class FakeApi : TaglinesApi {
    var configured = true
    var failAfter: Int? = null
    var failWith: Exception = TaglineApiException("boom", 500)
    val queries = mutableListOf<String>()
    override val isConfigured get() = configured
    override suspend fun counts(query: String, startMs: Long, endMs: Long): CountSeries {
        if (failAfter != null && queries.size >= failAfter!!) throw failWith
        queries += query
        return series(if ("-is:retweet" in query) listOf(1, 2) else listOf(10, 20))
    }
}

class TaglinesTest {
    @Test
    fun hourlyNumbersMustAddUpToXsTotal() {
        val ok = parseCounts(json(listOf(0, 5, 20)))
        assertEquals(25L, ok.total)
        assertEquals(3, ok.hours.size)
        assertThrows(TaglineParseException::class.java) { parseCounts(json(listOf(0, 5, 20), total = 26)) }
    }

    @Test
    fun noMatchesIsAValidZeroAndNonsenseIsRefused() {
        assertEquals(0L, parseCounts("""{"meta":{"total_tweet_count":0}}""").total)
        assertThrows(TaglineParseException::class.java) { parseCounts("not json") }
        assertThrows(TaglineParseException::class.java) { parseCounts("""{"data":[]}""") }
        assertThrows(TaglineParseException::class.java) { parseCounts("""{"data":[{"start":"x","tweet_count":1}],"meta":{"total_tweet_count":1}}""") }
        assertThrows(TaglineParseException::class.java) { parseCounts("""{"data":[{"start":"2026-10-14T08:00:00Z"}],"meta":{"total_tweet_count":0}}""") }
    }

    @Test
    fun taglinesAreCleanedAndQueriedAsExactPhrases() {
        assertEquals("QAZI BORN TO SHINE", cleanTagline("  \"QAZI   BORN TO SHINE\"  "))
        assertNull(cleanTagline("  \"\" "))
        assertEquals("\"A B\"", phraseQuery("A B", withRetweets = true))
        assertEquals("\"A B\" -is:retweet", phraseQuery("A B", withRetweets = false))
        assertEquals(0.03, estimatedCostUsd(3), 1e-9)
    }

    @Test
    fun countsEachTaglineTwiceAndOnlyAsksWhenTold() = runTest {
        val api = FakeApi()
        val out = TaglinesService(api, nowMs = { T0 + 24 * HOUR }).count(listOf("A B", "C D", "a b")) as CountOutcome.Done
        assertEquals(listOf("A B", "C D"), out.report.counts.map { it.tagline }) // the repeat is dropped
        assertEquals(4, api.queries.size)
        assertEquals(30L, out.report.counts[0].all.total)
        assertEquals(3L, out.report.counts[0].originals.total)
        assertEquals(24 * HOUR, out.report.endMs - out.report.startMs)
    }

    @Test
    fun nothingIsSentWithoutKeysTaglinesOrWithTooMany() = runTest {
        val api = FakeApi().apply { configured = false }
        assertTrue(TaglinesService(api).count(listOf("A")) is CountOutcome.Failed)
        api.configured = true
        assertTrue(TaglinesService(api).count(emptyList()) is CountOutcome.Failed)
        assertTrue(TaglinesService(api).count(List(MAX_TAGLINES + 1) { "t$it" }) is CountOutcome.Failed)
        assertTrue(api.queries.isEmpty())
    }

    @Test
    fun aFailureKeepsNothingAndSaysHowManyCallsWentThrough() = runTest {
        val api = FakeApi().apply { failAfter = 3; failWith = TaglineApiException("Forbidden", 403) }
        val out = TaglinesService(api).count(listOf("A", "B", "C")) as CountOutcome.Failed
        assertEquals(3, out.callsMade)
        assertTrue(out.reason.contains("3 call"))
        assertTrue(out.reason.contains("403"))
        val limited = FakeApi().apply { failAfter = 0; failWith = TaglineApiException("x", 429) }
        assertTrue((TaglinesService(limited).count(listOf("A")) as CountOutcome.Failed).reason.contains("too many requests"))
        val gone = FakeApi().apply { failAfter = 0; failWith = TaglineApiException("timeout", null) }
        assertTrue((TaglinesService(gone).count(listOf("A")) as CountOutcome.Failed).reason.contains("did not reply"))
    }

    private fun report(): CountReport {
        // Quiet for 5 hours, then a burst: one phrase starts first.
        val a = series(listOf(0, 0, 0, 0, 0, 600, 2100, 1800, 500, 300, 100), T0)
        val b = series(listOf(0, 0, 0, 0, 0, 0, 1800, 1500, 400, 200, 100), T0)
        return CountReport(
            T0, T0 + 11 * HOUR, T0 + 11 * HOUR,
            listOf(
                TaglineCount("FIRST", a, series(listOf(0, 0, 0, 0, 0, 50, 200, 100, 40, 20, 10), T0)),
                TaglineCount("SECOND", b, series(listOf(0, 0, 0, 0, 0, 0, 150, 100, 30, 10, 10), T0)),
            ),
        )
    }

    @Test
    fun factsComeOnlyFromTheCounts() {
        val r = report()
        val c = r.counts[0]
        assertEquals(5400L, c.all.total)
        assertEquals(2100L, c.peak!!.count)
        assertEquals(T0 + 5 * HOUR, c.firstActive!!.startMs)
        assertEquals(420L, c.originals.total)
        assertEquals(1.0 - 420.0 / 5400, c.retweetShare, 1e-9)
        val lines = insightLines(r, IST)
        assertTrue(lines.first().startsWith("A sudden start"))
        assertTrue(lines.first().contains("FIRST"))
        assertTrue(lines.any { it.contains("Mostly retweets") })
        assertTrue(lines.any { it.contains("moved together") })
        // never claims a cause
        assertFalse(lines.joinToString(" ").contains("coordinated", ignoreCase = true))
    }

    @Test
    fun anEmptyReportSaysSoAndAlreadyActiveIsNotCalledSudden() {
        val none = CountReport(T0, T0 + 3 * HOUR, T0 + 3 * HOUR, listOf(TaglineCount("X", series(listOf(0, 0)), series(listOf(0, 0)))))
        assertEquals(listOf("No tweets with these phrases were found in this period."), insightLines(none, IST))
        val active = CountReport(T0, T0 + 3 * HOUR, T0 + 3 * HOUR, listOf(TaglineCount("X", series(listOf(5, 5)), series(listOf(1, 1)))))
        assertTrue(insightLines(active, IST).first().startsWith("Already active"))
    }
}
