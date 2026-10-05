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
private const val ADMIN_ID = "9001"
private const val DK_ID = "9002"
private const val OUTSIDER_ID = "500"
private const val BASE_MS = 1_790_000_000_000L
private const val CANARY = "dk_account"

/** "user19 user20 ... user30": the twelve people who are in the group and do not follow the main account. */
private val STRANGERS = (19..30).joinToString(" ") { "user$it" }

/**
 * A fake X. The group holds 33 people: 1..18 follow the main account, 19..30 do not, plus the remover (RW), an
 * admin and DK (one of the user's own accounts, who follows main and serves as the check account).
 * [users] maps a lower-case @handle to its id; an outsider exists on X but is not in the group.
 */
private class FakeApi : GcPruneApi {
    val members = ((1..30).map { it.toString() } + listOf(REMOVER_ID, ADMIN_ID, DK_ID)).toMutableList()
    val admins = mutableSetOf(REMOVER_ID, ADMIN_ID)
    val followers = ((1..18).map { it.toString() } + DK_ID).toMutableSet()
    val users = mutableMapOf<String, String>().apply {
        for (i in 1..30) put("user$i", i.toString())
        put("admin", ADMIN_ID)
        put("rw_account", REMOVER_ID)
        put(CANARY.lowercase(), DK_ID)
        put("main_account", MAIN_ID)
        put("outsider", OUTSIDER_ID)
        put("alias19", "19") // a second name for the same account
    }
    var groupType = "group"
    var removerUsername = "rw_account"
    var canaryHandle: String? = CANARY

    val calls = mutableListOf<String>()
    val lookupBatches = mutableListOf<List<String>>()
    val removed = mutableListOf<String>()

    // hooks
    var lookupError: GcApiException? = null
    var lookupRate: RateInfo? = null
    var lookupReturnsWrongNames = false
    var removeOverride: ((String) -> Reply<RemoveOutcome>?)? = null
    var removeCountBeforeThrow: Pair<Int, GcApiException>? = null
    var readGroupFailsAfterRemoval = false
    var afterRemove: (() -> Unit)? = null
    var removeRate: RateInfo? = null

    override suspend fun mainAccount(): Reply<SelfInfo> = Reply(SelfInfo(MAIN_ID, "main_account"))
    override suspend fun removerAccount(): Reply<SelfInfo> = Reply(SelfInfo(REMOVER_ID, removerUsername))
    override suspend fun otherOwnAccountIds(): Set<String> = setOf(DK_ID)
    override suspend fun knownFollowerHandle(): String? = canaryHandle

    override suspend fun readGroup(groupId: String): Reply<GcConversation> {
        calls += "readGroup"
        if (readGroupFailsAfterRemoval && removed.isNotEmpty()) throw GcApiException("boom", 500)
        return Reply(GcConversation(groupId, groupType, members.toList(), admins.toList()))
    }

