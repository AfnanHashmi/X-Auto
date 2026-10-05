package com.clipcut.domain.gcprune

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

private const val GROUP = "g2096111685077696998"
private const val MAIN_ID = "1000"
private const val REMOVER_ID = "9000"
private const val OTHER_OWN_ID = "9002"
private const val ADMIN_ID = "9001"
private const val BASE_MS = 1_790_000_000_000L

/**
 * A fake X. Defaults give a group of 33: 18 followers (1..18), 12 plain non-followers (19..30), the
 * remover, an admin and one more of the user's own accounts -- so 12 real candidates.
 */
private class FakeApi : GcPruneApi {
    val members = ((1..30).map { it.toString() } + listOf(REMOVER_ID, ADMIN_ID, OTHER_OWN_ID)).toMutableList()
    val admins = mutableSetOf(REMOVER_ID, ADMIN_ID)
    val followers = (1..18).map { it.toString() }.toMutableList()
    var reportedFollowers: Int? = 18
    var groupType = "group"
    var removerUsername = "rw_account"

    val calls = mutableListOf<String>()
    val removed = mutableListOf<String>()

    // hooks
    var followersFailAtPage: Pair<Int, GcApiException>? = null
    var pageSize = 7
    var loopTokens = false
    var followCheckOverride: ((String) -> FollowCheck?)? = null
    var removeOverride: ((String) -> Reply<RemoveOutcome>?)? = null
    var removeCountBeforeThrow: Pair<Int, GcApiException>? = null
    var readGroupFailsAfterRemoval = false
    var afterRemove: (() -> Unit)? = null
    var removeRate: RateInfo? = null

    var mainError: GcApiException? = null

    override suspend fun mainAccount(): Reply<SelfInfo> {
        calls += "main"
        mainError?.let { throw it }
        return Reply(SelfInfo(MAIN_ID, "main_account", reportedFollowers))
    }
    override suspend fun removerAccount(): Reply<SelfInfo> = Reply(SelfInfo(REMOVER_ID, removerUsername, null))
    override suspend fun otherOwnAccountIds(): Set<String> = setOf(OTHER_OWN_ID)

    override suspend fun readGroup(groupId: String): Reply<GcConversation> {
        calls += "readGroup"
        if (readGroupFailsAfterRemoval && removed.isNotEmpty()) throw GcApiException("boom", 500)
        return Reply(GcConversation(groupId, groupType, members.toList(), admins.toList()))
    }

    override suspend fun followersPage(userId: String, token: String?): Reply<FollowersPage> {
        val page = token?.removePrefix("t")?.toInt() ?: 0
        calls += "followers:$page"
        followersFailAtPage?.let { (at, ex) -> if (page == at) throw ex }
        val chunk = followers.drop(page * pageSize).take(pageSize)
        val hasMore = (page + 1) * pageSize < followers.size
        val next = if (!hasMore) null else if (loopTokens) "t1" else "t${page + 1}"
        return Reply(FollowersPage(chunk, next))
    }

    override suspend fun lookupUsers(ids: List<String>): Reply<List<GcUserInfo>> =
        Reply(ids.filter { it != "27" }.map { GcUserInfo(it, "user$it", "User $it") }) // 27 plays a deleted account

    override suspend fun lookupHandle(handle: String): Reply<GcUserInfo?> = Reply(GcUserInfo("77", handle.trimStart('@'), "Someone"))

    override suspend fun followCheck(userId: String): Reply<FollowCheck> {
        calls += "check:$userId"
        followCheckOverride?.invoke(userId)?.let { return Reply(it) }
        return Reply(if (userId in followers) FollowCheck.Follows else FollowCheck.NotFollowing)
    }

    override suspend fun removeMember(groupId: String, userId: String): Reply<RemoveOutcome> {
        calls += "remove:$userId"
        removeCountBeforeThrow?.let { (n, ex) -> if (removed.size == n) throw ex }
        removeOverride?.invoke(userId)?.let { return it }
        removed += userId
        members.remove(userId)
        afterRemove?.invoke()
        return Reply(RemoveOutcome.Removed, removeRate)
    }

    fun removeCalls() = calls.filter { it.startsWith("remove:") }
}

private class MemoryAudit : AuditSink {
    val writes = mutableListOf<Pair<String, String>>()
    var failFromWrite: Int? = null

