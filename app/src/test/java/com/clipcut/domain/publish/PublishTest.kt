package com.clipcut.domain.publish

import com.clipcut.domain.bossmeter.BossMeterResult
import com.clipcut.domain.bossmeter.TagCount
import com.clipcut.domain.taglines.CountReport
import com.clipcut.domain.taglines.CountSeries
import com.clipcut.domain.taglines.HourBucket
import com.clipcut.domain.taglines.TaglineCount
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant

private const val FETCHED = 1_792_136_716_789L // 2026-10-16T07:45:16.789Z
private const val LINK = "https://x.com/example_channel/status/2110272039504511091?s=20"
private const val KEY = "sk-super-secret-key-123"

private fun boss(vararg tags: Pair<String, Long>) = BossMeterResult("2110272039504511091", 5983, tags.map { TagCount(it.first, it.second) }, FETCHED)

private const val HOUR = 3_600_000L
private const val END = FETCHED - 30_000L
private const val START = END - 24 * HOUR

/** A count report for the 24 hours before [FETCHED]; each tagline's whole total lands in its last hour bucket. */
private fun trend(vararg t: Pair<String, Long>) = CountReport(
    START, END, FETCHED,
    t.map { (name, n) ->
        val last = Math.floorDiv(END, HOUR) * HOUR
        TaglineCount(name, CountSeries(n, listOf(HourBucket(last, n))), CountSeries(1, listOf(HourBucket(last, 1))))
    },
)

private class FakeVault(var key: String? = KEY) : KeyVault {
    override fun get() = key
    override fun set(key: String) { this.key = key }
    override fun clear() { key = null }
}

private class MemPending : PendingStore {
    val map = HashMap<MeterKind, String>()
    override fun load(kind: MeterKind) = map[kind]
    override fun save(kind: MeterKind, json: String?) { if (json == null) map.remove(kind) else map[kind] = json }
}

private class FakeHttp : WebsiteHttp {
    val sent = mutableListOf<Pair<String, String>>() // json to key
    var reply: () -> WebsiteReply = { WebsiteReply(200) }
    var gate: CompletableDeferred<Unit>? = null
    var boom: Exception? = null
    override suspend fun post(json: String, key: String): WebsiteReply {
        gate?.await()
        sent += json to key
        boom?.let { throw it }
        return reply()
    }
}

@OptIn(ExperimentalCoroutinesApi::class)
class PublishTest {
    // ---- payload ---------------------------------------------------------------------------------------------------
    @Test
    fun bossBodyHasTheAgreedShape() {
        val p = bossPayload(boss("#KanikaIsTheBoss" to 4825, "#AamrapaliIsTheBoss" to 913), LINK)
        assertEquals(MeterKind.BOSS, p.kind)
        val j = JSONObject(p.json)
        assertEquals("boss", j.getString("kind"))
        assertEquals("2026-10-16T07:45:16Z", j.getString("refreshedAt"))
        assertEquals("https://x.com/example_channel/status/2110272039504511091", j.getString("postUrl")) // tracking query removed
        assertEquals(2, j.getJSONArray("items").length())
        assertEquals("#KanikaIsTheBoss", j.getJSONArray("items").getJSONObject(0).getString("label"))
        assertEquals(4825L, j.getJSONArray("items").getJSONObject(0).getLong("count"))
        assertEquals(setOf("kind", "refreshedAt", "postUrl", "items"), j.keys().asSequence().toSet())
    }

    @Test
    fun trendBodyHasTheAgreedShapeAndNoPostUrl() {
        val j = JSONObject(trendPayload(trend("QAZI BORN TO SHINE" to 36667, "QAZI RISING ABOVE HATE" to 48517)).json)
        assertEquals("trend", j.getString("kind"))
        assertEquals(setOf("kind", "refreshedAt", "hourlyStart", "items"), j.keys().asSequence().toSet())
        assertEquals(36667L, j.getJSONArray("items").getJSONObject(0).getLong("count"))
    }