    override suspend fun lookupHandles(handles: List<String>): Reply<List<NameLookup>> {
        calls += "lookup"
        lookupBatches += handles
        lookupError?.let { throw it }
        val answers = handles.map { h ->
            val id = users[h.lowercase()]
            if (id == null) {
                NameLookup(h, null, "Could not find user with usernames: [$h].")
            } else {
                NameLookup(h, FoundUser(id, h, "Name of $h", followsMain = id in followers), null)
            }
        }
        return Reply(if (lookupReturnsWrongNames) answers.map { it.copy(requested = it.requested + "x") } else answers, lookupRate)
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

    private suspend fun GcPruneService.ready(names: String = STRANGERS): LookupReady = (lookup(GROUP, names) as LookupOutcome.Ready).lookup

    private fun LookupReady.status(handle: String) = rows.first { it.requested.equals(handle, ignoreCase = true) }.status

    private fun LookupReady.removableIds() = rows.filter { it.selectable }.mapNotNull { it.userId }

    // =========================================================================================
    // Lookup
    // =========================================================================================

    @Test
    fun everyPastedNameGetsTheRightStatusAndNobodyIsRemoved() = runTest {
        val api = FakeApi()
        val l = service(api).ready("user19 user3 admin rw_account outsider ghost_zz $CANARY main_account")
        assertEquals(PrunePlanner.Status.Removable, l.status("user19"))
        assertEquals(PrunePlanner.Status.FollowsYou, l.status("user3"))
        assertEquals(PrunePlanner.Status.Protected("a group admin"), l.status("admin"))
        assertEquals(PrunePlanner.Status.Protected("one of your own accounts"), l.status("rw_account"))
        assertEquals(PrunePlanner.Status.NotInGroup, l.status("outsider"))
        assertTrue(l.status("ghost_zz") is PrunePlanner.Status.NotFound)
        assertEquals(PrunePlanner.Status.Protected("one of your own accounts"), l.status(CANARY))
        assertEquals(PrunePlanner.Status.Protected("one of your own accounts"), l.status("main_account"))
        assertEquals(0, api.removeCalls().size)
    }

    @Test
    fun onlyRowsThatAreRemovableOrFollowersCanBeTicked() = runTest {
        val l = service(FakeApi()).ready("user19 user3 admin outsider ghost_zz")
        assertEquals(listOf("19", "3"), l.removableIds())
        assertFalse(l.rows.first { it.requested == "admin" }.selectable)
        assertFalse(l.rows.first { it.requested == "outsider" }.selectable)
        assertFalse(l.rows.first { it.requested == "ghost_zz" }.selectable)
    }

    @Test
    fun handlesCanBePastedInAnyOfTheAcceptedWays() = runTest {
        val l = service(FakeApi()).ready("@user19, https://x.com/user20\nhttps://twitter.com/USER21/  user22;user23")
        assertEquals(listOf("19", "20", "21", "22", "23"), l.removableIds())
    }

    @Test
    fun aNameThatIsNotAValidUsernameIsShownButNeverSentToX() = runTest {
        val api = FakeApi()
        val l = service(api).ready("user19 not-a-name!! user20")
        val bad = l.rows.first { it.requested == "not-a-name!!" }
        assertTrue(bad.status is PrunePlanner.Status.NotFound)
        assertNull(bad.userId)
        assertFalse(api.lookupBatches.flatten().any { it.contains("!") })
        assertEquals(listOf("19", "20"), l.removableIds())
    }

    @Test
    fun twoNamesForTheSameAccountGiveOneRow() = runTest {
        val l = service(FakeApi()).ready("user19 alias19 user20")
        assertEquals(listOf("19", "20"), l.removableIds())
        assertEquals(2, l.rows.size)
    }

    @Test
    fun nothingIsTickedByDefaultForSomeoneWhoFollowsYou() = runTest {
        val l = service(FakeApi()).ready("user19 user3 user20")
        assertEquals(setOf("19", "20"), l.defaultSelection())
    }

    @Test
    fun anEmptyOrUnusableListIsRefusedBeforeAnyCall() = runTest {
        val api = FakeApi()
        val svc = service(api)
        assertTrue(svc.lookup(GROUP, "") is LookupOutcome.Aborted)
        assertTrue(svc.lookup(GROUP, "!!! ???") is LookupOutcome.Aborted)
        assertTrue(api.calls.isEmpty())
    }

    @Test
    fun moreThanTheMaximumNumberOfNamesIsRefused() = runTest {
        val api = FakeApi()
        val tooMany = (1..HandleList.MAX_NAMES + 1).joinToString(" ") { "name$it" }
        assertTrue(service(api).lookup(GROUP, tooMany) is LookupOutcome.Aborted)
        assertTrue(api.calls.isEmpty())
    }

    @Test
    fun anInvalidGroupIdIsRefusedBeforeAnyCall() = runTest {
        val api = FakeApi()
        assertTrue(service(api).lookup("12345", "user19") is LookupOutcome.Aborted)
        assertTrue(api.calls.isEmpty())
    }

    @Test
    fun bigListsAreLookedUpInBatchesOfAtMostOneHundredAndEveryNameOnce() = runTest {
        val api = FakeApi()
        for (i in 1..150) api.users["many$i"] = (1000 + i).toString()
        val names = (1..150).map { "many$it" }
        service(api).ready(names.joinToString(" "))
        assertTrue(api.lookupBatches.all { it.size <= 100 })
        assertEquals(2, api.lookupBatches.size)
        assertEquals(names.sorted(), api.lookupBatches.flatten().filter { it.startsWith("many") }.sorted())
    }

    @Test
    fun aGroupThatIsNotAGroupAborts() = runTest {
        val api = FakeApi().apply { groupType = "one_to_one" }
        assertTrue(service(api).lookup(GROUP, "user19") is LookupOutcome.Aborted)
    }

    @Test
    fun notBeingAnAdminAborts() = runTest {
        val api = FakeApi().apply { admins.remove(REMOVER_ID) }
        val out = service(api).lookup(GROUP, "user19") as LookupOutcome.Aborted
        assertTrue(out.reason.contains("not an admin"))
    }

    @Test
    fun aDamagedWhitelistStopsTheLookup() = runTest {
        val api = FakeApi()
        assertTrue(service(api, wl = FakeWhitelist(broken = true)).lookup(GROUP, "user19") is LookupOutcome.Aborted)
        assertTrue(api.calls.isEmpty())
    }

    @Test
    fun whitelistedPeopleAreShownAsProtected() = runTest {
        val l = service(FakeApi(), wl = FakeWhitelist(setOf("20"))).ready("user19 user20")
        assertEquals(PrunePlanner.Status.Protected("on your whitelist"), l.status("user20"))
        assertEquals(listOf("19"), l.removableIds())
    }

    // ---- the follow check is proven before it is believed ---------------------------------------------
    @Test
    fun aKnownFollowerIsAlwaysAskedAboutToProveTheFollowCheck() = runTest {
        val api = FakeApi()
        val l = service(api).ready("user19")
        assertTrue(l.followCheckTrusted)
        assertEquals(1, api.lookupBatches.flatten().count { it.equals(CANARY, ignoreCase = true) })
        assertTrue(l.rows.none { it.requested.equals(CANARY, ignoreCase = true) }) // not shown unless pasted
    }

    @Test
    fun theCheckAccountIsNotAskedTwiceIfItWasPasted() = runTest {
        val api = FakeApi()
        service(api).ready("user19 $CANARY")
        assertEquals(1, api.lookupBatches.flatten().count { it.equals(CANARY, ignoreCase = true) })
    }

    @Test
    fun ifXDoesNotReportTheKnownFollowerAsFollowingNothingIsPreTicked() = runTest {
        val api = FakeApi().apply { followers.remove(DK_ID) } // X "forgets" that DK follows
        val l = service(api).ready("user19 user20")
        assertFalse(l.followCheckTrusted)
        assertTrue(l.defaultSelection().isEmpty())
        assertEquals(listOf("19", "20"), l.removableIds()) // still selectable by hand
    }

    @Test
    fun withNoCheckAccountConfiguredNothingIsPreTicked() = runTest {
        val api = FakeApi().apply { canaryHandle = null }
        val l = service(api).ready("user19")
        assertFalse(l.followCheckTrusted)
        assertTrue(l.defaultSelection().isEmpty())
    }

    // ---- errors during a lookup fail closed -------------------------------------------------------------------
    @Test
    fun anErrorFromXDuringTheLookupAbortsWithNoRows() = runTest {
        for (e in listOf(GcApiException("HTTP 500", 500), GcApiException("no reply", null, noReply = true))) {
            val api = FakeApi().apply { lookupError = e }
            assertTrue(service(api).lookup(GROUP, "user19") is LookupOutcome.Aborted)
            assertEquals(0, api.removeCalls().size)
        }
    }

    @Test
    fun aRateLimitedLookupAbortsAndSaysWhenItLifts() = runTest {
        val api = FakeApi().apply { lookupError = GcApiException("HTTP 429", 429, resetAtEpochSec = BASE_MS / 1000 + 600) }
        val out = service(api).lookup(GROUP, "user19") as LookupOutcome.Aborted
        assertTrue(out.reason.contains("rate limit"))
    }

    @Test
    fun anAnswerThatDoesNotMatchTheNamesAskedAboutIsNotTrusted() = runTest {
        val api = FakeApi().apply { lookupReturnsWrongNames = true }
        assertTrue(service(api).lookup(GROUP, "user19") is LookupOutcome.Aborted)
    }

    @Test
    fun aRateLimitResetTooFarAwayAbortsTheLookup() = runTest {
        val api = FakeApi().apply { lookupRate = RateInfo(0, BASE_MS / 1000 + 3 * 3600) }
        assertTrue(service(api).lookup(GROUP, "user19") is LookupOutcome.Aborted)
    }

    // ---- the lookup's audit file ------------------------------------------------------------------------------------
    @Test
    fun theLookupWritesAnAuditFileWithEveryRow() = runTest {
        val audit = MemoryAudit()
        val l = service(FakeApi(), audit).ready("user19 user3 ghost_zz")
        val json = audit.lastJson()
        assertEquals("lookup", json.getString("mode"))
        assertEquals(3, json.getJSONArray("review").length())
        assertEquals("removable", json.getJSONArray("review").getJSONObject(0).getString("status"))
        assertEquals("follows you", json.getJSONArray("review").getJSONObject(1).getString("status"))
        assertTrue(json.getBoolean("follow_check_trusted"))
        assertEquals(0, json.getJSONArray("removed").length())
        assertNotNull(l.auditLocation)
    }

    @Test
    fun aFailedAuditWriteDuringALookupIsOnlyAWarning() = runTest {
        val audit = MemoryAudit().apply { failFromWrite = 1 }
        val l = service(FakeApi(), audit).ready("user19")
        assertNotNull(l.auditWarning)
        assertEquals(listOf("19"), l.removableIds())
    }

    // ---- cost ------------------------------------------------------------------------------------------------------------
    @Test
    fun theCostIsOneCentPerNamePlusOneForTheCheckAccount() {
        assertEquals(0.01, XPricing.USER_READ_USD, 0.0)
        assertEquals(0.51, XPricing.lookupCostUsd(50), 1e-9)
        assertEquals(0.01, XPricing.lookupCostUsd(0), 1e-9)
    }

    // =========================================================================================
    // Execute
    // =========================================================================================

    private suspend fun GcPruneService.runIt(
        l: LookupReady,
        selected: Set<String> = l.removableIds().toSet(),
        typed: String = l.groupId,
        settings: ExecSettings = ExecSettings(),
    ) = execute(l, selected, typed, settings)

    @Test
    fun removalNeedsTheGroupIdTypedExactly() = runTest {
        val api = FakeApi()
        val svc = service(api)
        val l = svc.ready()
        for (typed in listOf("", "g2096111685077696999", GROUP.uppercase(), " $GROUP")) {
            assertTrue(svc.runIt(l, typed = typed).stop is StopReason.Refused)
        }
        assertEquals(0, api.removeCalls().size)
    }

    @Test
    fun removesTheSelectedPeopleOneByOne() = runTest {
        val api = FakeApi()
        val svc = service(api)
        val l = svc.ready()
        val r = svc.runIt(l, selected = setOf("19", "20", "21"))
        assertEquals(StopReason.Completed, r.stop)
        assertEquals(listOf("19", "20", "21"), api.removed)
        assertEquals(3, r.removed.size)
    }

    @Test
    fun anUntickedPersonIsNeverRemoved() = runTest {
        val api = FakeApi()
        val svc = service(api)
        val l = svc.ready()
        svc.runIt(l, selected = l.removableIds().toSet() - "22")
        assertFalse("22" in api.removed)
        assertEquals(11, api.removed.size)
    }

    @Test
    fun executeNeverLooksAnyoneUpAgainNorChecksFollowing() = runTest {
        val api = FakeApi()
        val svc = service(api)
        val l = svc.ready()
        val lookupsBefore = api.calls.count { it == "lookup" }
        svc.runIt(l, selected = setOf("19", "20"))
        assertEquals(lookupsBefore, api.calls.count { it == "lookup" })
    }

    @Test
    fun anIdThatIsNotATickableRowIsNeverRemovedEvenIfSelected() = runTest {
        val api = FakeApi()
        val svc = service(api)
        val l = svc.ready("user19 admin outsider ghost_zz rw_account")
        svc.runIt(l, selected = setOf("19", ADMIN_ID, OUTSIDER_ID, REMOVER_ID, MAIN_ID, "1", "5"))
        assertEquals(listOf("19"), api.removed)
    }

    @Test
    fun aFollowerYouTickedAnywayIsRemovedAndTheAuditSaysSo() = runTest {
        val api = FakeApi()
        val audit = MemoryAudit()
        val svc = service(api, audit)
        val l = svc.ready("user3 user19")
        val r = svc.runIt(l, selected = setOf("3"))
        assertEquals(listOf("3"), api.removed)
        assertTrue(r.removed.single().detail.contains("you ticked it anyway"))
    }

    @Test
    fun aFollowerYouDidNotTickIsLeftAlone() = runTest {
        val api = FakeApi()
        val svc = service(api)
        val l = svc.ready("user3 user19")
        svc.runIt(l, selected = l.defaultSelection())
        assertEquals(listOf("19"), api.removed)
    }

    @Test
    fun theCapStopsTheRun() = runTest {
        val api = FakeApi()
        val svc = service(api)
        val l = svc.ready()
        val r = svc.runIt(l, settings = ExecSettings(cap = 3))
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
        val l = svc.ready()
        assertTrue(svc.runIt(l, settings = ExecSettings(cap = 0)).stop is StopReason.Refused)
        assertTrue(svc.runIt(l, settings = ExecSettings(cap = 51)).stop is StopReason.Refused)
        assertEquals(0, api.removeCalls().size)
    }

    @Test
    fun anUnexpectedErrorStopsTheWholeBatchAtOnce() = runTest {
        val api = FakeApi()
        val svc = service(api)
        val l = svc.ready()
        api.removeCountBeforeThrow = 1 to GcApiException("HTTP 500: server error", 500)
        val r = svc.runIt(l, selected = setOf("19", "20", "21", "22"))
        assertEquals(listOf("19"), api.removed)
        assertEquals(listOf("remove:19", "remove:20"), api.removeCalls()) // 21 and 22 were never even tried
        assertTrue(r.stop is StopReason.ApiError)
        assertEquals("20", r.failures.single().person.userId)
    }

    @Test
    fun aRemovedFalseReplyStopsTheRun() = runTest {
        val api = FakeApi()
        val svc = service(api)
        val l = svc.ready()
        api.removeOverride = { if (it == "20") Reply(RemoveOutcome.NotRemoved("X did not confirm")) else null }
        val r = svc.runIt(l, selected = setOf("19", "20", "21"))
        assertEquals(listOf("19"), api.removed)
        assertTrue(r.stop is StopReason.ApiError)
    }

    @Test
    fun aRateLimitOnARemovalStopsTheRunAndReportsTheReset() = runTest {
        val api = FakeApi()
        val svc = service(api)
        val l = svc.ready()
        api.removeCountBeforeThrow = 0 to GcApiException("HTTP 429", 429, resetAtEpochSec = 1_790_000_900)
        val r = svc.runIt(l, selected = setOf("19", "20"))
        assertEquals(StopReason.RateLimited(1_790_000_900), r.stop)
        assertEquals(0, api.removed.size)
    }

    @Test
    fun aSignatureRefusalIsExplainedNotWorkedAround() = runTest {
        val api = FakeApi()
        val svc = service(api)
        val l = svc.ready()
        api.removeCountBeforeThrow = 0 to GcApiException("HTTP 403: action_signatures are required for encrypted chats", 403)
        val r = svc.runIt(l, selected = setOf("19", "20"))
        assertTrue(r.stop is StopReason.NeedsSignature)
        assertTrue(r.stop.describe().contains("will not fake"))
        assertEquals(listOf("remove:19"), api.removeCalls())
    }

    @Test
    fun aRemovalWithNoReplyIsSettledByRereadingTheGroupThenStops() = runTest {
        val api = FakeApi()
        val svc = service(api)
        val l = svc.ready()
        api.removeCountBeforeThrow = 0 to GcApiException("timeout", null, noReply = true)
        val r = svc.runIt(l, selected = setOf("19", "20", "21"))
        assertTrue(r.stop is StopReason.ApiError)
        assertEquals(listOf("remove:19"), api.removeCalls())
        assertEquals("not removed", r.failures.single().outcome) // the group still lists them
    }

    @Test
    fun aNoReplyWhoseGroupCannotBeRereadIsRecordedAsUnknown() = runTest {
        val api = FakeApi()
        val svc = service(api)
        val l = svc.ready()
        api.readGroupFailsAfterRemoval = true
        api.removeCountBeforeThrow = 1 to GcApiException("timeout", null, noReply = true)
        val r = svc.runIt(l, selected = setOf("19", "20", "21"))
        assertEquals("unknown", r.failures.single().outcome)
        assertEquals(listOf("19"), api.removed)
    }

    @Test
    fun youAndYourAdminsCannotBeRemovedEvenIfARowIsForgedAsRemovable() = runTest {
        val api = FakeApi()
        val svc = service(api)
        val real = svc.ready("user19")
        fun forged(id: String, handle: String) = PrunePlanner.Row(handle, id, handle, handle, PrunePlanner.Status.Removable)
        val l = real.copy(
            rows = real.rows + listOf(
                forged(REMOVER_ID, "rw_account"), forged(ADMIN_ID, "admin"), forged(DK_ID, "dk"), forged(MAIN_ID, "main"),
            ),
        )
        val r = svc.runIt(l, selected = l.rows.mapNotNull { it.userId }.toSet())
        assertEquals(listOf("19"), api.removed)
        assertEquals(4, r.skipped.count { it.detail.contains("protected") })
    }

    @Test
    fun aPersonWhoBecameAnAdminSinceTheLookupIsNotRemoved() = runTest {
        val api = FakeApi()
        val svc = service(api)
        val l = svc.ready()
        api.admins += "21"
        val r = svc.runIt(l, selected = setOf("20", "21"))
        assertEquals(listOf("20"), api.removed)
        assertTrue(r.skipped.single().detail.contains("protected"))
    }

    @Test
    fun aPersonWhoWasWhitelistedAfterTheLookupIsNotRemoved() = runTest {
        val api = FakeApi()
        val wl = FakeWhitelist()
        val svc = service(api, wl = wl)
        val l = svc.ready()
        wl.ids = setOf("21")
        svc.runIt(l, selected = setOf("20", "21"))
        assertEquals(listOf("20"), api.removed)
    }

    @Test
    fun aPersonWhoAlreadyLeftIsSkipped() = runTest {
        val api = FakeApi()
        val svc = service(api)
        val l = svc.ready()
        api.members.remove("21")
        val r = svc.runIt(l, selected = setOf("20", "21"))
        assertEquals(listOf("20"), api.removed)
        assertTrue(r.skipped.single().detail.contains("no longer a member"))
    }

    @Test
    fun ifTheRemoverLostAdminBetweenLookupAndRemovalNothingHappens() = runTest {
        val api = FakeApi()
        val svc = service(api)
        val l = svc.ready()
        api.admins.remove(REMOVER_ID)
        val r = svc.runIt(l)
        assertTrue(r.stop is StopReason.GroupChanged)
        assertEquals(0, api.removeCalls().size)
    }

    @Test
    fun aDamagedWhitelistAtRemovalTimeStopsBeforeAnyRemoval() = runTest {
        val api = FakeApi()
        val wl = FakeWhitelist()
        val svc = service(api, wl = wl)
        val l = svc.ready()
        wl.broken = true
        assertTrue(svc.runIt(l).stop is StopReason.Refused)
        assertEquals(0, api.removeCalls().size)
    }

    // ---- pacing and rate limits (virtual time) ------------------------------------------------------------------------
    @Test
    fun removalsAreSpacedByTheConfiguredDelay() = runTest {
        val api = FakeApi()
        val svc = service(api)
        val l = svc.ready()
        val t0 = testScheduler.currentTime
        svc.runIt(l, selected = setOf("19", "20", "21"), settings = ExecSettings(cap = 20, delayMs = 5_000))
        assertTrue("took ${testScheduler.currentTime - t0} ms", testScheduler.currentTime - t0 >= 10_000)
    }

    @Test
    fun theDelayCanNeverBeShorterThanOneSecond() = runTest {
        val api = FakeApi()
        val svc = service(api)
        val l = svc.ready()
        val t0 = testScheduler.currentTime
        svc.runIt(l, selected = setOf("19", "20", "21"), settings = ExecSettings(cap = 20, delayMs = 0))
        assertTrue(testScheduler.currentTime - t0 >= 2_000)
    }

    @Test
    fun whenXSaysTheLimitIsUsedUpTheRunWaitsForTheReset() = runTest {
        val api = FakeApi()
        val svc = service(api)
        val l = svc.ready()
        val resetIn = 60L
        api.removeRate = RateInfo(remaining = 0, resetEpochSec = BASE_MS / 1000 + resetIn)
        val t0 = testScheduler.currentTime
        val r = svc.runIt(l, selected = setOf("19", "20"), settings = ExecSettings(cap = 20, delayMs = 1_000))
        assertEquals(StopReason.Completed, r.stop)
        assertEquals(2, api.removed.size)
        assertTrue("waited ${testScheduler.currentTime - t0} ms", testScheduler.currentTime - t0 >= resetIn * 1000)
    }

    @Test
    fun aResetTooFarAwayStopsInsteadOfWaiting() = runTest {
        val api = FakeApi()
        val svc = service(api)
        val l = svc.ready()
        api.removeRate = RateInfo(remaining = 0, resetEpochSec = BASE_MS / 1000 + 3 * 3600)
        val r = svc.runIt(l, selected = setOf("19", "20", "21"))
        assertTrue(r.stop is StopReason.RateLimited)
        assertEquals(listOf("19"), api.removed)
    }

    @Test
    fun stoppingMidRunLeavesTheRestAlone() = runTest {
        val api = FakeApi()
        val svc = service(api)
        val l = svc.ready()
        var report: RunReport? = null
        lateinit var job: Job
        job = launch { report = svc.runIt(l, selected = setOf("19", "20", "21", "22")) }
        api.afterRemove = { if (api.removed.size == 1) job.cancel() }
        advanceUntilIdle()
        assertEquals(StopReason.UserStopped, report?.stop)
        assertEquals(listOf("19"), api.removed)
    }

    // ---- the audit trail ----------------------------------------------------------------------------------------------------
    @Test
    fun theAuditLogRecordsEveryRemovalSkipAndFailure() = runTest {
        val api = FakeApi()
        val audit = MemoryAudit()
        val svc = service(api, audit)
        val l = svc.ready()
        api.members.remove("21") // leaves before its turn: skipped
        api.removeCountBeforeThrow = 1 to GcApiException("HTTP 500", 500)
        svc.runIt(l, selected = setOf("20", "21", "22"))
        val json = audit.lastJson()
        assertEquals("execute", json.getString("mode"))
        assertEquals(listOf("20"), json.getJSONArray("removed").let { a -> (0 until a.length()).map { a.getJSONObject(it).getString("user_id") } })
        assertEquals("21", json.getJSONArray("skipped").getJSONObject(0).getString("user_id"))
        assertEquals("22", json.getJSONArray("failures").getJSONObject(0).getString("user_id"))
        assertTrue(json.isNull("in_flight"))
        assertNotNull(json.getString("stop_reason"))
        assertEquals(GROUP, json.getString("group_id"))
        assertEquals(12, json.getJSONArray("review").length())
    }

    @Test
    fun theFileIsRewrittenAfterEveryRemovalNotJustAtTheEnd() = runTest {
        val audit = MemoryAudit()
        val svc = service(FakeApi(), audit)
        val l = svc.ready()
        val before = audit.writes.size
        svc.runIt(l, selected = setOf("19", "20", "21"))
        // in-flight + result for each of 3 people, plus the final write.
        assertTrue(audit.writes.size - before >= 7)
    }

    @Test
    fun whoIsInFlightIsOnDiskBeforeTheRequestIsSent() = runTest {
        val api = FakeApi()
        val audit = MemoryAudit()
        val svc = service(api, audit)
        val l = svc.ready()
        var seenInFlight: String? = null
        api.removeOverride = { _ ->
            seenInFlight = audit.lastJson().optJSONObject("in_flight")?.optString("user_id")
            null
        }
        svc.runIt(l, selected = setOf("19"))
        assertEquals("19", seenInFlight)
    }

    @Test
    fun ifTheAuditCannotBeWrittenBeforeARemovalNothingIsSent() = runTest {
        val api = FakeApi()
        val audit = MemoryAudit()
        val svc = service(api, audit)
        val l = svc.ready()
        audit.failFromWrite = audit.writes.size + 1
        val r = svc.runIt(l, selected = setOf("19", "20"))
        assertTrue(r.stop is StopReason.AuditFailed)
        assertEquals(0, api.removeCalls().size)
    }

    @Test
    fun ifTheAuditFailsAfterARemovalTheRunStopsAndTheReportStillShowsIt() = runTest {
        val api = FakeApi()
        val audit = MemoryAudit()
        val svc = service(api, audit)
        val l = svc.ready()
        // writes: in-flight(19) ok, result(19) fails.
        audit.failFromWrite = audit.writes.size + 2
        val r = svc.runIt(l, selected = setOf("19", "20"))
        assertTrue(r.stop is StopReason.AuditFailed)
        assertEquals(listOf("19"), api.removed)
        assertEquals(1, r.removed.size)
    }

    // ---- nothing selected ------------------------------------------------------------------------------------------------------
    @Test
    fun selectingNobodyDoesNothing() = runTest {
        val api = FakeApi()
        val svc = service(api)
        val l = svc.ready()
        val callsBefore = api.calls.size
        val r = svc.runIt(l, selected = emptySet())
        assertEquals(StopReason.Completed, r.stop)
        assertEquals(callsBefore, api.calls.size)
    }
}
