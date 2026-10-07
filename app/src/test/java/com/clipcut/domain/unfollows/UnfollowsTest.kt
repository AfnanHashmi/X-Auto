package com.clipcut.domain.unfollows

import com.clipcut.data.db.Unfollow
import com.clipcut.data.db.UnfollowDao
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

private fun status(total: Int = 0, paused: String? = null) = TrackerStatus(total, "2026-10-07T07:45:27.899Z", null, paused, null, 0, 300, 0, null)

private fun ev(seq: Long, user: String = "100$seq", uuid: String = "u$seq") =
    UnfollowEvent(seq, uuid, user, "name$seq", "Name $seq", 10, 20, 1_790_000_000_000L + seq)

private class FakeDao : UnfollowDao {
    val rows = mutableListOf<Unfollow>()
    override suspend fun insertAll(rows: List<Unfollow>): List<Long> = rows.map { r ->
        if (this.rows.any { it.eventUuid == r.eventUuid }) -1L else { this.rows += r; 1L }
    }
    override suspend fun maxSeq(): Long? = rows.maxOfOrNull { it.seq }
    override suspend fun count(): Int = rows.size
    override fun observeAll(): Flow<List<Unfollow>> = flowOf(rows.toList())
}

private class FakeApi(var configured: Boolean = true) : UnfollowsApi {
    /** What the tracker holds. */
    val server = mutableListOf<UnfollowEvent>()
    var pageSize = UnfollowsService.PAGE_SIZE
    var failWith: Exception? = null
    var failOnCall: Int? = null
    var totalOverride: Int? = null
    var statusToReturn = status()
    val requestedAfter = mutableListOf<Long>()
    override val isConfigured: Boolean get() = configured
    override suspend fun fetch(afterSeq: Long): UnfollowPage {
        requestedAfter += afterSeq
        if (failOnCall == requestedAfter.size) throw failWith!!
        if (failOnCall == null) failWith?.let { throw it }
        val page = server.filter { it.seq > afterSeq }.sortedBy { it.seq }.take(pageSize)
        return UnfollowPage(page, statusToReturn.copy(totalEvents = totalOverride ?: server.size))
    }
}

class UnfollowsTest {
    private val goodBody = """{"events":[{"seq":3,"event_uuid":"u3","user_id":"111","username":"ana","name":"Ana","followers_count":5,"following_count":6,"received_at":"2026-10-07T10:00:00.000Z"}],
        "status":{"total_events":1,"tracking_since":"2026-10-07T07:45:27.899Z","last_event_at":"2026-10-07T10:00:00.000Z","paused_reason":null,"subscription_deleted":null,"today_count":1,"daily_cap":300,"rejected_signature":0,"last_problem":null}}"""

    // ---- parsing ------------------------------------------------------------------------------------
    @Test
    fun readsEventsAndStatus() {
        val p = parseUnfollowPage(goodBody)
        assertEquals(1, p.events.size)
        assertEquals("ana", p.events.single().username)
        assertEquals(3L, p.events.single().seq)
        assertEquals(java.time.Instant.parse("2026-10-07T10:00:00Z").toEpochMilli(), p.events.single().receivedAtEpochMs)
        assertEquals(300, p.status.dailyCap)
        assertNull(p.status.pausedReason)
    }

