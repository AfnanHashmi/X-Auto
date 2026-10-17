package com.clipcut.domain.taglines

import kotlinx.coroutines.CancellationException

/** A failed call to X. [httpCode] is null when no reply came back at all. */
class TaglineApiException(message: String, val httpCode: Int? = null, cause: Throwable? = null) : Exception(message, cause)

/** X's tweet-counts endpoint. It only ever reads: there is no call here that posts, deletes or follows. */
interface TaglinesApi {
    val isConfigured: Boolean

    /** Tweets matching [query] per hour between [startMs] and [endMs]. One call, billed by X per call. */
    suspend fun counts(query: String, startMs: Long, endMs: Long): CountSeries
}

sealed interface CountOutcome {
    data class Done(val report: CountReport) : CountOutcome
    data class Failed(val reason: String, val callsMade: Int) : CountOutcome
}

/**
 * Counts each tagline for the last 24 hours. Only ever runs when asked. If any call fails, nothing is kept (half a table
 * would mislead) and the reason says how many calls had already gone through, since X may have billed those.
 */
class TaglinesService(
    private val api: TaglinesApi,
    private val nowMs: () -> Long = System::currentTimeMillis,
) {
    suspend fun count(taglines: List<String>): CountOutcome {
        if (!api.isConfigured) return CountOutcome.Failed("No X app keys are in local.properties (RW, Main or DK).", 0)
        val clean = taglines.mapNotNull(::cleanTagline).distinctBy { it.lowercase() }
        if (clean.isEmpty()) return CountOutcome.Failed("Add at least one tagline first.", 0)
        if (clean.size > MAX_TAGLINES) return CountOutcome.Failed("At most $MAX_TAGLINES taglines at a time, to keep the cost small.", 0)

        val now = nowMs()
        // X wants the end of the window to be a few seconds in the past.
        val end = now - 30_000L
        val start = end - 24 * 3_600_000L
        var calls = 0
        return try {
            val counts = clean.map { t ->
                val all = api.counts(phraseQuery(t, withRetweets = true), start, end).also { calls++ }
                val originals = api.counts(phraseQuery(t, withRetweets = false), start, end).also { calls++ }
                TaglineCount(t, all, originals)
            }
            CountOutcome.Done(CountReport(start, end, now, counts))
        } catch (e: CancellationException) {
            throw e
        } catch (e: TaglineParseException) {
            CountOutcome.Failed("${e.message}. Nothing was shown. $calls call(s) had already gone through and may have been billed.", calls)
        } catch (e: TaglineApiException) {
            val why = when (e.httpCode) {
                401, 403 -> "X refused the request (HTTP ${e.httpCode}): ${e.message}"
                429 -> "X says too many requests (HTTP 429). Wait a few minutes and try again."
                402 -> "X says every account is out of credits (HTTP 402). Top up Main, RW or DK."
                null -> "X did not reply: ${e.message}"
                else -> "X returned HTTP ${e.httpCode}: ${e.message}"
            }
            CountOutcome.Failed("$why $calls call(s) had already gone through and may have been billed.", calls)
        }
    }
}
