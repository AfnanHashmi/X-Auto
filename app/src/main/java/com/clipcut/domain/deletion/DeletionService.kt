package com.clipcut.domain.deletion

import com.clipcut.data.db.DeletedPost
import com.clipcut.data.db.DeletedPostDao
import com.clipcut.data.x.XApiClient
import com.clipcut.data.x.XApiException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withContext
import java.io.IOException
import java.time.Instant
import javax.inject.Inject
import javax.inject.Named
import javax.inject.Singleton
import kotlin.random.Random

/** One post that passed every deletion rule and is offered to the user for review. */
data class DeletionCandidate(
    val id: String,
    val text: String,
    val bbNumber: Int,
    val postedAt: Instant,
)

data class FindResult(
    val handle: String,
    val candidates: List<DeletionCandidate>,
    val scanned: Int,
    val pages: Int,
    /** True if paging stopped at the safety cap with more timeline left unread. */
    val truncated: Boolean,
)

enum class CandidateOutcome { DELETED, ALREADY_GONE }

/** Why a deletion run ended. Anything but [Completed] leaves the rest of the queue untouched. */
sealed interface RunStop {
    data object Completed : RunStop
    data object UserStopped : RunStop
    data class RateLimited(val resetEpochSec: Long?) : RunStop
    data class HardError(val message: String) : RunStop

    /** No reply came back, so the post may or may not be gone; [id] stays IN_FLIGHT in the log. */
    data class UnknownOutcome(val id: String, val message: String) : RunStop
    /** The per-run or per-24-hour safety cap was reached; the rest of the queue was left alone. */
    data class CapReached(val perRun: Boolean, val limit: Int) : RunStop
}

data class RunResult(
    val stop: RunStop,
    val deleted: Int,
    val alreadyGone: Int,
    /** Selected posts that were never attempted (run cap, daily cap, or an early stop). */
    val notAttempted: Int,
)

/**
 * Finds and deletes the media account's own "BB LOGS - NNN" posts.
 *
 * Deletion is the one irreversible thing this app does, and the account's standing is at stake, so
 * every rule lives here in code rather than in the UI:
 *  - only the media account, and only after its handle is verified to be [EXPECTED_HANDLE] (a swapped
 *    credential block must fail closed, not quietly delete from the wrong account);
 *  - only posts from that account's own timeline, inside the window, that match [isBbLogsPost];
 *  - finding is read-only and completes before any delete;
 *  - deletes are strictly sequential and paced with jitter, capped per run and per 24 hours,
 *    cancellable only *between* posts, and stop on the first hard error or rate limit.
 * There is no path that deletes an id which did not come out of [find] in this same process.
 */