    override suspend fun write(fileName: String, json: String): String {
        if (failFromWrite != null && writes.size + 1 >= failFromWrite!!) throw java.io.IOException("disk full")
        writes += fileName to json
        return "memory/$fileName"
    }

    fun lastJson(): JSONObject = JSONObject(writes.last().second)
}

private class FakeWhitelist(var ids: Set<String> = emptySet(), var broken: Boolean = false) : WhitelistSource {
    override suspend fun load(): Set<String> {
        if (broken) throw GcParseException("whitelist.json is damaged")
        return ids
    }
}

@OptIn(ExperimentalCoroutinesApi::class)
class GcPruneServiceTest {
    private fun TestScope.service(api: FakeApi, audit: MemoryAudit = MemoryAudit(), wl: WhitelistSource = FakeWhitelist()) =
        GcPruneService(api, wl, audit) { BASE_MS + testScheduler.currentTime }

    private suspend fun GcPruneService.readyScan(): ScanReady = (scan(GROUP) as ScanOutcome.Ready).scan

    private fun ScanReady.ids() = candidates.map { it.userId }

    // =========================================================================================
    // Scan
    // =========================================================================================

    @Test
    fun aScanFindsTheRightCandidatesAndRemovesNobody() = runTest {
        val api = FakeApi()
        val svc = service(api)
        val scan = svc.readyScan()
        assertEquals((19..30).map { it.toString() }, scan.ids())
        assertEquals(0, api.removeCalls().size)
        assertEquals(PrunePlanner.Exempt(own = 2, admins = 1, whitelisted = 0), scan.exempt)
        assertEquals(18, scan.followersFetched)
    }

    @Test
    fun theWholeFollowerListIsFetchedAcrossPages() = runTest {
        val api = FakeApi().apply { pageSize = 5 }
        service(api).readyScan()
        assertEquals(listOf("followers:0", "followers:1", "followers:2", "followers:3"), api.calls.filter { it.startsWith("followers") })
    }

    @Test
    fun profilesAreAttachedAndADeletedAccountShowsAsUnavailable() = runTest {
        val scan = service(FakeApi()).readyScan()
        assertEquals("user19", scan.candidates.first { it.userId == "19" }.handle)
        assertNull(scan.candidates.first { it.userId == "27" }.handle)
    }

    @Test
    fun aFollowerFetchErrorMidwayAbortsAndShowsNoCandidates() = runTest {
        val api = FakeApi().apply { followersFailAtPage = 1 to GcApiException("HTTP 500", 500) }
        val out = service(api).scan(GROUP)
        assertTrue(out is ScanOutcome.Aborted)
        assertEquals(0, api.removeCalls().size)
    }

    @Test
    fun aRateLimitedFollowerFetchAbortsAndSaysWhenItLifts() = runTest {
        val api = FakeApi().apply { followersFailAtPage = 1 to GcApiException("HTTP 429", 429, resetAtEpochSec = BASE_MS / 1000 + 600) }
        val out = service(api).scan(GROUP) as ScanOutcome.Aborted
        assertTrue(out.reason.contains("rate limit"))
    }

    @Test
    fun aNetworkFailureDuringTheFetchAborts() = runTest {
        val api = FakeApi().apply { followersFailAtPage = 2 to GcApiException("no reply", null, noReply = true) }
        assertTrue(service(api).scan(GROUP) is ScanOutcome.Aborted)
    }

    @Test
    fun anIncompleteFollowerListAborts() = runTest {
        // X says 18 followers... pretend it says 1000: the 18 we fetched are nowhere near enough.
        val api = FakeApi().apply { reportedFollowers = 1000 }
        val out = service(api).scan(GROUP) as ScanOutcome.Aborted
        assertTrue(out.reason.contains("followers"))
    }

    @Test
    fun missingFollowerCountAborts() = runTest {
        val api = FakeApi().apply { reportedFollowers = null }
        assertTrue(service(api).scan(GROUP) is ScanOutcome.Aborted)
    }

    @Test
    fun repeatedPaginationTokensAbortInsteadOfLooping() = runTest {
        val api = FakeApi().apply { loopTokens = true; pageSize = 5 }
        val out = service(api).scan(GROUP) as ScanOutcome.Aborted
        assertTrue(out.reason.contains("repeated"))
        assertTrue(api.calls.count { it.startsWith("followers") } <= 4)
    }

