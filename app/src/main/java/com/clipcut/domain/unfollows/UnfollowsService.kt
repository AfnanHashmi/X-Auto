package com.clipcut.domain.unfollows

import com.clipcut.data.db.Unfollow
import com.clipcut.data.db.UnfollowDao
import kotlinx.coroutines.CancellationException

/** A failed call to the tracker. [httpCode] is null when no reply came back at all. */
class UnfollowApiException(message: String, val httpCode: Int? = null, cause: Throwable? = null) : Exception(message, cause)

/** The tracker (a Cloudflare Worker that X delivers unfollow events to, and that kicks people out of the group chat). */
interface UnfollowsApi {
    val isConfigured: Boolean

    /** Everything that is new or changed after [afterVersion], oldest change first, at most one page. */
    suspend fun fetch(afterVersion: Long): UnfollowPage

    /** off, dry_run or live. */
    suspend fun setMode(mode: String)

    /** Clears an automation pause. */
    suspend fun resume()

    /** Never kick [userId] ([keep] true), or allow it again (false). */
    suspend fun whitelist(userId: String, keep: Boolean)
}

sealed interface RefreshOutcome {
    data class Done(val changed: Int, val newEvents: Int, val status: TrackerStatus, val warning: String?) : RefreshOutcome
    data class Failed(val reason: String) : RefreshOutcome
}

sealed interface ActionOutcome {
    data object Done : ActionOutcome
    data class Failed(val reason: String) : ActionOutcome
}

/** What a screen row shows: one unfollow, plus how many times this person has unfollowed in total. */
data class UnfollowRow(val event: Unfollow, val timesUnfollowed: Int)

/**
 * Pulls what the tracker has recorded or changed since the last fetch and stores it. A failed fetch stores nothing for
 * the failed page and does not move the cursor, so the next tap simply asks again.
 */
class UnfollowsService(
    private val api: UnfollowsApi,
    private val dao: UnfollowDao,
    private val nowMs: () -> Long = System::currentTimeMillis,
) {
    suspend fun refresh(): RefreshOutcome {
        if (!api.isConfigured) {
            return RefreshOutcome.Failed("The tracker address or token is not set in local.properties (UNFOLLOW_URL, UNFOLLOW_TOKEN).")
        }
        return try {
            val countBefore = dao.count()
            var changed = 0
            var status: TrackerStatus? = null
            var pages = 0
            while (true) {
                if (++pages > MAX_PAGES) return RefreshOutcome.Failed("The tracker returned far more pages than expected, so the fetch was stopped.")
                val after = dao.maxVersion() ?: 0L
                val page = api.fetch(after)
                status = page.status
                if (page.events.isEmpty()) break
                if (page.events.any { it.version <= after }) throw UnfollowParseException("The tracker repeated changes that were already fetched")
                dao.upsertAll(page.events.map(::toRow))
                changed += page.events.size
                if (page.events.size < PAGE_SIZE) break
            }
            val s = status ?: return RefreshOutcome.Failed("The tracker gave no status.")
            val stored = dao.count()
            val warning = if (s.totalEvents < stored) {
                "The tracker holds fewer events (${s.totalEvents}) than this phone has stored ($stored): its data may have been reset."
            } else {
                null
            }
            RefreshOutcome.Done(changed, stored - countBefore, s, warning)
        } catch (e: CancellationException) {
            throw e
        } catch (e: UnfollowApiException) {
            RefreshOutcome.Failed(describe(e))
        } catch (e: UnfollowParseException) {
            RefreshOutcome.Failed("The tracker's reply could not be trusted, so nothing was stored: ${e.message}")
        } catch (e: Exception) {
            RefreshOutcome.Failed("The fetch failed, so nothing more was stored: ${e.message}")
        }
    }

    suspend fun setMode(mode: String): ActionOutcome = act { api.setMode(mode) }

    suspend fun resume(): ActionOutcome = act { api.resume() }

    suspend fun protect(userId: String, keep: Boolean = true): ActionOutcome = act { api.whitelist(userId, keep) }

    private suspend fun act(block: suspend () -> Unit): ActionOutcome {
        if (!api.isConfigured) return ActionOutcome.Failed("The tracker address or token is not set in local.properties.")
        return try {
            block()
            ActionOutcome.Done
        } catch (e: CancellationException) {
            throw e
        } catch (e: UnfollowApiException) {
            ActionOutcome.Failed(describe(e))
        } catch (e: Exception) {
            ActionOutcome.Failed("That did not go through: ${e.message}")
        }
    }

    private fun describe(e: UnfollowApiException): String = when (e.httpCode) {
        401 -> "The tracker refused the token. Check UNFOLLOW_TOKEN in local.properties matches the Worker's APP_TOKEN."
        null -> "The tracker did not reply (${e.message}). Nothing was changed."
        else -> "The tracker returned an error (HTTP ${e.httpCode}). Nothing was changed."
    }

    private fun toRow(e: UnfollowEvent) = Unfollow(
        eventUuid = e.eventUuid, seq = e.seq, userId = e.userId, username = e.username, name = e.name,
        followersCount = e.followersCount, followingCount = e.followingCount, receivedAtEpochMs = e.receivedAtEpochMs,
        fetchedAtEpochMs = nowMs(), gcStatus = e.gcStatus, gcDetail = e.gcDetail, gcDueAtEpochMs = e.gcDueAtEpochMs,
        gcUpdatedAtEpochMs = e.gcUpdatedAtEpochMs, version = e.version,
    )

    companion object {
        /** Must match the Worker's page size (LIMIT 500). */
        const val PAGE_SIZE = 500
        const val MAX_PAGES = 20

        /** X charges this per delivered event (docs.x.com pricing). Only shown, never used to decide anything. */
        const val EVENT_COST_USD = 0.01

        /** Newest first, each with how many times that person appears: more than once means they re-followed in between. */
        fun rows(events: List<Unfollow>): List<UnfollowRow> {
            val counts = events.groupingBy { it.userId }.eachCount()
            return events.map { UnfollowRow(it, counts.getValue(it.userId)) }
        }
    }
}
