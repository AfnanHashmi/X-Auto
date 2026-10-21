package com.clipcut.domain.taglines

import com.clipcut.domain.llm.LlmException
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate
import java.time.ZoneOffset

private const val NOW = 1_792_300_000_000L - (1_792_300_000_000L % HOUR_MS) + 10 * 60_000L
private const val TAG = "QAZI RISING"

private class MemRepo : TrendCheckRepository {
    val spent = HashMap<LocalDate, Double>()
    override var costPerPost: Double? = null
    val checks = ArrayList<TrendCheckResult>()
    val samples = HashMap<String, StoredSample>()
    override fun spentOn(day: LocalDate) = spent[day] ?: 0.0
    override fun add(day: LocalDate, usd: Double) { spent[day] = spentOn(day) + usd }
    override fun all() = checks.sortedByDescending { it.atMs }
    override fun forTagline(tagline: String) = all().filter { it.tagline.equals(tagline, true) }
    override fun find(tagline: String, startMs: Long) = forTagline(tagline).firstOrNull { it.startMs == startMs }
    override fun save(r: TrendCheckResult) { checks.removeAll { it.key == r.key }; checks += r }
    override fun sample(key: String) = samples[key]
    override fun saveSample(key: String, s: StoredSample) { samples[key] = s }
}

private class FakeCounts(val hours: List<HourBucket>) : TaglinesApi {
    override val isConfigured = true
    val queries = ArrayList<String>()
    override suspend fun counts(query: String, startMs: Long, endMs: Long): CountSeries {
        queries += query
        val inside = hours.filter { it.startMs >= startMs && it.startMs < endMs }
        val factor = when { query.contains("is:reply") -> 0.02; query.contains("is:quote") -> 0.01; query.contains("-is:retweet") -> 0.1; else -> 1.0 }
        val h = inside.map { HourBucket(it.startMs, (it.count * factor).toLong()) }
        return CountSeries(h.sumOf { it.count }, h)
    }
}

private class FakeSearch(var balance: Double, val pricePerPost: Double, val failAfterCalls: Int = Int.MAX_VALUE) : TrendSearchApi {
    override val isConfigured = true
    val calls = ArrayList<Triple<Long, Long, Int>>()
    var served = 0
    override suspend fun search(query: String, startMs: Long, endMs: Long, maxResults: Int, nextToken: String?): SearchPage {
        if (calls.size >= failAfterCalls) throw TaglineApiException("boom", 500)
        calls += Triple(startMs, endMs, maxResults)
        val posts = (1..maxResults).map { i ->
            served++
            TcPost("p$served", "a${served % 40}", startMs + i * 1000L, "post number $served of the trend", "hi", PostType.ORIGINAL, 1, 0, emptyList(), emptyList(), false)
        }
        balance -= posts.size * pricePerPost
        val authors = posts.map { it.authorId }.distinct().associateWith { id ->
            TcAuthor(id, "u$id", "n$id", NOW - 800 * 24 * HOUR_MS, 300, 100, 2000, false, false, "", "")
        }
        return SearchPage(posts, authors, null)
    }
    override suspend fun balanceUsd(): Double? = balance
    override fun payerText() = "Paid from test"
}

private fun trendHours(): List<HourBucket> {
    val counts = listOf(0L, 0L, 0L, 300L, 3000L, 5000L, 2500L, 800L, 100L, 0L)
    val start = NOW - 12 * HOUR_MS
    return counts.mapIndexed { i, c -> HourBucket(start + i * HOUR_MS, c) }
}

class TrendCheckServiceTest {
    private fun judge(): suspend (List<TcPost>, List<String>) -> Sentiment = { posts, _ ->
        Sentiment(posts.size / 2, posts.size / 4, posts.size - posts.size / 2 - posts.size / 4, emptyMap(), posts.size, "Test AI", 0.01)
    }

    private fun service(repo: TrendCheckRepository, counts: TaglinesApi, search: TrendSearchApi, j: suspend (List<TcPost>, List<String>) -> Sentiment = judge()) =
        TrendCheckService(counts, search, repo, { listOf("Qazi Jee") }, j, { ZoneOffset.UTC }, { NOW })

    @Test
    fun theRangeIsFoundFromOneCountsCallAndTheLiveViewMakesFourExactOnes() = runBlocking {
        val counts = FakeCounts(trendHours())
        val repo = MemRepo()
        val svc = service(repo, counts, FakeSearch(10.0, 0.005))
        val d = svc.detect(TAG)
        assertEquals(1, counts.queries.size)
        val r = d.range!!
        assertTrue(r.ended)
        assertEquals(NOW - 12 * HOUR_MS + 3 * HOUR_MS, r.startMs); assertEquals(NOW - 12 * HOUR_MS + 9 * HOUR_MS, r.endMs)

        counts.queries.clear()
        val live = svc.live(TAG, r)
        assertEquals(4, counts.queries.size) // all, originals, replies, quotes
        assertEquals(11_700L, live.shares.all); assertEquals(10, live.shares.originalPct); assertEquals(2, live.shares.replyPct)
        assertNotNull(live.launch); assertNull(live.stats)
        assertEquals(live, repo.find(TAG, r.startMs)) // saved
    }