    @Test
    fun overHalfFlaggedAbortsWithAWarning() = runTest {
        val api = FakeApi().apply {
            followers.clear(); followers.addAll((1..3).map { it.toString() }); reportedFollowers = 3
        }
        val out = service(api).scan(GROUP) as ScanOutcome.Aborted
        assertTrue(out.reason.contains("over half"))
    }

    @Test
    fun aGroupThatIsNotAGroupAborts() = runTest {
        val api = FakeApi().apply { groupType = "one_to_one" }
        assertTrue(service(api).scan(GROUP) is ScanOutcome.Aborted)
    }

    @Test
    fun notBeingAnAdminAborts() = runTest {
        val api = FakeApi().apply { admins.remove(REMOVER_ID) }
        val out = service(api).scan(GROUP) as ScanOutcome.Aborted
        assertTrue(out.reason.contains("not an admin"))
    }

    @Test
    fun anInvalidGroupIdIsRefusedBeforeAnyCall() = runTest {
        val api = FakeApi()
        assertTrue(service(api).scan("12345") is ScanOutcome.Aborted)
        assertTrue(api.calls.isEmpty())
    }

    @Test
    fun aDamagedWhitelistStopsTheScan() = runTest {
        val api = FakeApi()
        val out = service(api, wl = FakeWhitelist(broken = true)).scan(GROUP)
        assertTrue(out is ScanOutcome.Aborted)
        assertTrue(api.calls.isEmpty())
    }

    @Test
    fun whitelistedPeopleNeverAppear() = runTest {
        val scan = service(FakeApi(), wl = FakeWhitelist(setOf("20", "21"))).readyScan()
        assertFalse("20" in scan.ids() || "21" in scan.ids())
        assertEquals(10, scan.candidates.size)
    }

    @Test
    fun theScanWritesADryRunAuditFile() = runTest {
        val audit = MemoryAudit()
        val scan = service(FakeApi(), audit).readyScan()
        val json = audit.lastJson()
        assertEquals("dry-run", json.getString("mode"))
        assertEquals(12, json.getJSONArray("candidates").length())
        assertEquals(0, json.getJSONArray("removed").length())
        assertNotNull(scan.auditLocation)
    }

    @Test
    fun aFailedAuditWriteDuringAScanIsOnlyAWarning() = runTest {
        val audit = MemoryAudit().apply { failFromWrite = 1 }
        val scan = service(FakeApi(), audit).readyScan()
        assertNotNull(scan.auditWarning)
        assertEquals(12, scan.candidates.size)
    }

    // =========================================================================================
    // The cost of a scan is shown, and approved, before anything is read
    // =========================================================================================

    @Test
    fun theEstimateIsTheFollowerCountTimesTheUnitPrice() = runTest {
        val api = FakeApi().apply { reportedFollowers = 24_028 }
        val out = service(api).estimate() as EstimateOutcome.Ready
        assertEquals(24_028, out.estimate.followers)
        assertEquals(240.28, out.estimate.estimatedUsd, 0.001)
    }

    @Test
    fun estimatingReadsNoFollowersAndNoGroup() = runTest {
        val api = FakeApi()
        service(api).estimate()
        assertEquals(listOf("main"), api.calls)
    }

    @Test
    fun anEstimateThatCannotBeWorkedOutIsAFailureNotAZero() = runTest {
        val noCount = FakeApi().apply { reportedFollowers = null }
        assertTrue(service(noCount).estimate() is EstimateOutcome.Failed)
        val down = FakeApi().apply { mainError = GcApiException("HTTP 500", 500) }
        assertTrue(service(down).estimate() is EstimateOutcome.Failed)
        val limited = FakeApi().apply { mainError = GcApiException("HTTP 429", 429) }
        assertTrue(service(limited).estimate() is EstimateOutcome.Failed)
    }

    @Test
    fun theUnitPriceIsTheDocumentedOneCentPerRecord() {
        assertEquals(0.01, XPricing.USER_READ_USD, 0.0)
    }

    // =========================================================================================
    // Execute
    // =========================================================================================

    private suspend fun GcPruneService.runIt(
        scan: ScanReady,
        selected: Set<String> = scan.candidates.map { it.userId }.toSet(),
        typed: String = scan.groupId,
        settings: ExecSettings = ExecSettings(),
    ) = execute(scan, selected, typed, settings)