    @Test
    fun trendCarriesTheHourlyGraphOnOneSharedGrid() {
        // FETCHED is 07:45:16Z, so the 24 hours before it start at 07:45:46Z the day before: 25 UTC-hour buckets, 07:00 to 07:00.
        val a = Math.floorDiv(END, HOUR) * HOUR
        val report = CountReport(
            START, END, FETCHED,
            listOf(
                TaglineCount("A", CountSeries(7, listOf(HourBucket(a - 24 * HOUR, 3), HourBucket(a - HOUR, 1), HourBucket(a, 3))), CountSeries(1, listOf(HourBucket(a, 1)))),
                TaglineCount("B", CountSeries(0, emptyList()), CountSeries(0, emptyList())), // X may leave `data` out for zero tweets
            ),
        )
        val j = JSONObject(trendPayload(report).json)
        assertEquals("2026-10-15T07:00:00Z", j.getString("hourlyStart"))
        val items = j.getJSONArray("items")
        val h = items.getJSONObject(0).getJSONArray("hourly")
        assertEquals(25, h.length())
        assertEquals(3L, h.getLong(0)); assertEquals(1L, h.getLong(23)); assertEquals(3L, h.getLong(24))
        assertEquals(7L, (0 until h.length()).sumOf { h.getLong(it) }) // the hours add up to the count
        assertEquals(0L, h.getLong(5)) // an empty hour is 0, never null
        val empty = items.getJSONObject(1).getJSONArray("hourly")
        assertEquals(25, empty.length()); assertTrue((0 until 25).all { empty.getLong(it) == 0L })
        assertFalse(JSONObject(bossPayload(boss("#A" to 1), LINK).json).getJSONArray("items").getJSONObject(0).has("hourly")) // Boss Meter unchanged
    }

    @Test
    fun anHourOutsideTheGraphOrBeyondTheLimitsIsRefusedNotDropped() {
        val a = Math.floorDiv(END, HOUR) * HOUR
        fun report(startMs: Long = START, fetched: Long = FETCHED, bucket: Long = a) =
            CountReport(startMs, END, fetched, listOf(TaglineCount("A", CountSeries(1, listOf(HourBucket(bucket, 1))), CountSeries(1, listOf(HourBucket(bucket, 1))))))
        trendPayload(report())
        assertThrows(PayloadException::class.java) { trendPayload(report(bucket = a + HOUR)) }
        val partial = JSONObject(trendPayload(report(bucket = a + 1000)).json).getJSONArray("items").getJSONObject(0).getJSONArray("hourly")
        assertEquals(1L, partial.getLong(24)) // a bucket part-way into an hour counts in that hour
        val firstPartial = JSONObject(trendPayload(report(bucket = START)).json).getJSONArray("items").getJSONObject(0).getJSONArray("hourly")
        assertEquals(1L, firstPartial.getLong(0)) // X's first bucket starts at the window's start, not on the hour
        assertThrows(PayloadException::class.java) { trendPayload(report(fetched = FETCHED + 3 * HOUR)) } // refresh time too far from the graph's end
        assertThrows(PayloadException::class.java) { trendPayload(report(startMs = END - 49 * HOUR, bucket = a)) } // more than 48 hours
        assertThrows(PayloadException::class.java) { validateItems(listOf(MeterItem("a", 1, listOf(-1)))) }
        assertThrows(PayloadException::class.java) { validateItems(listOf(MeterItem("a", 1, emptyList()))) }
    }

    @Test
    fun bossNeedsAnXStatusLinkWithAUserName() {
        assertEquals("https://x.com/a_b/status/123456", canonicalPostUrl("twitter.com/a_b/status/123456"))
        assertNull(canonicalPostUrl("2110272039504511091"))
        assertNull(canonicalPostUrl("https://x.com/i/web/status/2110272039504511091"))
        assertNull(canonicalPostUrl("https://example.com/a/status/2110272039504511091"))
        assertNull(canonicalPostUrl("https://x.com/a/likes"))
        assertThrows(PayloadException::class.java) { bossPayload(boss("#A" to 1), "2110272039504511091") }
    }

    @Test
    fun itemRulesAreEnforcedBeforeAnythingIsSent() {
        assertThrows(PayloadException::class.java) { validateItems(emptyList()) }
        assertThrows(PayloadException::class.java) { validateItems(List(51) { MeterItem("t$it", 1) }) }
        validateItems(List(50) { MeterItem("t$it", 1) })
        assertThrows(PayloadException::class.java) { validateItems(listOf(MeterItem("", 1))) }
        assertThrows(PayloadException::class.java) { validateItems(listOf(MeterItem("x".repeat(121), 1))) }
        validateItems(listOf(MeterItem("x".repeat(120), 0)))
        assertThrows(PayloadException::class.java) { validateItems(listOf(MeterItem("a", 1), MeterItem("a", 2))) }
        assertThrows(PayloadException::class.java) { validateItems(listOf(MeterItem("a", -1))) }
    }

    // ---- publisher -------------------------------------------------------------------------------------------------
    private fun TestScope.publisher(http: FakeHttp, vault: KeyVault = FakeVault(), pending: PendingStore = MemPending()) =
        WebsitePublisher(http, vault, pending, this, nowMs = { 42L })

    private val one = trendPayload(trend("A" to 5))