    @Test
    fun aFullRunStaysInsideTheBudgetMeasuresThePriceAndKeepsTheSample() = runBlocking {
        val repo = MemRepo()
        val search = FakeSearch(balance = 10.0, pricePerPost = 0.0072)
        val svc = service(repo, FakeCounts(trendHours()), search)
        val check = svc.live(TAG, svc.detect(TAG).range!!)
        val plan = svc.plan(check, 1.00)!!
        val out = svc.full(check, 1.00)

        assertTrue("asked ${search.served} posts", search.served <= 200)
        assertEquals(plan.postsPlanned, search.served)
        assertEquals(0.0072, repo.costPerPost!!, 1e-9) // measured from the balance difference, not assumed
        assertEquals(search.served * 0.0072, out.costUsd!!, 1e-6)
        assertEquals(search.served, out.postsRead)
        assertNotNull(out.stats); assertNotNull(out.sentiment)
        assertEquals(plan.hoursCovered, out.readHours)
        assertEquals(out, repo.find(TAG, check.startMs))
        assertNotNull(repo.sample(check.key))
        // today's spend is the X charge plus the AI step
        assertEquals(search.served * 0.0072 + 0.01, repo.spentOn(svc.today()), 1e-6)
    }

    @Test
    fun aSecondRunOnlyBuysTheHoursTheFirstOneSkipped() = runBlocking {
        val repo = MemRepo()
        val search = FakeSearch(10.0, 0.005)
        val svc = service(repo, FakeCounts(trendHours()), search)
        val check = svc.live(TAG, svc.detect(TAG).range!!)
        val first = svc.full(check, 0.30) // a small budget still reaches every busy hour, a few posts each
        assertEquals(first.hourly.filter { it.count > 0 }.map { it.startMs }, first.readHours)
        val callsAfterFirst = search.calls.size
        // every busy hour was covered, so a second run has nothing left to buy and asks X for nothing
        val err = runCatching { svc.full(first, 0.30) }.exceptionOrNull()
        assertTrue(err is TrendCheckException && err.message!!.contains("already been read"))
        assertEquals(callsAfterFirst, search.calls.size)
    }

    @Test
    fun theDailyLimitAndTheBudgetRangeAreEnforcedBeforeAnythingIsSpent() = runBlocking {
        val repo = MemRepo()
        val search = FakeSearch(10.0, 0.005)
        val svc = service(repo, FakeCounts(trendHours()), search)
        val check = svc.live(TAG, svc.detect(TAG).range!!)
        repo.add(svc.today(), 2.50)
        assertTrue(runCatching { svc.full(check, 1.00) }.exceptionOrNull() is TrendCheckException)
        assertTrue(runCatching { svc.full(check, 0.05) }.exceptionOrNull() is TrendCheckException)
        assertTrue(runCatching { svc.full(check, 9.00) }.exceptionOrNull() is TrendCheckException)
        assertEquals(0, search.calls.size) // nothing was asked of X
        assertNull(svc.blockedReason(0.40)); assertNotNull(svc.blockedReason(0.60))
    }

    @Test
    fun whenXFailsPartWayWhatWasBoughtIsKeptAndCharged() = runBlocking {
        val repo = MemRepo()
        val search = FakeSearch(10.0, 0.005, failAfterCalls = 1)
        val svc = service(repo, FakeCounts(trendHours()), search)
        val check = svc.live(TAG, svc.detect(TAG).range!!)
        val err = runCatching { svc.full(check, 1.00) }.exceptionOrNull()
        assertTrue(err is TrendCheckException && err.message!!.contains("posts were read and kept"))
        val kept = repo.find(TAG, check.startMs)!!
        assertTrue(kept.postsRead > 0 && kept.stats != null)
        assertTrue(repo.spentOn(svc.today()) > 0.0)
        assertFalse(kept.readHours.isEmpty())
    }

    @Test
    fun ifTheSentimentStepFailsThePostsAndFiguresAreStillSaved() = runBlocking {
        val repo = MemRepo()
        val svc = service(repo, FakeCounts(trendHours()), FakeSearch(10.0, 0.005)) { _, _ -> throw LlmException("no key") }
        val check = svc.live(TAG, svc.detect(TAG).range!!)
        val err = runCatching { svc.full(check, 1.00) }.exceptionOrNull()
        assertTrue(err is TrendCheckException && err.message!!.contains("sentiment step failed"))
        val kept = repo.find(TAG, check.startMs)!!
        assertNotNull(kept.stats); assertNull(kept.sentiment)
    }
}