    @Test
    fun removalNeedsTheGroupIdTypedExactly() = runTest {
        val api = FakeApi()
        val svc = service(api)
        val scan = svc.readyScan()
        for (typed in listOf("", "g2096111685077696999", GROUP.uppercase(), " $GROUP")) {
            val r = svc.runIt(scan, typed = typed)
            assertTrue(r.stop is StopReason.Refused)
        }
        assertEquals(0, api.removeCalls().size)
    }

    @Test
    fun removesTheSelectedPeopleOneByOne() = runTest {
        val api = FakeApi()
        val svc = service(api)
        val scan = svc.readyScan()
        val r = svc.runIt(scan, selected = setOf("19", "20", "21"))
        assertEquals(StopReason.Completed, r.stop)
        assertEquals(listOf("19", "20", "21"), api.removed)
        assertEquals(3, r.removed.size)
    }

    @Test
    fun anUntickedPersonIsNeverRemoved() = runTest {
        val api = FakeApi()
        val svc = service(api)
        val scan = svc.readyScan()
        svc.runIt(scan, selected = scan.ids().toSet() - "22")
        assertFalse("22" in api.removed)
        assertEquals(11, api.removed.size)
    }

    @Test
    fun anIdNobodySelectedAndNotInTheScanIsNeverRemoved() = runTest {
        val api = FakeApi()
        val svc = service(api)
        val scan = svc.readyScan()
        svc.runIt(scan, selected = setOf("1", "5", "9001", "9000"))
        assertEquals(0, api.removeCalls().size)
    }

    @Test
    fun theCapStopsTheRun() = runTest {
        val api = FakeApi()
        val svc = service(api)
        val scan = svc.readyScan()
        val r = svc.runIt(scan, settings = ExecSettings(cap = 3))
        assertEquals(3, api.removed.size)
        assertEquals(StopReason.CapReached(3), r.stop)
    }

    @Test
    fun theDefaultCapIsTwentyAndTheMaximumIsFifty() {
        assertEquals(20, ExecSettings().cap)
        assertEquals(50, ExecSettings.MAX_CAP)
    }

    @Test
    fun aCapOutsideTheAllowedRangeIsRefused() = runTest {
        val api = FakeApi()
        val svc = service(api)
        val scan = svc.readyScan()
        assertTrue(svc.runIt(scan, settings = ExecSettings(cap = 0)).stop is StopReason.Refused)
        assertTrue(svc.runIt(scan, settings = ExecSettings(cap = 51)).stop is StopReason.Refused)
        assertEquals(0, api.removeCalls().size)
    }

    @Test
    fun somebodyWhoFollowedSinceTheScanIsSkippedNotRemoved() = runTest {
        val api = FakeApi()
        val svc = service(api)
        val scan = svc.readyScan()
        api.followers += "21" // follows now
        val r = svc.runIt(scan, selected = setOf("20", "21", "22"))
        assertEquals(listOf("20", "22"), api.removed)
        assertEquals(1, r.skipped.size)
        assertTrue(r.skipped.single().detail.contains("followed since the scan"))
    }

    @Test
    fun aPersonWhoseFollowCannotBeVerifiedIsSkipped() = runTest {
        val api = FakeApi()
        val svc = service(api)
        val scan = svc.readyScan()
        api.followCheckOverride = { if (it == "21") FollowCheck.Unverifiable("no such account") else null }
        val r = svc.runIt(scan, selected = setOf("20", "21", "22"))
        assertEquals(listOf("20", "22"), api.removed)
        assertTrue(r.skipped.single().detail.contains("could not verify"))
    }

    @Test
    fun theFollowCheckIsProvenOnRealFollowersBeforeAnyRemoval() = runTest {
        val api = FakeApi()
        val svc = service(api)
        val scan = svc.readyScan()
        // A broken check that says nobody follows: it would approve everyone. The self-test catches it.
        api.followCheckOverride = { FollowCheck.NotFollowing }
        val r = svc.runIt(scan)
        assertTrue(r.stop is StopReason.SelfTestFailed)
        assertEquals(0, api.removeCalls().size)
    }

    @Test
    fun theSelfTestUsesRealFollowersFromTheScan() = runTest {
        val api = FakeApi()
        val svc = service(api)
        val scan = svc.readyScan()
        assertEquals(5, scan.followerSample.size)
        assertTrue(scan.followerSample.all { it in api.followers })
        svc.runIt(scan, selected = setOf("19"))
        assertTrue(api.calls.contains("check:${scan.followerSample.first()}"))
    }

