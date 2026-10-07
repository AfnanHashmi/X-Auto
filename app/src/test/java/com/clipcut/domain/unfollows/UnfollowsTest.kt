package com.clipcut.domain.unfollows

import com.clipcut.data.db.Unfollow
import com.clipcut.data.db.UnfollowDao
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZoneOffset

private fun auto(mode: String = "dry_run", paused: String? = null) =
    Automation(mode, paused, "g1", 30, 0, 20, 0, null, emptyList())

private fun status(total: Int = 0, paused: String? = null, automation: Automation? = auto()) =
    TrackerStatus(total, "2026-10-07T07:45:27.899Z", null, paused, null, 0, 300, 0, null, automation)

private fun ev(seq: Long, version: Long = seq, user: String = "100$seq", uuid: String = "u$seq", gc: String = "pending") =
    UnfollowEvent(seq, uuid, user, "name$seq", "Name $seq", 10, 20, 1_790_000_000_000L + seq, gc, null, null, null, version)

private class FakeDao : UnfollowDao {
    val rows = linkedMapOf<String, Unfollow>()
    override suspend fun upsertAll(rows: List<Unfollow>) { rows.forEach { this.rows[it.eventUuid] = it } }
    override suspend fun maxVersion(): Long? = rows.values.maxOfOrNull { it.version }
    override suspend fun count(): Int = rows.size
    override fun observeAll(): Flow<List<Unfollow>> = flowOf(rows.values.toList())
}

private class FakeApi(var configured: Boolean = true) : UnfollowsApi {
    /** What the tracker holds: one entry per event, replaced when its follow-up changes. */
    val server = linkedMapOf<String, UnfollowEvent>()
    var pageSize = UnfollowsService.PAGE_SIZE
    var failWith: Exception? = null
    var failOnCall: Int? = null
    var totalOverride: Int? = null
    var automation: Automation? = auto()
    val requestedAfter = mutableListOf<Long>()
    val actions = mutableListOf<String>()
    override val isConfigured: Boolean get() = configured

    fun put(e: UnfollowEvent) { server[e.eventUuid] = e }

    override suspend fun fetch(afterVersion: Long): UnfollowPage {
        requestedAfter += afterVersion
        if (failOnCall == requestedAfter.size) throw failWith!!
        if (failOnCall == null) failWith?.let { throw it }
        val page = server.values.filter { it.version > afterVersion }.sortedBy { it.version }.take(pageSize)
        return UnfollowPage(page, status(totalOverride ?: server.size, automation = automation))
    }

    override suspend fun setMode(mode: String) { failWith?.let { throw it }; actions += "mode:$mode" }
    override suspend fun resume() { failWith?.let { throw it }; actions += "resume" }
    override suspend fun whitelist(userId: String, keep: Boolean) { failWith?.let { throw it }; actions += "whitelist:$userId:$keep" }
}

class UnfollowsTest {
    private val goodBody = """{"events":[{"seq":3,"version":7,"event_uuid":"u3","user_id":"111","username":"ana","name":"Ana","followers_count":5,"following_count":6,"received_at":"2026-10-07T10:00:00.000Z",
        "gc_status":"kicked","gc_detail":"removed from the group","due_at":"2026-10-07T10:30:00.000Z","gc_updated_at":"2026-10-07T10:31:00.000Z"}],
        "status":{"total_events":1,"tracking_since":"2026-10-07T07:45:27.899Z","last_event_at":"2026-10-07T10:00:00.000Z","paused_reason":null,"subscription_deleted":null,"today_count":1,"daily_cap":300,"rejected_signature":0,"last_problem":null,
        "automation":{"mode":"live","paused_reason":null,"group_id":"g1","delay_min":30,"kicked_today":1,"kick_cap":20,"pending":2,"last_run_at":"2026-10-07T10:35:00.000Z","whitelist":["42","x"]}}}"""

