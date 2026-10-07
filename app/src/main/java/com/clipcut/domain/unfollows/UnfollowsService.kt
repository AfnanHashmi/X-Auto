package com.clipcut.domain.unfollows

import com.clipcut.data.db.Unfollow
import com.clipcut.data.db.UnfollowDao
import kotlinx.coroutines.CancellationException

/** A failed call to the tracker. [httpCode] is null when no reply came back at all. */
class UnfollowApiException(message: String, val httpCode: Int? = null, cause: Throwable? = null) : Exception(message, cause)

/** The tracker (a Cloudflare Worker that X delivers unfollow events to). */
interface UnfollowsApi {
    val isConfigured: Boolean

    /** Everything the tracker holds with a counter above [afterSeq], oldest first, at most one page. */
    suspend fun fetch(afterSeq: Long): UnfollowPage
}

sealed interface RefreshOutcome {
    data class Done(val newEvents: Int, val status: TrackerStatus, val warning: String?) : RefreshOutcome
    data class Failed(val reason: String) : RefreshOutcome
}

/** What a screen row shows: one unfollow, plus how many times this person has unfollowed in total. */
data class UnfollowRow(val event: Unfollow, val timesUnfollowed: Int)

/**
 * Pulls what the tracker has recorded since the last fetch and stores it. A failed fetch stores nothing for the
 * failed page and does not move the cursor, so the next tap simply asks again.
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
            var fresh = 0
            var status: TrackerStatus? = null
            var pages = 0
            while (true) {
                if (++pages > MAX_PAGES) return RefreshOutcome.Failed("The tracker returned far more pages than expected, so the fetch was stopped.")
                val after = dao.maxSeq() ?: 0L
                val page = api.fetch(after)
                status = page.status
                if (page.events.isEmpty()) break
                if (page.events.any { it.seq <= after }) throw UnfollowParseException("The tracker repeated events that were already fetched")
                val rows = page.events.map {
                    Unfollow(it.eventUuid, it.seq, it.userId, it.username, it.name, it.followersCount, it.followingCount, it.receivedAtEpochMs, nowMs())
                }
                fresh += dao.insertAll(rows).count { it != -1L }
                if (page.events.size < PAGE_SIZE) break
            }
            val s = status ?: return RefreshOutcome.Failed("The tracker gave no status.")
            val stored = dao.count()
            val warning = if (s.totalEvents < stored) {
                "The tracker holds fewer events (${s.totalEvents}) than this phone has stored ($stored): its data may have been reset."
            } else {
                null
            }
            RefreshOutcome.Done(fresh, s, warning)
        } catch (e: CancellationException) {
            throw e
        } catch (e: UnfollowApiException) {
            RefreshOutcome.Failed(
                when (e.httpCode) {
                    401 -> "The tracker refused the token. Check UNFOLLOW_TOKEN in local.properties matches the Worker's APP_TOKEN."
                    null -> "The tracker did not reply (${e.message}). Nothing was changed."
                    else -> "The tracker returned an error (HTTP ${e.httpCode}). Nothing was changed."
                },
            )
        } catch (e: UnfollowParseException) {
            RefreshOutcome.Failed("The tracker's reply could not be trusted, so nothing was stored: ${e.message}")
        } catch (e: Exception) {
            RefreshOutcome.Failed("The fetch failed, so nothing more was stored: ${e.message}")
        }
    }

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