    @Test
    fun anUnexpectedErrorStopsTheWholeBatchAtOnce() = runTest {
        val api = FakeApi()
        val svc = service(api)
        val scan = svc.readyScan()
        api.removeCountBeforeThrow = 1 to GcApiException("HTTP 500: server error", 500)
        val r = svc.runIt(scan, selected = setOf("19", "20", "21", "22"))
        assertEquals(listOf("19"), api.removed)
        assertEquals(listOf("remove:19", "remove:20"), api.removeCalls()) // 21 and 22 were never even tried
        assertTrue(r.stop is StopReason.ApiError)
        assertEquals("20", r.failures.single().person.userId)
    }

    @Test
    fun aRemovedFalseReplyStopsTheRun() = runTest {
        val api = FakeApi()
        val svc = service(api)
        val scan = svc.readyScan()
        api.removeOverride = { if (it == "20") Reply(RemoveOutcome.NotRemoved("X did not confirm")) else null }
        val r = svc.runIt(scan, selected = setOf("19", "20", "21"))
        assertEquals(listOf("19"), api.removed)
        assertTrue(r.stop is StopReason.ApiError)
    }

    @Test
    fun aRateLimitOnARemovalStopsTheRunAndReportsTheReset() = runTest {
        val api = FakeApi()
        val svc = service(api)
        val scan = svc.readyScan()
        api.removeCountBeforeThrow = 0 to GcApiException("HTTP 429", 429, resetAtEpochSec = 1_790_000_900)
        val r = svc.runIt(scan, selected = setOf("19", "20"))
        assertEquals(StopReason.RateLimited(1_790_000_900), r.stop)
        assertEquals(0, api.removed.size)
    }

    @Test
    fun aSignatureRefusalIsExplainedNotWorkedAround() = runTest {
        val api = FakeApi()
        val svc = service(api)
        val scan = svc.readyScan()
        api.removeCountBeforeThrow = 0 to GcApiException("HTTP 403: action_signatures are required for encrypted chats", 403)
        val r = svc.runIt(scan, selected = setOf("19", "20"))
        assertTrue(r.stop is StopReason.NeedsSignature)
        assertTrue(r.stop.describe().contains("will not fake"))
        assertEquals(listOf("remove:19"), api.removeCalls())
    }

    @Test
    fun aRemovalWithNoReplyIsSettledByRereadingTheGroupThenStops() = runTest {
        val api = FakeApi()
        val svc = service(api)
        val scan = svc.readyScan()
        api.removeCountBeforeThrow = 0 to GcApiException("timeout", null, noReply = true)
        val r = svc.runIt(scan, selected = setOf("19", "20", "21"))
        assertTrue(r.stop is StopReason.ApiError)
        assertEquals(listOf("remove:19"), api.removeCalls())
        assertEquals("not removed", r.failures.single().outcome) // the group still lists them
    }

    @Test
    fun aNoReplyWhoseGroupCannotBeRereadIsRecordedAsUnknown() = runTest {
        val api = FakeApi()
        val svc = service(api)
        val scan = svc.readyScan()
        // First removal works, the second gets no reply, and the group can no longer be read.
        api.readGroupFailsAfterRemoval = true
        api.removeCountBeforeThrow = 1 to GcApiException("timeout", null, noReply = true)
        val r = svc.runIt(scan, selected = setOf("19", "20", "21"))
        assertEquals("unknown", r.failures.single().outcome)
        assertEquals(listOf("19"), api.removed)
    }

    @Test
    fun youAndYourAdminsCannotBeRemovedEvenIfSelectedAndForcedIntoTheScan() = runTest {
        val api = FakeApi()
        val svc = service(api)
        val real = svc.readyScan()
        // A hostile or buggy caller builds a scan that lists the remover, an admin and another own account.
        val forged = real.copy(
            candidates = real.candidates + listOf(
                Candidate(REMOVER_ID, "rw_account", "RW", "forged"),
                Candidate(ADMIN_ID, "admin", "Admin", "forged"),
                Candidate(OTHER_OWN_ID, "dk", "DK", "forged"),
                Candidate(MAIN_ID, "main", "Main", "forged"),
            ),
        )
        val r = svc.runIt(forged, selected = forged.ids().toSet())
        for (id in listOf(REMOVER_ID, ADMIN_ID, OTHER_OWN_ID, MAIN_ID)) assertFalse(id in api.removed)
        assertEquals(4, r.skipped.count { it.detail.contains("protected") })
    }

