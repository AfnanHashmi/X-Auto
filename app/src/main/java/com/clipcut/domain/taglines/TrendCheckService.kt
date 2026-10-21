package com.clipcut.domain.taglines

import com.clipcut.domain.llm.LlmException
import kotlinx.coroutines.CancellationException
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

/** No more Trend check spending once this much has been spent today (phone's date): measured X charges plus the AI step. */
const val TREND_CHECK_DAILY_CAP_USD = 3.00

/** The cheapest budget a Full analysis accepts and the most one run may be given. */
const val TREND_MIN_BUDGET_USD = 0.10
const val TREND_MAX_BUDGET_USD = 3.00

/** The trend as the hourly counts show it: the whole last week and the range found in it. */
data class Detected(val hourly: List<HourBucket>, val range: TrendRange?)

/** A failure with a message fit to show. */
class TrendCheckException(message: String) : Exception(message)

/**
 * Trend check: finds when a tagline's trend ran, shows its exact shape from counts, and (on request, inside a budget) reads
 * a time-spread sample of the posts to tell organized from natural support. Everything it asks X is a read; nothing is posted.
 */
class TrendCheckService(
    private val counts: TaglinesApi,
    private val search: TrendSearchApi,
    private val store: TrendCheckRepository,
    private val contestants: () -> List<String>,
    /** Judges the sampled posts toward the listed contestants (one AI call). */
    private val judge: suspend (List<TcPost>, List<String>) -> Sentiment,
    private val zone: () -> ZoneId = ZoneId::systemDefault,
    private val nowMs: () -> Long = System::currentTimeMillis,
) {
    val isConfigured: Boolean get() = counts.isConfigured && search.isConfigured

    fun today(): LocalDate = Instant.ofEpochMilli(nowMs()).atZone(zone()).toLocalDate()

    fun spentToday(): Double = store.spentOn(today())

    fun costPerPost(): Double = store.costPerPost ?: DEFAULT_COST_PER_POST_USD

    fun costPerPostIsMeasured(): Boolean = store.costPerPost != null

    fun payerText(): String = search.payerText()

    /** Null when spending [usd] more today is allowed, else why not. */
    fun blockedReason(usd: Double): String? {
        val spent = spentToday()
        return if (spent + usd > TREND_CHECK_DAILY_CAP_USD) {
            "This would pass today's Trend check limit (${usdText(spent)} of ${usdText(TREND_CHECK_DAILY_CAP_USD)} spent). Try a smaller budget, or tomorrow."
        } else null
    }

    private fun window(): Pair<Long, Long> {
        val end = nowMs() - 30_000L
        return (end - TREND_LOOKBACK_MS) to end
    }

    private suspend fun <T> guarded(block: suspend () -> T): T = try {
        block()
    } catch (e: CancellationException) {
        throw e
    } catch (e: TaglineParseException) {
        throw TrendCheckException("${e.message}. Nothing was shown.")
    } catch (e: TrendParseException) {
        throw TrendCheckException("${e.message}. Nothing was kept.")
    } catch (e: TaglineApiException) {
        throw TrendCheckException(
            when (e.httpCode) {
                401, 403 -> "X refused the request (HTTP ${e.httpCode}): ${e.message}"
                429 -> "X says too many requests (HTTP 429). Wait a few minutes and try again."
                402 -> "X says every account is out of credits (HTTP 402). Top up Main, RW or DK."
                null -> "X did not reply: ${e.message}"
                else -> "X returned HTTP ${e.httpCode}: ${e.message}"
            },
        )
    }

    /** One counts call (about $0.005) over the last 7 days, and the trend range found in it. */
    suspend fun detect(tagline: String): Detected = guarded {
        val (start, end) = window()
        val hourly = counts.counts(phraseQuery(tagline, withRetweets = true), start, end).hours
        Detected(hourly, detectRange(hourly, nowMs()))
    }

    /** The exact numbers for [range]: four counts calls (about $0.02). Free of any sampling. Saved with the trend. */
    suspend fun live(tagline: String, range: TrendRange): TrendCheckResult = guarded {
        val (_, end) = window()
        val to = minOf(range.endMs.coerceAtLeast(range.startMs + HOUR_MS), end)
        val all = counts.counts(phraseQuery(tagline, true), range.startMs, to)
        val originals = counts.counts(phraseQuery(tagline, false), range.startMs, to)
        val replies = counts.counts(phraseQuery(tagline, true) + " is:reply", range.startMs, to)
        val quotes = counts.counts(phraseQuery(tagline, true) + " is:quote", range.startMs, to)
        val previous = store.find(tagline, range.startMs)
        val result = TrendCheckResult(
            tagline = tagline, startMs = range.startMs, endMs = range.endMs, ended = range.ended, atMs = nowMs(),
            shares = CountShares(all.total, originals.total, replies.total, quotes.total),
            hourly = all.hours, launch = launchType(all.hours), waves = pushWaves(all.hours),
            // The sample, if there is one, stays attached: live refresh only renews the exact numbers.
            stats = previous?.stats, sentiment = previous?.sentiment, postsRead = previous?.postsRead ?: 0,
            readHours = previous?.readHours.orEmpty(), costUsd = previous?.costUsd,
        )
        store.save(result)
        result
    }

    /** What a Full analysis with [budgetUsd] would buy for [check] (hours already read are skipped). Null: budget too small. */
    fun plan(check: TrendCheckResult, budgetUsd: Double): TrendPlan? =
        planSlices(check.hourly, check.readHours.toSet(), budgetUsd, costPerPost())

    /**
     * Reads the planned posts, adds them to those read before, and works out the sample statistics and sentiment. The X cost
     * is measured from the balances before and after (free reads) and kept as the price per post for the next estimate.
     */
    suspend fun full(check: TrendCheckResult, budgetUsd: Double): TrendCheckResult = guarded {
        if (budgetUsd < TREND_MIN_BUDGET_USD || budgetUsd > TREND_MAX_BUDGET_USD) {
            throw TrendCheckException("Budget must be between ${usdText(TREND_MIN_BUDGET_USD)} and ${usdText(TREND_MAX_BUDGET_USD)}.")
        }
        blockedReason(budgetUsd)?.let { throw TrendCheckException(it) }
        val plan = plan(check, budgetUsd) ?: throw TrendCheckException("That budget cannot buy a single search. Try at least ${usdText(MIN_PER_CALL * costPerPost())}.")
        if (plan.slices.isEmpty()) throw TrendCheckException("Every hour of this trend has already been read.")

        val before = search.balanceUsd()
        val fetched = ArrayList<TcPost>()
        val authors = HashMap<String, TcAuthor>()
        val coveredHours = ArrayList<Long>()
        val query = "\"${check.tagline}\""
        var readError: Exception? = null
        try {
            for (slice in plan.slices) {
                var token: String? = null
                var got = 0
                while (got < slice.posts) {
                    val page = search.search(query, slice.startMs, slice.endMs, (slice.posts - got).coerceIn(MIN_PER_CALL, MAX_PER_CALL), token)
                    val room = slice.posts - got
                    fetched += page.posts.take(room)
                    authors += page.authors
                    got += page.posts.size.coerceAtMost(room)
                    token = page.nextToken
                    if (page.posts.isEmpty() || token == null) break
                }
                coveredHours += plan.hoursCovered.filter { it >= slice.startMs && it < slice.endMs }
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            readError = e // keep what was bought: it is paid for
        }
        val after = search.balanceUsd()
        val spent = if (before != null && after != null) (before - after).coerceAtLeast(0.0) else null
        // The plan's most is the fall-back when the balances could not be read.
        store.add(today(), spent ?: (fetched.size * costPerPost()))
        if (spent != null && spent > 0 && fetched.isNotEmpty()) store.costPerPost = spent / fetched.size

        val prior = store.sample(check.key)
        val known = (prior?.posts.orEmpty()).associateBy { it.id }
        val merged = (known + fetched.associateBy { it.id }).values.toList()
        val allAuthors = (prior?.authors.orEmpty()) + authors
        if (merged.isNotEmpty()) store.saveSample(check.key, StoredSample(merged, allAuthors))
        val names = contestants()
        val stats = sampleStats(merged, allAuthors, check.tagline, names)
        var sentiment: Sentiment? = null
        var sentimentNote: String? = null
        if (stats != null) {
            try {
                sentiment = judge(merged, names).also { s -> s.costUsd?.let { store.add(today(), it) } }
            } catch (e: CancellationException) {
                throw e
            } catch (e: LlmException) {
                sentimentNote = e.message
            }
        }
        val result = check.copy(
            atMs = nowMs(), stats = stats, sentiment = sentiment ?: check.sentiment, postsRead = merged.size,
            readHours = (check.readHours + coveredHours).distinct().sorted(),
            costUsd = (check.costUsd ?: 0.0) + (spent ?: 0.0),
        )
        store.save(result)
        readError?.let { throw TrendCheckException("Stopped part-way: ${it.message}. ${fetched.size} posts were read and kept.") }
        if (sentimentNote != null && stats != null) throw TrendCheckException("Posts read, but the sentiment step failed: $sentimentNote. The rest is saved.")
        result
    }
}