@Singleton
class DeletionService @Inject constructor(
    @Named("media") private val client: XApiClient,
    private val log: DeletedPostDao,
) {
    /** The read-only pass: nothing is deleted here. */
    suspend fun find(range: DeletionRange, now: Instant, onProgress: (pages: Int, scanned: Int) -> Unit): FindResult {
        val me = verifiedAccount()
        val blocked = log.allIds().toHashSet()
        // X rejects an end_time too close to the present, and nothing newer than that can be in the window anyway.
        val end = minOf(range.endExclusive, now.minusSeconds(END_MARGIN_SEC))
        val candidates = LinkedHashMap<String, DeletionCandidate>()
        var scanned = 0
        var pages = 0
        var token: String? = null
        var truncated = false
        if (range.start < end) {
            while (true) {
                currentCoroutineContext().ensureActive()
                if (pages >= MAX_PAGES) { truncated = token != null; break }
                val page = client.getTimelinePage(me.id, range.start, end, token)
                pages++
                scanned += page.posts.size
                for (p in page.posts) {
                    val n = bbNumber(p.text) ?: continue
                    if (p.isReferencing) continue
                    if (p.createdAt < range.start || p.createdAt >= range.endExclusive) continue
                    if (p.id in blocked) continue
                    candidates.putIfAbsent(p.id, DeletionCandidate(p.id, p.text, n, p.createdAt))
                }
                onProgress(pages, scanned)
                token = page.nextToken ?: break
                delay(PAGE_PACING_MS)
            }
        }
        return FindResult(
            handle = me.username,
            candidates = candidates.values.sortedByDescending { it.postedAt },
            scanned = scanned,
            pages = pages,
            truncated = truncated,
        )
    }

    /**
     * The destructive pass, over posts the user has reviewed and confirmed. [onOutcome] fires after each
     * post is settled. Cancelling the calling coroutine stops the run at the next gap between posts,
     * never in the middle of a request.
     */
    suspend fun delete(
        runId: String,
        queue: List<DeletionCandidate>,
        now: () -> Instant = Instant::now,
        onOutcome: (id: String, CandidateOutcome) -> Unit,
    ): RunResult {
        try {
            verifiedAccount()
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e // a CancellationException is an IllegalStateException; never report a stop as an error
        } catch (e: IOException) {
            return RunResult(RunStop.HardError(e.message ?: "Could not verify the account"), 0, 0, queue.size)
        } catch (e: IllegalStateException) {
            return RunResult(RunStop.HardError(e.message ?: "Account check failed"), 0, 0, queue.size)
        }
        val usedToday = log.countSince(now().minusSeconds(24 * 3600).toEpochMilli())
        val allowance = (MAX_PER_DAY - usedToday).coerceAtLeast(0)
        if (allowance == 0) return RunResult(RunStop.CapReached(perRun = false, limit = MAX_PER_DAY), 0, 0, queue.size)
        val batch = queue.take(minOf(MAX_PER_RUN, allowance))

        var deleted = 0
        var gone = 0
        var stop: RunStop = RunStop.Completed
        for ((index, c) in batch.withIndex()) {
            if (index > 0) {
                try {
                    delay(PACE_MS + Random.nextLong(-JITTER_MS, JITTER_MS + 1))
                } catch (e: kotlinx.coroutines.CancellationException) {
                    stop = RunStop.UserStopped
                    break
                }
            }
            if (!currentCoroutineContext().isActive) {
                stop = RunStop.UserStopped
                break
            }
            // The request and its log write are one uninterruptible unit: a cancel that landed mid-request
            // would discard a real deletion's result and leave X and the log disagreeing.
            when (val step = withContext(NonCancellable) { deleteOne(runId, c, now) }) {
                is Step.Settled -> {
                    if (step.outcome == CandidateOutcome.DELETED) deleted++ else gone++
                    onOutcome(c.id, step.outcome)
                    if (step.stopAfter != null) { stop = step.stopAfter; break }
                }
                is Step.Halt -> { stop = step.stop; break }
            }
        }
        if (stop == RunStop.Completed && batch.size < queue.size) {
            stop = RunStop.CapReached(perRun = MAX_PER_RUN <= allowance, limit = if (MAX_PER_RUN <= allowance) MAX_PER_RUN else MAX_PER_DAY)
        }
        return RunResult(stop, deleted, gone, queue.size - deleted - gone)
    }

    private sealed interface Step {
        data class Settled(val outcome: CandidateOutcome, val stopAfter: RunStop? = null) : Step
        data class Halt(val stop: RunStop) : Step
    }

    private suspend fun deleteOne(runId: String, c: DeletionCandidate, now: () -> Instant): Step {
        fun row(outcome: String) = DeletedPost(
            tweetId = c.id,
            text = c.text,
            bbNumber = c.bbNumber,
            postedAtEpochMs = c.postedAt.toEpochMilli(),
            deletedAtEpochMs = now().toEpochMilli(),
            outcome = outcome,
            runId = runId,
        )
        // Logged BEFORE the request, like ClipRecord.uploadAttempted: if the process dies mid-request the
        // id is on record as possibly-deleted and is never offered again.
        log.upsert(row(DeletedPost.IN_FLIGHT))
        return try {
            val result = client.deleteTweet(c.id)
            if (!result.deleted) {
                log.discardInFlight(c.id)
                return Step.Halt(RunStop.HardError("X answered deleted:false for ${c.id}; stopping."))
            }
            log.upsert(row(DeletedPost.DELETED))
            // Stop before earning a 429 when X says the window is used up.
            val stopAfter = if (result.rateLimit.remaining == 0) {
                RunStop.RateLimited(result.rateLimit.resetAt(now().epochSecond))
            } else {
                null
            }
            Step.Settled(CandidateOutcome.DELETED, stopAfter)
        } catch (e: XApiException) {
            when (e.httpCode) {
                404 -> {
                    log.upsert(row(DeletedPost.ALREADY_GONE))
                    Step.Settled(CandidateOutcome.ALREADY_GONE)
                }
                429 -> {
                    log.discardInFlight(c.id)
                    Step.Halt(RunStop.RateLimited(e.rateLimit?.resetAt(now().epochSecond)))
                }
                else -> {
                    log.discardInFlight(c.id)
                    Step.Halt(RunStop.HardError(e.message ?: "HTTP ${e.httpCode}"))
                }
            }
        } catch (e: IOException) {
            // No HTTP reply at all: the delete may or may not have happened. Leave it IN_FLIGHT.
            Step.Halt(RunStop.UnknownOutcome(c.id, e.message ?: "no reply from X"))
        }
    }

    private suspend fun verifiedAccount() = client.getMe().also {
        check(it.username.equals(EXPECTED_HANDLE, ignoreCase = true)) {
            "The media API keys belong to @${it.username}, not @$EXPECTED_HANDLE. Refusing to delete anything."
        }
    }

    companion object {
        /** The only account this feature will ever touch; a credential mix-up must fail closed. */
        const val EXPECTED_HANDLE = "dk_account"
        const val MAX_PAGES = 10
        const val PAGE_PACING_MS = 1_500L
        const val PACE_MS = 3_000L
        const val JITTER_MS = 750L
        const val MAX_PER_RUN = 25
        const val MAX_PER_DAY = 100
        const val END_MARGIN_SEC = 15L
    }
}
