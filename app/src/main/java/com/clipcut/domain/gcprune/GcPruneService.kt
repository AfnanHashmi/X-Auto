package com.clipcut.domain.gcprune

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withContext
import java.time.Instant
import java.time.ZoneId
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter

/** A group member who passed every rule and is offered for removal. [handle] and [name] are null if the account is gone. */
data class Candidate(val userId: String, val handle: String?, val name: String?, val reason: String)

/** Everything a scan learned. [execute] works only from one of these, never from a raw list of ids. */
data class ScanReady(
    val runId: String,
    val groupId: String,
    val removerId: String,
    val removerHandle: String,
    val mainHandle: String,
    val memberCount: Int,
    val followersFetched: Int,
    val followersReported: Int,
    val candidates: List<Candidate>,
    val exempt: PrunePlanner.Exempt,
    val ownIds: Set<String>,
    /** A few real followers, used to prove the per-person check works before anyone is removed. */
    val followerSample: List<String>,
    val auditLocation: String?,
    val auditWarning: String?,
)

/** What a scan will cost to read, shown to the user and approved before a single follower is fetched. */
data class ScanEstimate(val followers: Int, val estimatedUsd: Double)

sealed interface EstimateOutcome {
    data class Ready(val estimate: ScanEstimate) : EstimateOutcome
    data class Failed(val reason: String) : EstimateOutcome
}

/**
 * What X charges per record read (docs.x.com pricing: "Following/Followers: Read | $0.010 per resource").
 * Each user is billed once per UTC day, so a second scan the same day costs far less. Prices change: this is
 * only used to warn, never to decide anything, and the X usage page is the source of truth.
 */
object XPricing {
    const val USER_READ_USD = 0.01
}

sealed interface ScanOutcome {
    data class Ready(val scan: ScanReady) : ScanOutcome
    data class Aborted(val reason: String) : ScanOutcome
}

data class ExecSettings(val cap: Int = DEFAULT_CAP, val delayMs: Long = DEFAULT_DELAY_MS) {
    companion object {
        const val DEFAULT_CAP = 20
        const val MAX_CAP = 50
        const val DEFAULT_DELAY_MS = 5_000L

        /** Whatever is asked for, removals are never closer together than this. */
        const val MIN_DELAY_MS = 1_000L
    }
}

sealed interface StopReason {
    data object Completed : StopReason
    data class CapReached(val cap: Int) : StopReason
    data object UserStopped : StopReason
    data class RateLimited(val resetEpochSec: Long?) : StopReason
    data class ApiError(val message: String) : StopReason
    data class NeedsSignature(val message: String) : StopReason
    data class AuditFailed(val message: String) : StopReason
    data class SelfTestFailed(val message: String) : StopReason
    data class GroupChanged(val message: String) : StopReason
    data class Refused(val message: String) : StopReason
}

data class RunReport(
    val stop: StopReason,
    val removed: List<AuditEvent>,
    val skipped: List<AuditEvent>,
    val failures: List<AuditEvent>,
    val auditLocation: String?,
)

fun StopReason.describe(): String = when (this) {
    StopReason.Completed -> "Finished."
    is StopReason.CapReached -> "Stopped at the limit of $cap removals for one run. Run it again to continue."
    StopReason.UserStopped -> "Stopped. Anyone not yet removed was left alone."
    is StopReason.RateLimited ->
        "X's rate limit was reached." + (resetEpochSec?.let { " It lifts at ${GcClock.hhmm(it)}." } ?: "") + " Nothing further was attempted."
    is StopReason.ApiError -> "Stopped on an unexpected reply from X: $message"
    is StopReason.NeedsSignature ->
        "X refused the removal because it needs a cryptographic signature (X Chat's encryption keys). " +
            "This app does not have them and will not fake one. Nobody further was removed. X said: $message"
    is StopReason.AuditFailed -> "Stopped because the audit log could not be written: $message"
    is StopReason.SelfTestFailed -> "Stopped before removing anyone: $message"
    is StopReason.GroupChanged -> "Stopped before removing anyone: $message"
    is StopReason.Refused -> message
}

internal object GcClock {
    fun hhmm(epochSec: Long): String =
        DateTimeFormatter.ofPattern("HH:mm").withZone(ZoneId.systemDefault()).format(Instant.ofEpochSecond(epochSec))
}