    // ---- parsing ------------------------------------------------------------------------------------
    @Test
    fun readsEventsGroupFollowUpAndAutomationState() {
        val p = parseUnfollowPage(goodBody)
        val e = p.events.single()
        assertEquals("ana", e.username)
        assertEquals(7L, e.version)
        assertEquals("kicked", e.gcStatus)
        assertEquals("removed from the group", e.gcDetail)
        assertEquals(java.time.Instant.parse("2026-10-07T10:30:00Z").toEpochMilli(), e.gcDueAtEpochMs)
        assertEquals(java.time.Instant.parse("2026-10-07T10:31:00Z").toEpochMilli(), e.gcUpdatedAtEpochMs)
        val a = p.status.automation!!
        assertEquals("live", a.mode)
        assertEquals(1, a.kickedToday)
        assertEquals(2, a.pending)
        assertEquals(listOf("42"), a.whitelist) // a non-numeric id is dropped, not trusted
    }

    @Test
    fun aPausedTrackerAndAnAutomationPauseAreReported() {
        val p = parseUnfollowPage(goodBody.replace(""""paused_reason":null,"subscription""", """"paused_reason":"daily cap","subscription""").replace(""""mode":"live","paused_reason":null""", """"mode":"live","paused_reason":"stopped""""))
        assertEquals("daily cap", p.status.pausedReason)
        assertEquals("stopped", p.status.automation!!.pausedReason)
    }

    @Test
    fun aReplyWithoutAutomationStillParses() {
        val without = goodBody.replace(Regex(""",\s*"automation":\{.*\}\}\}\s*$""", RegexOption.DOT_MATCHES_ALL), "}}")
        assertNull(parseUnfollowPage(without).status.automation)
    }

    @Test
    fun replyThatIsMissingPartsOrGarbageIsRejected() {
        for (bad in listOf("nonsense", "{}", """{"events":[]}""", """{"status":{"total_events":0}}""")) {
            assertThrows(bad, UnfollowParseException::class.java) { parseUnfollowPage(bad) }
        }
    }

    @Test
    fun anEventWithoutANumberVersionIdTimeOrStatusIsRejected() {
        val bads = listOf(
            goodBody.replace(""""seq":3""", """"seq":0"""),
            goodBody.replace(""""version":7""", """"version":0"""),
            goodBody.replace(""""user_id":"111"""", """"user_id":"abc""""),
            goodBody.replace(""""received_at":"2026-10-07T10:00:00.000Z"""", """"received_at":"yesterday""""),
            goodBody.replace(""""event_uuid":"u3"""", """"event_uuid":""""""),
            goodBody.replace(""""gc_status":"kicked"""", """"gc_status":""""""),
        )
        for (b in bads) assertThrows(UnfollowParseException::class.java) { parseUnfollowPage(b) }
    }

    @Test
    fun anUnknownAutomationModeIsNotTrusted() {
        assertThrows(UnfollowParseException::class.java) { parseUnfollowPage(goodBody.replace(""""mode":"live"""", """"mode":"yolo"""")) }
    }

    // ---- refreshing ----------------------------------------------------------------------------------------
    @Test
    fun firstRefreshStoresEverythingAndLaterOnesOnlyWhatIsNewOrChanged() = runTest {
        val api = FakeApi().apply { put(ev(1)); put(ev(2)) }
        val dao = FakeDao()
        val svc = UnfollowsService(api, dao) { 5L }
        val first = svc.refresh() as RefreshOutcome.Done
        assertEquals(2, first.newEvents)
        api.put(ev(3))
        val second = svc.refresh() as RefreshOutcome.Done
        assertEquals(1, second.newEvents)
        assertEquals(listOf(1L, 2L, 3L), dao.rows.values.map { it.seq }.sorted())
        assertEquals(listOf(0L, 2L), api.requestedAfter)
    }

    @Test
    fun aChangedFollowUpReplacesTheStoredRowWithoutAddingAnother() = runTest {
        val api = FakeApi().apply { put(ev(1, version = 1)) }
        val dao = FakeDao()
        val svc = UnfollowsService(api, dao)
        svc.refresh()
        assertEquals("pending", dao.rows.getValue("u1").gcStatus)
        api.put(ev(1, version = 9, gc = "kicked"))
        val out = svc.refresh() as RefreshOutcome.Done
        assertEquals(1, dao.rows.size)
        assertEquals("kicked", dao.rows.getValue("u1").gcStatus)
        assertEquals(9L, dao.rows.getValue("u1").version)
        assertEquals(0, out.newEvents)
        assertEquals(1, out.changed)
    }

    @Test
    fun theCursorIsTheNewestStoredVersion() = runTest {
        val api = FakeApi().apply { put(ev(1, version = 4)); put(ev(2, version = 8)) }
        val svc = UnfollowsService(api, FakeDao())
        svc.refresh()
        svc.refresh()
        assertEquals(8L, api.requestedAfter.last())
    }

    @Test
    fun rowsAlreadyStoredByAnOlderAppVersionAreRefreshedFromVersionZero() = runTest {
        val dao = FakeDao().apply { rows["u1"] = Unfollow("u1", 1, "1001", "a", "A", 1, 1, 1, 1) } // v11 row: version 0, not tracked
        val api = FakeApi().apply { put(ev(1, version = 3, gc = "kicked")) }
        UnfollowsService(api, dao).refresh()
        assertEquals(0L, api.requestedAfter.first())
        assertEquals("kicked", dao.rows.getValue("u1").gcStatus)
    }

    @Test
    fun morePagesThanOneAreFollowedToTheEnd() = runTest {
        val big = FakeApi().apply { (1L..(UnfollowsService.PAGE_SIZE + 3L)).forEach { put(ev(it)) } }
        val dao = FakeDao()
        UnfollowsService(big, dao).refresh()
        assertEquals(UnfollowsService.PAGE_SIZE + 3, dao.rows.size)
    }

    @Test
    fun aFailedFetchStoresNothingAndKeepsTheCursor() = runTest {
        val api = FakeApi().apply { put(ev(1)); failWith = UnfollowApiException("boom", 500) }
        val dao = FakeDao()
        assertTrue(UnfollowsService(api, dao).refresh() is RefreshOutcome.Failed)
        assertEquals(0, dao.rows.size)
        api.failWith = null
        assertEquals(1, (UnfollowsService(api, dao).refresh() as RefreshOutcome.Done).newEvents)
    }

    @Test
    fun aFailureOnALaterPageKeepsWhatWasAlreadyStoredAndRetriesFromThere() = runTest {
        val api = FakeApi().apply {
            (1L..(UnfollowsService.PAGE_SIZE + 3L)).forEach { put(ev(it)) }
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
    fun aTrackerThatRepeatsOldVersionsIsNotTrusted() = runTest {
        val dao = FakeDao().apply { rows["u9"] = Unfollow("u9", 9, "1", null, null, null, null, 1, 1, version = 9) }
        val api = object : UnfollowsApi {
            override val isConfigured = true
            override suspend fun fetch(afterVersion: Long) = UnfollowPage(listOf(ev(5, version = 5)), status(1))
            override suspend fun setMode(mode: String) = Unit
            override suspend fun resume() = Unit
            override suspend fun whitelist(userId: String, keep: Boolean) = Unit
        }
        assertTrue(UnfollowsService(api, dao).refresh() is RefreshOutcome.Failed)
        assertEquals(1, dao.rows.size)
    }

    @Test
    fun notBeingConfiguredIsReportedWithoutCallingAnything() = runTest {
        val api = FakeApi(configured = false)
        val svc = UnfollowsService(api, FakeDao())
        assertTrue((svc.refresh() as RefreshOutcome.Failed).reason.contains("UNFOLLOW_URL"))
        assertTrue(svc.setMode("live") is ActionOutcome.Failed)
        assertTrue(api.requestedAfter.isEmpty())
        assertTrue(api.actions.isEmpty())
    }

    @Test
    fun aTrackerThatHoldsFewerEventsThanThePhoneWarns() = runTest {
        val dao = FakeDao().apply { rows["old"] = Unfollow("old", 1, "1", null, null, null, null, 1, 1) }
        val api = FakeApi().apply { totalOverride = 0 }
        val out = UnfollowsService(api, dao).refresh() as RefreshOutcome.Done
        assertTrue(out.warning!!.contains("fewer events"))
    }

    // ---- actions ---------------------------------------------------------------------------------------------------
    @Test
    fun theModeResumeAndNeverKickActionsReachTheTracker() = runTest {
        val api = FakeApi()
        val svc = UnfollowsService(api, FakeDao())
        assertEquals(ActionOutcome.Done, svc.setMode("live"))
        assertEquals(ActionOutcome.Done, svc.resume())
        assertEquals(ActionOutcome.Done, svc.protect("42"))
        assertEquals(ActionOutcome.Done, svc.protect("42", keep = false))
        assertEquals(listOf("mode:live", "resume", "whitelist:42:true", "whitelist:42:false"), api.actions)
    }

    @Test
    fun aFailedActionIsReportedNotHidden() = runTest {
        val api = FakeApi().apply { failWith = UnfollowApiException("HTTP 401", 401) }
        val out = UnfollowsService(api, FakeDao()).setMode("live")
        assertTrue(out is ActionOutcome.Failed)
        assertTrue(api.actions.isEmpty())
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

    // ---- what each group status looks like ---------------------------------------------------------------------------
    @Test
    fun everyStatusWordHasAClearBadge() {
        assertEquals(GcBadge("Kicked out of the group at 14:32", GcTone.Done), GcStatus.badge("kicked", null, "14:32"))
        assertEquals(GcBadge("Kicked out of the group", GcTone.Done), GcStatus.badge("kicked", null, null))
        assertEquals(GcBadge("Would be kicked (dry run)", GcTone.Waiting), GcStatus.badge("would_kick", null, null))
        assertEquals(GcBadge("Waiting, re-check at 15:02", GcTone.Waiting), GcStatus.badge("pending", "15:02", null))
        assertEquals(GcTone.Warning, GcStatus.badge("failed", null, null).tone)
        assertEquals(GcTone.Warning, GcStatus.badge("unverified", null, null).tone)
        for (s in listOf("not_in_gc", "refollowed", "protected", "skipped", "not_tracked")) assertEquals(s, GcTone.Neutral, GcStatus.badge(s, null, null).tone)
        assertEquals("mystery", GcStatus.badge("mystery", null, null).label)
    }

    @Test
    fun onlyKickedCountsAsKickedAndOnlyUndecidedPeopleCanBeProtected() {
        assertTrue(GcStatus.isKicked("kicked"))
        assertFalse(GcStatus.isKicked("would_kick"))
        assertFalse(GcStatus.isKicked("failed"))
        assertTrue(GcStatus.canProtect("pending"))
        assertTrue(GcStatus.canProtect("would_kick"))
        assertFalse(GcStatus.canProtect("kicked"))
        assertFalse(GcStatus.canProtect("not_in_gc"))
    }

    // ---- the home card ---------------------------------------------------------------------------------------------
    @Test
    fun theCardCountsTodaysUnfollowsAndKicksInThePhonesDay() {
        val zone = ZoneOffset.UTC
        val today = LocalDate.of(2026, 10, 7)
        fun ms(h: Int, d: Int = 7) = LocalDate.of(2026, 10, d).atTime(h, 0).atZone(zone).toInstant().toEpochMilli()
        fun row(id: String, at: Long, gc: String, updated: Long? = null) = Unfollow(id, 1, id, null, null, null, null, at, at, gc, null, null, updated, 1)
        val s = UnfollowSummary.of(
            listOf(
                row("a", ms(9), "kicked", ms(10)),
                row("b", ms(9), "would_kick", ms(10)),
                row("c", ms(9), "pending"),
                row("d", ms(9, d = 6), "kicked", ms(10, d = 6)), // yesterday
                row("e", ms(23, d = 6), "kicked", ms(1)),         // unfollowed yesterday, kicked after midnight: counts as kicked today
            ),
            today, zone,
        )
        assertEquals(UnfollowSummary(unfollowedToday = 3, kickedToday = 2, wouldKickToday = 1, waiting = 1), s)
        assertEquals(UnfollowSummary.EMPTY, UnfollowSummary.of(emptyList(), today, ZoneId.of("UTC")))
    }
}