    @Test
    fun aPersonWhoBecameAnAdminSinceTheScanIsNotRemoved() = runTest {
        val api = FakeApi()
        val svc = service(api)
        val scan = svc.readyScan()
        api.admins += "21"
        val r = svc.runIt(scan, selected = setOf("20", "21"))
        assertEquals(listOf("20"), api.removed)
        assertTrue(r.skipped.single().detail.contains("protected"))
    }

    @Test
    fun aPersonWhoWasWhitelistedAfterTheScanIsNotRemoved() = runTest {
        val api = FakeApi()
        val wl = FakeWhitelist()
        val svc = service(api, wl = wl)
        val scan = svc.readyScan()
        wl.ids = setOf("21")
        svc.runIt(scan, selected = setOf("20", "21"))
        assertEquals(listOf("20"), api.removed)
    }

    @Test
    fun aPersonWhoAlreadyLeftIsSkipped() = runTest {
        val api = FakeApi()
        val svc = service(api)
        val scan = svc.readyScan()
        api.members.remove("21")
        val r = svc.runIt(scan, selected = setOf("20", "21"))
        assertEquals(listOf("20"), api.removed)
        assertTrue(r.skipped.single().detail.contains("no longer a member"))
    }

    @Test
    fun ifTheRemoverLostAdminBetweenScanAndRemovalNothingHappens() = runTest {
        val api = FakeApi()
        val svc = service(api)
        val scan = svc.readyScan()
        api.admins.remove(REMOVER_ID)
        val r = svc.runIt(scan)
        assertTrue(r.stop is StopReason.GroupChanged)
        assertEquals(0, api.removeCalls().size)
    }

    @Test
    fun aDamagedWhitelistAtRemovalTimeStopsBeforeAnyRemoval() = runTest {
        val api = FakeApi()
        val wl = FakeWhitelist()
        val svc = service(api, wl = wl)
        val scan = svc.readyScan()
        wl.broken = true
        assertTrue(svc.runIt(scan).stop is StopReason.Refused)
        assertEquals(0, api.removeCalls().size)
    }

    // ---- pacing and rate limits (virtual time) -----------------------------------------------------------
    @Test
    fun removalsAreSpacedByTheConfiguredDelay() = runTest {
        val api = FakeApi()
        val svc = service(api)
        val scan = svc.readyScan()
        val t0 = testScheduler.currentTime
        svc.runIt(scan, selected = setOf("19", "20", "21"), settings = ExecSettings(cap = 20, delayMs = 5_000))
        assertTrue("took ${testScheduler.currentTime - t0} ms", testScheduler.currentTime - t0 >= 10_000)
    }

    @Test
    fun theDelayCanNeverBeShorterThanOneSecond() = runTest {
        val api = FakeApi()
        val svc = service(api)
        val scan = svc.readyScan()
        val t0 = testScheduler.currentTime
        svc.runIt(scan, selected = setOf("19", "20", "21"), settings = ExecSettings(cap = 20, delayMs = 0))
        assertTrue(testScheduler.currentTime - t0 >= 2_000)
    }

    @Test
    fun whenXSaysTheLimitIsUsedUpTheRunWaitsForTheReset() = runTest {
        val api = FakeApi()
        val svc = service(api)
        val scan = svc.readyScan()
        val resetIn = 60L
        api.removeRate = RateInfo(remaining = 0, resetEpochSec = BASE_MS / 1000 + resetIn)
        val t0 = testScheduler.currentTime
        val r = svc.runIt(scan, selected = setOf("19", "20"), settings = ExecSettings(cap = 20, delayMs = 1_000))
        assertEquals(StopReason.Completed, r.stop)
        assertEquals(2, api.removed.size)
        assertTrue("waited ${testScheduler.currentTime - t0} ms", testScheduler.currentTime - t0 >= resetIn * 1000)
    }

    @Test
    fun aResetTooFarAwayStopsInsteadOfWaiting() = runTest {
        val api = FakeApi()
        val svc = service(api)
        val scan = svc.readyScan()
        api.removeRate = RateInfo(remaining = 0, resetEpochSec = BASE_MS / 1000 + 3 * 3600)
        val r = svc.runIt(scan, selected = setOf("19", "20", "21"))
        assertTrue(r.stop is StopReason.RateLimited)
        assertEquals(listOf("19"), api.removed)
    }