/**
 * Finds group members who do not follow the main account and removes them. Every rule fails toward
 * NOT removing, because a wrong removal is far worse than a missed one:
 *
 *  - a scan removes nobody, and aborts (showing no candidates) if the follower fetch is incomplete,
 *    rate-limited or errors, or if the result looks implausible ([PrunePlanner]);
 *  - removal needs a scan, the group id typed exactly, and respects a per-run cap;
 *  - you, group admins and the whitelist are never removed, checked again against a fresh read of the group;
 *  - each person is re-checked individually right before removal, and removed only if X positively shows
 *    they do not follow; one at a time, paced, rate-limit aware, stopping on any unexpected error;
 *  - every step is written to the audit file, including who was in flight before each request.
 */
class GcPruneService(
    private val api: GcPruneApi,
    private val whitelist: WhitelistSource,
    private val audit: AuditSink,
    private val nowMs: () -> Long = System::currentTimeMillis,
) {
    // ------------------------------------------------------------------------------------------
    // Scan: always a dry run
    // ------------------------------------------------------------------------------------------

    /** Reads only the follower COUNT (one cheap call) so the cost of a scan can be approved before it starts. */
    suspend fun estimate(): EstimateOutcome = try {
        val n = api.mainAccount().value.followersCount
        if (n == null) {
            EstimateOutcome.Failed("X did not say how many followers your account has, so the cost of a scan cannot be estimated.")
        } else {
            EstimateOutcome.Ready(ScanEstimate(n, n * XPricing.USER_READ_USD))
        }
    } catch (e: CancellationException) {
        throw e
    } catch (e: GcApiException) {
        EstimateOutcome.Failed(describeApi(e))
    } catch (e: GcParseException) {
        EstimateOutcome.Failed("X sent a reply that could not be trusted: ${e.message}")
    } catch (e: Exception) {
        EstimateOutcome.Failed("Could not estimate the cost: ${e.message}")
    }

    suspend fun scan(groupId: String, onProgress: (followersFetched: Int) -> Unit = {}): ScanOutcome {
        if (!GcLink.isValidGroupId(groupId)) return ScanOutcome.Aborted("That is not a group chat id.")
        return try {
            doScan(groupId, onProgress)
        } catch (e: CancellationException) {
            throw e
        } catch (e: GcApiException) {
            ScanOutcome.Aborted(describeApi(e))
        } catch (e: GcParseException) {
            ScanOutcome.Aborted("X sent a reply that could not be trusted, so nothing was checked: ${e.message}")
        } catch (e: Exception) {
            ScanOutcome.Aborted("The scan failed, so nothing was checked: ${e.message}")
        }
    }

    private suspend fun doScan(groupId: String, onProgress: (Int) -> Unit): ScanOutcome {
        val whitelisted = loadWhitelist() ?: return ScanOutcome.Aborted("The whitelist could not be read, so nothing was checked.")
        val mainReply = api.mainAccount()
        val main = mainReply.value
        val reported = main.followersCount ?: return ScanOutcome.Aborted("X did not say how many followers your account has, so the list cannot be checked.")
        val remover = api.removerAccount().value
        val ownIds = api.otherOwnAccountIds() + main.id + remover.id

        val groupReply = api.readGroup(groupId)
        awaitRate(groupReply.rate)
        val group = groupReply.value
        if (group.type != "group") return ScanOutcome.Aborted("That conversation is not a group chat.")
        if (group.memberIds.isEmpty()) return ScanOutcome.Aborted("The group has no members.")
        if (remover.id !in group.memberIds) return ScanOutcome.Aborted("@${remover.username} is not a member of this group, so it cannot remove anyone.")
        if (remover.id !in group.adminIds) return ScanOutcome.Aborted("@${remover.username} is not an admin of this group, so it cannot remove anyone.")

        // The whole follower list, or nothing.
        val followers = HashSet<String>()
        val sample = ArrayList<String>()
        val seenTokens = HashSet<String>()
        var token: String? = null
        var pages = 0
        while (true) {
            currentCoroutineContext().ensureActive()
            if (++pages > MAX_FOLLOWER_PAGES) return ScanOutcome.Aborted("The follower list is far longer than expected, so it was not trusted.")
            val reply = api.followersPage(main.id, token)
            followers.addAll(reply.value.ids)
            if (sample.size < SAMPLE_SIZE) sample.addAll(reply.value.ids.take(SAMPLE_SIZE - sample.size))
            onProgress(followers.size)
            awaitRate(reply.rate)
            val next = reply.value.nextToken ?: break
            if (!seenTokens.add(next)) return ScanOutcome.Aborted("X's follower pages repeated, so the list cannot be trusted.")
            token = next
        }

        val plan = PrunePlanner.plan(
            PrunePlanner.Input(
                members = group.memberIds,
                admins = group.adminIds.toSet(),
                followers = followers,
                followersReported = reported,
                ownIds = ownIds,
                whitelist = whitelisted,
            ),
        )
        val ready = when (plan) {
            is PrunePlanner.Result.Abort -> return ScanOutcome.Aborted(plan.reason)
            is PrunePlanner.Result.Ready -> plan
        }

        // Handles, so a person can actually review the list. A lookup that fails aborts: an unreadable row is not reviewable.
        val profiles = HashMap<String, GcUserInfo>()
        for (batch in ready.candidates.chunked(LOOKUP_BATCH)) {
            val lookup = api.lookupUsers(batch)
            lookup.value.forEach { profiles[it.id] = it }
            awaitRate(lookup.rate)
        }
        val reason = "Not in @${main.username}'s follower list (${ready.followersFetched} followers fetched)"
        val candidates = ready.candidates.map { id ->
            val p = profiles[id]
            Candidate(id, p?.username, p?.name, reason)
        }

        val run = AuditRun(runId(), groupId, "dry-run", nowMs()).also {
            it.followersFetched = ready.followersFetched
            it.followersReported = reported
            it.memberCount = group.memberIds.distinct().size
            candidates.forEach { c -> it.candidates += AuditPerson(c.userId, c.handle, c.name) }
            it.stopReason = "scan only, nothing removed"
            it.finishedAtEpochMs = nowMs()
        }
        var location: String? = null
        var warning: String? = null
        try {
            location = audit.write(run.fileName(), run.toJson())
        } catch (e: Exception) {
            warning = "The audit file could not be written (${e.message}). Nothing has been removed."
        }
        return ScanOutcome.Ready(
            ScanReady(
                runId = run.runId,
                groupId = groupId,
                removerId = remover.id,
                removerHandle = remover.username,
                mainHandle = main.username,
                memberCount = run.memberCount,
                followersFetched = ready.followersFetched,
                followersReported = reported,
                candidates = candidates,
                exempt = ready.exempt,
                ownIds = ownIds,
                followerSample = sample,
                auditLocation = location,
                auditWarning = warning,
            ),
        )
    }

    // ------------------------------------------------------------------------------------------
    // Execute: only from a scan
    // ------------------------------------------------------------------------------------------

    suspend fun execute(
        scan: ScanReady,
        selectedIds: Set<String>,
        typedGroupId: String,
        settings: ExecSettings = ExecSettings(),
        onUpdate: (RunReport) -> Unit = {},
    ): RunReport {
        val run = AuditRun(runId(), scan.groupId, "execute", nowMs())
        var location: String? = null

        fun report(stop: StopReason) = RunReport(stop, run.removed.toList(), run.skipped.toList(), run.failures.toList(), location)

        /** Writes the audit file; the caller decides whether a failure is fatal. Returns an error text or null. */
        suspend fun persist(): String? = try {
            location = audit.write(run.fileName(), run.toJson())
            null
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            e.message ?: "unknown error"
        }

        suspend fun finish(stop: StopReason): RunReport = withContext(NonCancellable) {
            run.stopReason = stop.describe()
            run.finishedAtEpochMs = nowMs()
            run.inFlight = null
            persist()
            report(stop).also(onUpdate)
        }

        if (typedGroupId != scan.groupId) return finish(StopReason.Refused("The group id you typed does not match, so nothing was removed."))
        val cap = settings.cap
        if (cap !in 1..ExecSettings.MAX_CAP) return finish(StopReason.Refused("The removal limit must be between 1 and ${ExecSettings.MAX_CAP}."))
        val delayMs = settings.delayMs.coerceAtLeast(ExecSettings.MIN_DELAY_MS)
        run.cap = cap
        run.delayMs = delayMs
        run.memberCount = scan.memberCount
        run.followersFetched = scan.followersFetched
        run.followersReported = scan.followersReported
        scan.candidates.forEach { run.candidates += AuditPerson(it.userId, it.handle, it.name) }

        val queue = scan.candidates.filter { it.userId in selectedIds }
        fun person(c: Candidate) = AuditPerson(c.userId, c.handle, c.name)
        fun event(c: Candidate, outcome: String, detail: String) = AuditEvent(person(c), outcome, detail, nowMs())

        try {
            if (queue.isEmpty()) return finish(StopReason.Completed)

            // A fresh look at everything that can change between the scan and now.
            val whitelisted = loadWhitelist() ?: return finish(StopReason.Refused("The whitelist could not be read, so nothing was removed."))
            val remover = api.removerAccount().value
            if (remover.id != scan.removerId) return finish(StopReason.GroupChanged("The account that would remove people is not the one that scanned."))
            val groupReply = api.readGroup(scan.groupId)
            val group = groupReply.value
            if (group.type != "group") return finish(StopReason.GroupChanged("That conversation is no longer a group chat."))
            if (remover.id !in group.memberIds || remover.id !in group.adminIds) {
                return finish(StopReason.GroupChanged("@${remover.username} is no longer an admin of this group."))
            }
            val admins = group.adminIds.toSet()
            val members = group.memberIds.toSet()
            awaitRate(groupReply.rate)

            // Prove the per-person check works on people who really follow, before trusting it on anyone else.
            var checkWorks = false
            for (id in scan.followerSample.take(SELF_TEST_TRIES)) {
                val reply = api.followCheck(id)
                awaitRate(reply.rate)
                if (reply.value == FollowCheck.Follows) {
                    checkWorks = true
                    break
                }
            }
            if (!checkWorks) {
                return finish(StopReason.SelfTestFailed("X did not confirm that known followers follow you, so the follow check cannot be trusted."))
            }

            var waitBeforeNext = false
            for (c in queue) {
                if (!currentCoroutineContext().isActive) return finish(StopReason.UserStopped)
                if (run.removed.size >= cap) return finish(StopReason.CapReached(cap))

                // Protections, re-applied here so they hold even if the plan or the caller were wrong.
                if (PrunePlanner.isProtected(c.userId, admins, scan.ownIds, whitelisted)) {
                    run.skipped += event(c, "skipped", "protected: you, an admin or on the whitelist")
                    continue
                }
                if (c.userId !in members) {
                    run.skipped += event(c, "skipped", "no longer a member of the group")
                    continue
                }

                if (waitBeforeNext) {
                    delay(delayMs)
                    waitBeforeNext = false
                }

                // The decisive check: only a positive "does not follow" lets a removal through.
                val check = api.followCheck(c.userId)
                awaitRate(check.rate)
                when (val v = check.value) {
                    FollowCheck.Follows -> {
                        run.skipped += event(c, "skipped", "follows you now (followed since the scan)")
                        continue
                    }
                    is FollowCheck.Unverifiable -> {
                        run.skipped += event(c, "skipped", "could not verify: ${v.reason}")
                        continue
                    }
                    FollowCheck.NotFollowing -> Unit
                }

                // Recorded BEFORE the request, so a crash mid-request leaves a trace of who was in flight.
                run.inFlight = person(c)
                persist()?.let { return finish(StopReason.AuditFailed(it)) }

                val step = withContext(NonCancellable) { attemptRemoval(run, scan.groupId, c) { cand, outcome, detail -> event(cand, outcome, detail) } }
                run.inFlight = null
                if (step !is Step.Removed) return finish((step as Step.Stop).reason)
                persist()?.let { return finish(StopReason.AuditFailed(it)) }
                onUpdate(report(StopReason.Completed))
                awaitRate(step.rate)
                waitBeforeNext = true
            }
            return finish(StopReason.Completed)
        } catch (e: CancellationException) {
            return finish(StopReason.UserStopped)
        } catch (e: GcApiException) {
            return finish(stopFor(e))
        } catch (e: GcParseException) {
            return finish(StopReason.ApiError("X sent a reply that could not be trusted: ${e.message}"))
        } catch (e: Exception) {
            return finish(StopReason.ApiError(e.message ?: "unexpected error"))
        }
    }

    private sealed interface Step {
        data class Removed(val rate: RateInfo?) : Step
        data class Stop(val reason: StopReason) : Step
    }

    /** Sends one removal and records what happened. A removal that cannot be confirmed stops the run. */
    private suspend fun attemptRemoval(
        run: AuditRun,
        groupId: String,
        c: Candidate,
        event: (Candidate, String, String) -> AuditEvent,
    ): Step = try {
        val reply = api.removeMember(groupId, c.userId)
        when (val outcome = reply.value) {
            RemoveOutcome.Removed -> {
                run.removed += event(c, "removed", "removed from the group")
                Step.Removed(reply.rate)
            }
            is RemoveOutcome.NotRemoved -> {
                run.failures += event(c, "not removed", outcome.reason)
                Step.Stop(classify(outcome.reason))
            }
        }
    } catch (e: GcApiException) {
        if (e.noReply) {
            // The request may or may not have gone through. Ask the group, and record the answer.
            val stillMember = try {
                api.readGroup(groupId).value.memberIds.contains(c.userId)
            } catch (x: Exception) {
                null
            }
            when (stillMember) {
                false -> run.removed += event(c, "removed", "no reply from X, but the group no longer lists them")
                true -> run.failures += event(c, "not removed", "no reply from X, and the group still lists them")
                null -> run.failures += event(c, "unknown", "no reply from X and the group could not be re-read: check on X")
            }
            Step.Stop(StopReason.ApiError("X did not reply to a removal request (${e.message}). The run was stopped."))
        } else {
            run.failures += event(c, "not removed", e.message.orEmpty())
            Step.Stop(stopFor(e))
        }
    }

    // ------------------------------------------------------------------------------------------

    /** The whitelist, or null if it cannot be read: an unreadable whitelist must stop things, never count as empty. */
    private suspend fun loadWhitelist(): Set<String>? = try {
        whitelist.load()
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        null
    }

    private fun classify(message: String): StopReason =
        if (SIGNATURE_HINT.containsMatchIn(message)) StopReason.NeedsSignature(message) else StopReason.ApiError(message)

    private fun stopFor(e: GcApiException): StopReason = when {
        e.httpCode == 429 -> StopReason.RateLimited(e.resetAtEpochSec)
        else -> classify(e.message.orEmpty())
    }

    private fun describeApi(e: GcApiException): String = when {
        e.httpCode == 429 ->
            "X's rate limit was reached, so nothing was checked." + (e.resetAtEpochSec?.let { " It lifts at ${GcClock.hhmm(it)}." } ?: "")
        e.noReply -> "X did not reply (${e.message}), so nothing was checked."
        else -> "X refused a request, so nothing was checked: ${e.message}"
    }

    /** If X says the limit is used up, wait for the reset (up to [MAX_RATE_WAIT_SEC]); a longer wait stops the run. */
    private suspend fun awaitRate(rate: RateInfo?) {
        if (rate?.remaining != 0) return
        val reset = rate.resetEpochSec ?: throw GcApiException("X says the rate limit is used up but not when it lifts", 429)
        val waitSec = reset - nowMs() / 1000 + 1
        if (waitSec <= 0) return
        if (waitSec > MAX_RATE_WAIT_SEC) throw GcApiException("The rate limit lifts in ${waitSec / 60} minutes, too long to wait", 429, reset)
        delay(waitSec * 1000)
    }

    private fun runId(): String =
        DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss").withZone(ZoneOffset.UTC).format(Instant.ofEpochMilli(nowMs()))

    companion object {
        const val MAX_FOLLOWER_PAGES = 200
        const val MAX_RATE_WAIT_SEC = 15 * 60L
        const val LOOKUP_BATCH = 100
        const val SAMPLE_SIZE = 5
        const val SELF_TEST_TRIES = 3
        private val SIGNATURE_HINT = Regex("signature|encrypt|cryptograph", RegexOption.IGNORE_CASE)
    }
}
