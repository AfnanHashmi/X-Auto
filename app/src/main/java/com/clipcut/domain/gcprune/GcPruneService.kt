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

/**
 * Everything a lookup learned. [GcPruneService.execute] works only from one of these, never from a raw list of ids.
 * [rows] holds every pasted name with what became of it; only rows that are [PrunePlanner.Row.selectable] can be removed.
 */
data class LookupReady(
    val runId: String,
    val groupId: String,
    val removerId: String,
    val removerHandle: String,
    val mainHandle: String,
    val memberCount: Int,
    val rows: List<PrunePlanner.Row>,
    val ownIds: Set<String>,
    /** True when a known follower was reported as following, i.e. the "follows you" warnings can be relied on. */
    val followCheckTrusted: Boolean,
    val auditLocation: String?,
    val auditWarning: String?,
) {
    /**
     * Who starts ticked: people in the group who do not follow you. Nobody, if the follow check could not be
     * proven, because then a follower could be sitting there looking like a stranger.
     */
    fun defaultSelection(): Set<String> =
        if (!followCheckTrusted) emptySet() else rows.filter { it.status is PrunePlanner.Status.Removable }.mapNotNull { it.userId }.toSet()
}

/**
 * What X charges per record read (docs.x.com pricing: "User: Read | $0.010 per resource"). Each account is billed
 * once per UTC day. Only used to show a figure beforehand, never to decide anything; X's usage page is the truth.
 */
object XPricing {
    const val USER_READ_USD = 0.01

    /** One extra account is always looked up to prove the follow check works. */
    const val CHECK_ACCOUNTS = 1

    fun lookupCostUsd(names: Int): Double = (names + CHECK_ACCOUNTS) * USER_READ_USD
}

sealed interface LookupOutcome {
    data class Ready(val lookup: LookupReady) : LookupOutcome
    data class Aborted(val reason: String) : LookupOutcome
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
    is StopReason.GroupChanged -> "Stopped before removing anyone: $message"
    is StopReason.Refused -> message
}

internal object GcClock {
    fun hhmm(epochSec: Long): String =
        DateTimeFormatter.ofPattern("HH:mm").withZone(ZoneId.systemDefault()).format(Instant.ofEpochSecond(epochSec))
}

/**
 * Looks up the @handles you paste and removes the ones you choose from a group chat. Every rule fails toward
 * NOT removing, because a wrong removal is far worse than a missed one:
 *
 *  - a lookup removes nobody; it only classifies each name (removable, follows you, not in the group, protected,
 *    not found), and nothing is pre-ticked unless the follow check was proven to work;
 *  - removal needs a lookup, the group id typed exactly, and respects a per-run cap;
 *  - you, group admins and the whitelist are never removed, checked again against a fresh read of the group;
 *  - people are removed one at a time, paced, rate-limit aware, stopping on any unexpected error;
 *  - every step is written to the audit file, including who was in flight before each request.
 */