    @Test
    fun aPausedTrackerIsReported() {
        val p = parseUnfollowPage(goodBody.replace(""""paused_reason":null""", """"paused_reason":"daily cap""""))
        assertEquals("daily cap", p.status.pausedReason)
    }

    @Test
    fun replyThatIsMissingPartsOrGarbageIsRejected() {
        for (bad in listOf("nonsense", "{}", """{"events":[]}""", """{"status":{"total_events":0}}""")) {
            assertThrows(bad, UnfollowParseException::class.java) { parseUnfollowPage(bad) }
        }
    }

    @Test
    fun anEventWithoutANumberAnIdOrATimeIsRejected() {
        assertThrows(UnfollowParseException::class.java) { parseUnfollowPage(goodBody.replace(""""seq":3""", """"seq":0""")) }
        assertThrows(UnfollowParseException::class.java) { parseUnfollowPage(goodBody.replace(""""user_id":"111"""", """"user_id":"abc"""")) }
        assertThrows(UnfollowParseException::class.java) { parseUnfollowPage(goodBody.replace("2026-10-07T10:00:00.000Z\"}],", "yesterday\"}],")) }
        assertThrows(UnfollowParseException::class.java) { parseUnfollowPage(goodBody.replace(""""event_uuid":"u3"""", """"event_uuid":""""")) }
    }

    // ---- refreshing ----------------------------------------------------------------------------------------
    @Test
    fun firstRefreshStoresEverythingAndLaterOnesOnlyWhatIsNew() = runTest {
        val api = FakeApi().apply { server += listOf(ev(1), ev(2)) }
        val dao = FakeDao()
        val svc = UnfollowsService(api, dao) { 5L }
        val first = svc.refresh() as RefreshOutcome.Done
        assertEquals(2, first.newEvents)
        api.server += ev(3)
        val second = svc.refresh() as RefreshOutcome.Done
        assertEquals(1, second.newEvents)
        assertEquals(listOf(1L, 2L, 3L), dao.rows.map { it.seq }.sorted())
        assertEquals(listOf(0L, 2L), api.requestedAfter)
    }

    @Test
    fun theCursorIsTheNewestStoredSequenceNumber() = runTest {
        val api = FakeApi().apply { server += listOf(ev(1), ev(2)) }
        val svc = UnfollowsService(api, FakeDao())
        svc.refresh()
        svc.refresh()
        assertEquals(2L, api.requestedAfter.last())
    }

    @Test
    fun theSameEventTwiceIsStoredOnce() = runTest {
        val dao = FakeDao()
        val api = FakeApi().apply { server += listOf(ev(1, uuid = "same"), ev(2, uuid = "same")) }
        val out = UnfollowsService(api, dao).refresh() as RefreshOutcome.Done
        assertEquals(1, dao.rows.size)
        assertEquals(1, out.newEvents)
    }

    @Test
    fun morePagesThanOneAreFollowedToTheEnd() = runTest {
        val api = FakeApi().apply { pageSize = 2; server += (1L..5L).map { ev(it) } }
        val dao = FakeDao()
        // Page size 2 is below the service's PAGE_SIZE, so the service believes the list is complete after a short page.
        UnfollowsService(api, dao).refresh()
        assertEquals(2, dao.rows.size)
        // A full page keeps going: emulate with the real page size.
        val big = FakeApi().apply { server += (1L..(UnfollowsService.PAGE_SIZE + 3L)).map { ev(it) } }
        val dao2 = FakeDao()
        UnfollowsService(big, dao2).refresh()
        assertEquals(UnfollowsService.PAGE_SIZE + 3, dao2.rows.size)
    }

    @Test
    fun aFailedFetchStoresNothingAndKeepsTheCursor() = runTest {
        val api = FakeApi().apply { server += listOf(ev(1)); failWith = UnfollowApiException("boom", 500) }
        val dao = FakeDao()
        val out = UnfollowsService(api, dao).refresh()
        assertTrue(out is RefreshOutcome.Failed)
        assertEquals(0, dao.rows.size)
        api.failWith = null
        assertEquals(1, (UnfollowsService(api, dao).refresh() as RefreshOutcome.Done).newEvents)
    }

    @Test
    fun aFailureOnALaterPageKeepsWhatWasAlreadyStoredAndRetriesFromThere() = runTest {
        val api = FakeApi().apply {
            server += (1L..(UnfollowsService.PAGE_SIZE + 3L)).map { ev(it) }
            failWith = UnfollowApiException("no reply", null)
            failOnCall = 2
        }
        val dao = FakeDao()
        assertTrue(UnfollowsService(api, dao).refresh() is RefreshOutcome.Failed)
        assertEquals(UnfollowsService.PAGE_SIZE, dao.rows.size)
        api.failWith = null; api.failOnCall = null
        UnfollowsService(api, dao).refresh()
        assertEquals(UnfollowsService.PAGE_SIZE + 3, dao.rows.size)
    }

    @Test
    fun anUnauthorisedReplyTellsYouToCheckTheToken() = runTest {
        val api = FakeApi().apply { failWith = UnfollowApiException("HTTP 401", 401) }
        val out = UnfollowsService(api, FakeDao()).refresh() as RefreshOutcome.Failed
        assertTrue(out.reason.contains("UNFOLLOW_TOKEN"))
    }

    @Test
    fun aReplyThatCannotBeTrustedStoresNothing() = runTest {
        val api = FakeApi().apply { failWith = UnfollowParseException("bad shape") }
        val dao = FakeDao()
        assertTrue(UnfollowsService(api, dao).refresh() is RefreshOutcome.Failed)
        assertEquals(0, dao.rows.size)
    }

    @Test
    fun aTrackerThatRepeatsOldEventsIsNotTrusted() = runTest {
        val dao = FakeDao().apply { rows += Unfollow("u9", 9, "1", null, null, null, null, 1, 1) }
        val api = object : UnfollowsApi {
            override val isConfigured = true
            override suspend fun fetch(afterSeq: Long) = UnfollowPage(listOf(ev(5)), status(1))
        }
        val out = UnfollowsService(api, dao).refresh()
        assertTrue(out is RefreshOutcome.Failed)
        assertEquals(1, dao.rows.size)
    }

    @Test
    fun notBeingConfiguredIsReportedWithoutCallingAnything() = runTest {
        val api = FakeApi(configured = false)
        val out = UnfollowsService(api, FakeDao()).refresh() as RefreshOutcome.Failed
        assertTrue(out.reason.contains("UNFOLLOW_URL"))
        assertTrue(api.requestedAfter.isEmpty())
    }

    @Test
    fun aTrackerThatHoldsFewerEventsThanThePhoneWarns() = runTest {
        val dao = FakeDao().apply { rows += Unfollow("old", 1, "1", null, null, null, null, 1, 1) }
        val api = FakeApi().apply { totalOverride = 0 }
        val out = UnfollowsService(api, dao).refresh() as RefreshOutcome.Done
        assertTrue(out.warning!!.contains("fewer events"))
    }

    // ---- the list --------------------------------------------------------------------------------------------------
    @Test
    fun aPersonWhoUnfollowedTwiceIsMarkedAsSuch() {
        fun row(uuid: String, user: String, at: Long) = Unfollow(uuid, at, user, "n", "N", null, null, at, at)
        val rows = UnfollowsService.rows(listOf(row("a", "1", 3), row("b", "2", 2), row("c", "1", 1)))
        assertEquals(listOf(2, 1, 2), rows.map { it.timesUnfollowed })
    }

    @Test
    fun eachEventCostsOneCent() {
        assertEquals(0.01, UnfollowsService.EVENT_COST_USD, 0.0)
    }
}