    @Test
    fun stoppingMidRunLeavesTheRestAlone() = runTest {
        val api = FakeApi()
        val svc = service(api)
        val scan = svc.readyScan()
        var report: RunReport? = null
        lateinit var job: Job
        job = launch { report = svc.runIt(scan, selected = setOf("19", "20", "21", "22")) }
        api.afterRemove = { if (api.removed.size == 1) job.cancel() }
        advanceUntilIdle()
        assertEquals(StopReason.UserStopped, report?.stop)
        assertEquals(listOf("19"), api.removed)
    }

    // ---- the audit trail ---------------------------------------------------------------------------------------
    @Test
    fun theAuditLogRecordsEveryRemovalSkipAndFailure() = runTest {
        val api = FakeApi()
        val audit = MemoryAudit()
        val svc = service(api, audit)
        val scan = svc.readyScan()
        api.followers += "21"
        api.removeCountBeforeThrow = 1 to GcApiException("HTTP 500", 500)
        svc.runIt(scan, selected = setOf("20", "21", "22"))
        val json = audit.lastJson()
        assertEquals("execute", json.getString("mode"))
        assertEquals(listOf("20"), json.getJSONArray("removed").let { a -> (0 until a.length()).map { a.getJSONObject(it).getString("user_id") } })
        assertEquals("21", json.getJSONArray("skipped").getJSONObject(0).getString("user_id"))
        assertEquals("22", json.getJSONArray("failures").getJSONObject(0).getString("user_id"))
        assertTrue(json.isNull("in_flight"))
        assertNotNull(json.getString("stop_reason"))
        assertEquals(GROUP, json.getString("group_id"))
    }

    @Test
    fun theFileIsRewrittenAfterEveryRemovalNotJustAtTheEnd() = runTest {
        val audit = MemoryAudit()
        val svc = service(FakeApi(), audit)
        val scan = svc.readyScan()
        val before = audit.writes.size
        svc.runIt(scan, selected = setOf("19", "20", "21"))
        // in-flight + result for each of 3 people, plus the final write.
        assertTrue(audit.writes.size - before >= 7)
    }

    @Test
    fun whoIsInFlightIsOnDiskBeforeTheRequestIsSent() = runTest {
        val api = FakeApi()
        val audit = MemoryAudit()
        val svc = service(api, audit)
        val scan = svc.readyScan()
        var seenInFlight: String? = null
        api.removeOverride = { id ->
            seenInFlight = audit.lastJson().optJSONObject("in_flight")?.optString("user_id")
            null
        }
        svc.runIt(scan, selected = setOf("19"))
        assertEquals("19", seenInFlight)
    }

    @Test
    fun ifTheAuditCannotBeWrittenBeforeARemovalNothingIsSent() = runTest {
        val api = FakeApi()
        val audit = MemoryAudit()
        val svc = service(api, audit)
        val scan = svc.readyScan()
        audit.failFromWrite = audit.writes.size + 1
        val r = svc.runIt(scan, selected = setOf("19", "20"))
        assertTrue(r.stop is StopReason.AuditFailed)
        assertEquals(0, api.removeCalls().size)
    }

    @Test
    fun ifTheAuditFailsAfterARemovalTheRunStopsAndTheReportStillShowsIt() = runTest {
        val api = FakeApi()
        val audit = MemoryAudit()
        val svc = service(api, audit)
        val scan = svc.readyScan()
        // writes: in-flight(19) ok, result(19) fails.
        audit.failFromWrite = audit.writes.size + 2
        val r = svc.runIt(scan, selected = setOf("19", "20"))
        assertTrue(r.stop is StopReason.AuditFailed)
        assertEquals(listOf("19"), api.removed)
        assertEquals(1, r.removed.size)
    }

    // ---- nothing selected ------------------------------------------------------------------------------------------
    @Test
    fun selectingNobodyDoesNothing() = runTest {
        val api = FakeApi()
        val svc = service(api)
        val scan = svc.readyScan()
        val r = svc.runIt(scan, selected = emptySet())
        assertEquals(StopReason.Completed, r.stop)
        assertEquals(0, api.calls.count { it.startsWith("check:") || it.startsWith("remove:") })
    }
}
