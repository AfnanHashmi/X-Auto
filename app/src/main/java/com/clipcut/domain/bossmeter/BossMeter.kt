package com.clipcut.domain.bossmeter

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import org.json.JSONException
import org.json.JSONObject

/**
 * Boss Meter: the hashtag vote counter from boss_meter.py, unchanged in logic.
 *
 *  - total thread = count of `conversation_id:<id>` (one extra call)
 *  - per hashtag  = count of `conversation_id:<id> <#tag>`
 *  - percentages are of the tracked-vote sum (not of the thread)
 *  - uncounted    = total thread - tracked sum (no-hashtag replies, untracked variants, deleted or filtered;
 *    NOT provably spam)
 *
 * Every count is X's `GET /2/tweets/counts/recent` with `granularity=day` and no start or end, so X's default
 * window: the last 7 days. The post must be inside that window.
 */

class BossMeterException(message: String, val httpCode: Int? = null, val retryAtEpochSec: Long? = null) : Exception(message)

interface BossMeterApi {
    val isConfigured: Boolean

    /** X's `total_tweet_count` for [query] (0 when X leaves it out). One call, billed by X per call. */
    suspend fun count(query: String): Long
}

data class TagCount(val tag: String, val count: Long)

data class BossMeterResult(val conversationId: String, val totalThread: Long, val tags: List<TagCount>, val fetchedAtMs: Long) {
    val trackedSum: Long get() = tags.sumOf { it.count }
    val uncounted: Long get() = totalThread - trackedSum

    /** Share of hashtag votes, as the script prints it. */
    fun percent(t: TagCount): Double = if (trackedSum == 0L) 0.0 else t.count * 100.0 / trackedSum

    val uncountedPercentOfThread: Double? get() = if (totalThread == 0L) null else uncounted * 100.0 / totalThread

    /** Most votes first, like the script's summary. */
    val ranked: List<TagCount> get() = tags.sortedByDescending { it.count }
}

sealed interface BossMeterOutcome {
    data class Done(val result: BossMeterResult) : BossMeterOutcome
    data class Failed(val reason: String, val callsMade: Int) : BossMeterOutcome
}

const val BOSS_CALL_USD = 0.005
const val MAX_HASHTAGS = 20

/** One call for the thread plus one per hashtag. */
fun bossMeterCostUsd(hashtags: Int): Double = (hashtags + 1) * BOSS_CALL_USD

private val STATUS_LINK = Regex("""(?:x|twitter)\.com/[^/?#\s]+/status(?:es)?/(\d{5,25})""", RegexOption.IGNORE_CASE)
private val BARE_ID = Regex("""^\d{5,25}$""")

/** The post id from an x.com or twitter.com link (or a bare id). Null when it is neither. */
fun postIdFrom(link: String): String? {
    val s = link.trim()
    if (BARE_ID.matches(s)) return s
    return STATUS_LINK.find(s)?.groupValues?.get(1)
}

/**
 * Hashtags from free text: split on spaces, commas or new lines; a missing `#` is added; repeats (any case) are dropped,
 * keeping the first spelling and the order given.
 */
fun hashtagsFrom(text: String): List<String> =
    text.split(Regex("[\\s,]+"))
        .map { it.trim().trimStart('#') }
        .filter { it.isNotEmpty() && it.all { c -> c.isLetterOrDigit() || c == '_' } }
        .map { "#$it" }
        .distinctBy { it.lowercase() }

private val LOOKALIKE = Regex("(?<=[a-z])ls(?=[A-Z])")

/**
 * "...lsTheBoss": a lowercase L where a capital I almost certainly belongs ("IsTheBoss"). The two look the same in many
 * fonts, so pasted text can carry the wrong one, and X then counts a different, almost unused hashtag. Returns tag to
 * suggested spelling; the text is never changed without the user's tap.
 */
fun lookalikeFixes(hashtags: List<String>): Map<String, String> =
    hashtags.associateWith { LOOKALIKE.replace(it, "Is") }.filter { (tag, fix) -> tag != fix }

/** The script's `get_count`: `meta.total_tweet_count`, 0 when absent. */
fun parseTotal(body: String): Long = try {
    JSONObject(body).optJSONObject("meta")?.optLong("total_tweet_count", 0L) ?: 0L
} catch (e: JSONException) {
    throw BossMeterException("X sent a reply that is not valid JSON")
}

class BossMeterService(
    private val api: BossMeterApi,
    private val nowMs: () -> Long = System::currentTimeMillis,
    private val pause: suspend (Long) -> Unit = { delay(it) },
) {
    /**
     * Runs the counts in the script's order: the thread total, then each hashtag, half a second apart. On a rate limit
     * it waits until X's reset time, as the script does (at most [MAX_WAIT_SEC] at a time), and tries that call again.
     */
    suspend fun run(link: String, hashtags: List<String>, onProgress: (String) -> Unit = {}): BossMeterOutcome {
        if (!api.isConfigured) return BossMeterOutcome.Failed("No X app keys are in local.properties (Main, RW or DK).", 0)
        val id = postIdFrom(link) ?: return BossMeterOutcome.Failed("That is not a post link. Paste a link like https://x.com/user/status/123…", 0)
        if (hashtags.isEmpty()) return BossMeterOutcome.Failed("Add at least one hashtag.", 0)
        if (hashtags.size > MAX_HASHTAGS) return BossMeterOutcome.Failed("At most $MAX_HASHTAGS hashtags at a time.", 0)

        var calls = 0
        suspend fun count(query: String): Long {
            while (true) {
                try {
                    return api.count(query).also { calls++ }
                } catch (e: BossMeterException) {
                    if (e.httpCode != 429) throw e
                    val wait = maxOf((e.retryAtEpochSec ?: (nowMs() / 1000 + 15)) - nowMs() / 1000, 1L)
                    if (wait > MAX_WAIT_SEC) throw BossMeterException("X's rate limit resets in ${wait / 60} minutes. Try again later.", 429)
                    onProgress("Rate limited. Waiting ${wait}s…")
                    pause(wait * 1000)
                }
            }
        }

        return try {
            onProgress("Counting the whole thread…")
            val total = count("conversation_id:$id")
            pause(500)
            val tags = hashtags.mapIndexed { i, tag ->
                onProgress("Counting $tag (${i + 1} of ${hashtags.size})…")
                TagCount(tag, count("conversation_id:$id $tag")).also { pause(500) }
            }
            BossMeterOutcome.Done(BossMeterResult(id, total, tags, nowMs()))
        } catch (e: CancellationException) {
            throw e
        } catch (e: BossMeterException) {
            val why = when (e.httpCode) {
                401, 403 -> "X refused the request (HTTP ${e.httpCode}): ${e.message}"
                402 -> "X says every account is out of credits (HTTP 402). Top up Main, RW or DK."
                429 -> e.message ?: "X's rate limit was hit."
                null -> "X did not reply: ${e.message}"
                else -> "X returned HTTP ${e.httpCode}: ${e.message}"
            }
            BossMeterOutcome.Failed("$why $calls call(s) had already gone through and may have been billed.", calls)
        }
    }

    companion object {
        const val MAX_WAIT_SEC = 15 * 60L
    }
}