class GcPruneService(
    private val api: GcPruneApi,
    private val whitelist: WhitelistSource,
    private val audit: AuditSink,
    private val nowMs: () -> Long = System::currentTimeMillis,
) {
    // ------------------------------------------------------------------------------------------
    // Lookup: always a dry run
    // ------------------------------------------------------------------------------------------

    suspend fun lookup(groupId: String, pasted: String): LookupOutcome {
        if (!GcLink.isValidGroupId(groupId)) return LookupOutcome.Aborted("That is not a group chat id.")
        val parsed = HandleList.parse(pasted)
        if (parsed.tooMany) return LookupOutcome.Aborted("That is more than ${HandleList.MAX_NAMES} names. Do them in smaller lists.")
        if (parsed.valid.isEmpty()) return LookupOutcome.Aborted("There is no valid username in what you pasted.")
        return try {
            doLookup(groupId, parsed)
        } catch (e: CancellationException) {
            throw e
        } catch (e: GcApiException) {
            LookupOutcome.Aborted(describeApi(e))
        } catch (e: GcParseException) {
            LookupOutcome.Aborted("X sent a reply that could not be trusted, so nothing was looked up: ${e.message}")
        } catch (e: Exception) {
            LookupOutcome.Aborted("The lookup failed, so nothing was looked up: ${e.message}")
        }
    }

    private suspend fun doLookup(groupId: String, parsed: HandleList.Parsed): LookupOutcome {
        val whitelisted = loadWhitelist() ?: return LookupOutcome.Aborted("The whitelist could not be read, so nothing was looked up.")
        val main = api.mainAccount().value
        val remover = api.removerAccount().value
        val ownIds = api.otherOwnAccountIds() + main.id + remover.id

        val groupReply = api.readGroup(groupId)
        awaitRate(groupReply.rate)
        val group = groupReply.value
        if (group.type != "group") return LookupOutcome.Aborted("That conversation is not a group chat.")
        if (group.memberIds.isEmpty()) return LookupOutcome.Aborted("The group has no members.")
        if (remover.id !in group.memberIds) return LookupOutcome.Aborted("@${remover.username} is not a member of this group, so it cannot remove anyone.")
        if (remover.id !in group.adminIds) return LookupOutcome.Aborted("@${remover.username} is not an admin of this group, so it cannot remove anyone.")

        // One extra account that is known to follow the main account, to prove the follow answers can be believed.
        val checkHandle = api.knownFollowerHandle()
        val pastedLower = parsed.valid.map { it.lowercase() }.toSet()
        val toAsk = parsed.valid + listOfNotNull(checkHandle?.takeIf { it.lowercase() !in pastedLower })
        val answers = ArrayList<NameLookup>()
        for (batch in toAsk.chunked(LOOKUP_BATCH)) {
            val reply = api.lookupHandles(batch)
            if (reply.value.map { it.requested } != batch) throw GcParseException("the answer did not match the names that were asked about")
            answers += reply.value
            awaitRate(reply.rate)
        }
        val followCheckTrusted = checkHandle != null &&
            answers.firstOrNull { it.requested.equals(checkHandle, ignoreCase = true) }?.found?.followsMain == true

        val rows = PrunePlanner.classify(
            lookups = answers.filter { it.requested.lowercase() in pastedLower },
            members = group.memberIds.toSet(),
            admins = group.adminIds.toSet(),
            ownIds = ownIds,
            whitelist = whitelisted,
        ) + parsed.invalid.map { PrunePlanner.Row(it, null, null, null, PrunePlanner.Status.NotFound("not a valid @username")) }

        val run = AuditRun(runId(), groupId, "lookup", nowMs()).also {
            it.memberCount = group.memberIds.distinct().size
            it.followCheckTrusted = followCheckTrusted
            rows.forEach { r -> it.review += AuditRow(r.requested, r.userId, r.handle, r.name, statusText(r.status)) }
            it.stopReason = "lookup only, nothing removed"
            it.finishedAtEpochMs = nowMs()
        }
        var location: String? = null
        var warning: String? = null
        try {
            location = audit.write(run.fileName(), run.toJson())
        } catch (e: Exception) {
            warning = "The audit file could not be written (${e.message}). Nothing has been removed."
        }
        return LookupOutcome.Ready(
            LookupReady(
                runId = run.runId,
                groupId = groupId,
                removerId = remover.id,
                removerHandle = remover.username,
                mainHandle = main.username,
                memberCount = run.memberCount,
                rows = rows,
                ownIds = ownIds,
                followCheckTrusted = followCheckTrusted,
                auditLocation = location,
                auditWarning = warning,
            ),
        )
    }

    // ------------------------------------------------------------------------------------------
    // Execute: only from a lookup
    // ------------------------------------------------------------------------------------------

    suspend fun execute(
        lookup: LookupReady,
        selectedIds: Set<String>,
        typedGroupId: String,
        settings: ExecSettings = ExecSettings(),
        onUpdate: (RunReport) -> Unit = {},
    ): RunReport {
        val run = AuditRun(runId(), lookup.groupId, "execute", nowMs())
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

        if (typedGroupId != lookup.groupId) return finish(StopReason.Refused("The group id you typed does not match, so nothing was removed."))
        val cap = settings.cap
        if (cap !in 1..ExecSettings.MAX_CAP) return finish(StopReason.Refused("The removal limit must be between 1 and ${ExecSettings.MAX_CAP}."))
        val delayMs = settings.delayMs.coerceAtLeast(ExecSettings.MIN_DELAY_MS)
        run.cap = cap
        run.delayMs = delayMs
        run.memberCount = lookup.memberCount
        run.followCheckTrusted = lookup.followCheckTrusted
        lookup.rows.forEach { run.review += AuditRow(it.requested, it.userId, it.handle, it.name, statusText(it.status)) }

        // Only rows that were found, are in the group and are not protected can be queued, and only if ticked.
        val queue = lookup.rows.filter { it.selectable && it.userId in selectedIds }
        fun person(r: PrunePlanner.Row) = AuditPerson(r.userId.orEmpty(), r.handle, r.name)
        fun event(r: PrunePlanner.Row, outcome: String, detail: String) = AuditEvent(person(r), outcome, detail, nowMs())

        try {
            if (queue.isEmpty()) return finish(StopReason.Completed)

            // A fresh look at everything that can change between the lookup and now.
            val whitelisted = loadWhitelist() ?: return finish(StopReason.Refused("The whitelist could not be read, so nothing was removed."))
            val remover = api.removerAccount().value
            if (remover.id != lookup.removerId) return finish(StopReason.GroupChanged("The account that would remove people is not the one that looked them up."))
            val groupReply = api.readGroup(lookup.groupId)
            val group = groupReply.value
            if (group.type != "group") return finish(StopReason.GroupChanged("That conversation is no longer a group chat."))
            if (remover.id !in group.memberIds || remover.id !in group.adminIds) {
                return finish(StopReason.GroupChanged("@${remover.username} is no longer an admin of this group."))
            }
            val admins = group.adminIds.toSet()
            val members = group.memberIds.toSet()
            awaitRate(groupReply.rate)

            var waitBeforeNext = false
            for (row in queue) {
                val userId = row.userId ?: continue
                if (!currentCoroutineContext().isActive) return finish(StopReason.UserStopped)
                if (run.removed.size >= cap) return finish(StopReason.CapReached(cap))

                // Protections, re-applied here so they hold even if the plan or the caller were wrong.
                if (PrunePlanner.isProtected(userId, admins, lookup.ownIds, whitelisted)) {
                    run.skipped += event(row, "skipped", "protected: you, an admin or on the whitelist")
                    continue
                }
                if (userId !in members) {
                    run.skipped += event(row, "skipped", "no longer a member of the group")
                    continue
                }

                if (waitBeforeNext) {
                    delay(delayMs)
                    waitBeforeNext = false
                }

                // Recorded BEFORE the request, so a crash mid-request leaves a trace of who was in flight.
                run.inFlight = person(row)
                persist()?.let { return finish(StopReason.AuditFailed(it)) }

                val step = withContext(NonCancellable) { attemptRemoval(run, lookup.groupId, row, ::event) }
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
        row: PrunePlanner.Row,
        event: (PrunePlanner.Row, String, String) -> AuditEvent,
    ): Step {
        val userId = row.userId.orEmpty()
        val followed = if (row.status is PrunePlanner.Status.FollowsYou) " (it follows you; you ticked it anyway)" else ""
        return try {
            val reply = api.removeMember(groupId, userId)
            when (val outcome = reply.value) {
                RemoveOutcome.Removed -> {
                    run.removed += event(row, "removed", "removed from the group$followed")
                    Step.Removed(reply.rate)
                }
                is RemoveOutcome.NotRemoved -> {
                    run.failures += event(row, "not removed", outcome.reason)
                    Step.Stop(classify(outcome.reason))
                }
            }
        } catch (e: GcApiException) {
            if (e.noReply) {
                // The request may or may not have gone through. Ask the group, and record the answer.
                val stillMember = try {
                    api.readGroup(groupId).value.memberIds.contains(userId)
                } catch (x: Exception) {
                    null
                }
                when (stillMember) {
                    false -> run.removed += event(row, "removed", "no reply from X, but the group no longer lists them$followed")
                    true -> run.failures += event(row, "not removed", "no reply from X, and the group still lists them")
                    null -> run.failures += event(row, "unknown", "no reply from X and the group could not be re-read: check on X")
                }
                Step.Stop(StopReason.ApiError("X did not reply to a removal request (${e.message}). The run was stopped."))
            } else {
                run.failures += event(row, "not removed", e.message.orEmpty())
                Step.Stop(stopFor(e))
            }
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
            "X's rate limit was reached, so nothing was looked up." + (e.resetAtEpochSec?.let { " It lifts at ${GcClock.hhmm(it)}." } ?: "")
        e.noReply -> "X did not reply (${e.message}), so nothing was looked up."
        else -> "X refused a request, so nothing was looked up: ${e.message}"
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

    private fun statusText(status: PrunePlanner.Status): String = when (status) {
        PrunePlanner.Status.Removable -> "removable"
        PrunePlanner.Status.FollowsYou -> "follows you"
        PrunePlanner.Status.NotInGroup -> "not in the group"
        is PrunePlanner.Status.Protected -> "protected: ${status.why}"
        is PrunePlanner.Status.NotFound -> "not found: ${status.reason}"
    }

    private fun runId(): String =
        DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss").withZone(ZoneOffset.UTC).format(Instant.ofEpochMilli(nowMs()))

    companion object {
        const val MAX_RATE_WAIT_SEC = 15 * 60L
        const val LOOKUP_BATCH = 100
        private val SIGNATURE_HINT = Regex("signature|encrypt|cryptograph", RegexOption.IGNORE_CASE)
    }
}