    @Test
    fun publishReturnsAtOnceAndTheResultComesLater() = runTest(StandardTestDispatcher()) {
        val http = FakeHttp().apply { gate = CompletableDeferred() }
        val pub = publisher(http)
        pub.publish(one)
        assertEquals(PublishStatus.Sending, pub.status.value.getValue(MeterKind.TREND)) // the caller was not made to wait
        http.gate!!.complete(Unit)
        advanceUntilIdle()
        assertEquals(PublishStatus.Done(42L), pub.status.value.getValue(MeterKind.TREND))
        assertEquals(listOf(one.json to KEY), http.sent)
    }

    @Test
    fun aFailureShowsNotUpdatedAndRetryResendsTheSameBodyWithoutAnyLookup() = runTest(StandardTestDispatcher()) {
        val http = FakeHttp().apply { reply = { WebsiteReply(500) } }
        val pub = publisher(http)
        pub.publish(one)
        advanceUntilIdle()
        assertTrue(pub.status.value.getValue(MeterKind.TREND) is PublishStatus.Failed)
        http.reply = { WebsiteReply(200) }
        pub.retry(MeterKind.TREND)
        advanceUntilIdle()
        assertTrue(pub.status.value.getValue(MeterKind.TREND) is PublishStatus.Done)
        assertEquals(2, http.sent.size)
        assertEquals(http.sent[0].first, http.sent[1].first) // identical payload
    }

    @Test
    fun noKeyNetworkErrorsAndRedirectsFailQuietlyWithoutLeakingTheKey() = runTest(StandardTestDispatcher()) {
        val none = publisher(FakeHttp(), FakeVault(null))
        none.publish(one); advanceUntilIdle()
        assertTrue((none.status.value.getValue(MeterKind.TREND) as PublishStatus.Failed).reason.contains("WEBSITE_PUBLISH_KEY"))

        val down = FakeHttp().apply { boom = java.io.IOException("timeout while sending Authorization: Bearer $KEY") }
        val p1 = publisher(down); p1.publish(one); advanceUntilIdle()
        val m1 = (p1.status.value.getValue(MeterKind.TREND) as PublishStatus.Failed).reason
        assertFalse(m1.contains(KEY)); assertEquals("Could not reach the website.", m1)

        val echo = FakeHttp().apply { reply = { WebsiteReply(401, "bad key $KEY") } }
        val p2 = publisher(echo); p2.publish(one); advanceUntilIdle()
        val m2 = (p2.status.value.getValue(MeterKind.TREND) as PublishStatus.Failed).reason
        assertFalse(m2.contains(KEY)); assertTrue(m2.contains("rejected the key"))

        val moved = FakeHttp().apply { reply = { WebsiteReply(301) } }
        val p3 = publisher(moved); p3.publish(one); advanceUntilIdle()
        assertTrue((p3.status.value.getValue(MeterKind.TREND) as PublishStatus.Failed).reason.contains("redirected"))
    }

    @Test
    fun aFailedUpdateSurvivesRestartAndASuccessClearsIt() = runTest(StandardTestDispatcher()) {
        val pending = MemPending()
        val http = FakeHttp().apply { reply = { WebsiteReply(503) } }
        publisher(http, pending = pending).publish(one); advanceUntilIdle()
        assertEquals(one.json, pending.map[MeterKind.TREND])
        val reopened = publisher(http, pending = pending) // a new session
        assertTrue(reopened.status.value.getValue(MeterKind.TREND) is PublishStatus.Failed)
        http.reply = { WebsiteReply(200) }
        reopened.retry(MeterKind.TREND); advanceUntilIdle()
        assertTrue(pending.map.isEmpty())
    }

    @Test
    fun aNewerResultReplacesOneStillOnItsWay() = runTest(StandardTestDispatcher()) {
        val http = FakeHttp().apply { gate = CompletableDeferred() }
        val pub = publisher(http)
        val newer = trendPayload(trend("A" to 9))
        pub.publish(one); advanceUntilIdle()
        pub.publish(newer)
        http.gate!!.complete(Unit); advanceUntilIdle()
        assertEquals(listOf(newer.json), http.sent.map { it.first })
    }

    @Test
    fun anUnbuildableResultNeverSendsAndOffersNoStalePayloadToRetry() = runTest(StandardTestDispatcher()) {
        val http = FakeHttp()
        val pub = publisher(http)
        pub.reject(MeterKind.BOSS, "needs a full link")
        pub.retry(MeterKind.BOSS); advanceUntilIdle()
        assertTrue(http.sent.isEmpty())
        assertEquals("needs a full link", (pub.status.value.getValue(MeterKind.BOSS) as PublishStatus.Failed).reason)
    }
}
