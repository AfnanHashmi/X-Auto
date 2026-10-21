package com.clipcut.domain.taglines

import com.clipcut.domain.api.TAGLINE_ORDER
import com.clipcut.domain.api.XReadException
import com.clipcut.domain.api.XReadPool
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl.Companion.toHttpUrl
import java.time.Instant
import javax.inject.Inject

/** X's recent search, read only. One call returns posts together with their authors. */
interface TrendSearchApi {
    val isConfigured: Boolean

    /** Up to [maxResults] (10 to 100) posts for [query] between [startMs] and [endMs], newest first. */
    suspend fun search(query: String, startMs: Long, endMs: Long, maxResults: Int, nextToken: String? = null): SearchPage

    /** What the accounts that pay for tagline reads have left in total, read fresh (free), or null when it cannot be read. */
    suspend fun balanceUsd(): Double?

    /** Who would pay, and what they have left, for the cost box. */
    fun payerText(): String
}

class RealTrendSearchApi @Inject constructor(private val pool: XReadPool) : TrendSearchApi {
    override val isConfigured: Boolean get() = pool.isAnyConfigured(TAGLINE_ORDER)

    override suspend fun search(query: String, startMs: Long, endMs: Long, maxResults: Int, nextToken: String?): SearchPage =
        withContext(Dispatchers.IO) {
            val url = "https://api.x.com/2/tweets/search/recent".toHttpUrl().newBuilder()
                .addQueryParameter("query", query)
                .addQueryParameter("start_time", Instant.ofEpochMilli(startMs).toString())
                .addQueryParameter("end_time", Instant.ofEpochMilli(endMs).toString())
                .addQueryParameter("max_results", maxResults.coerceIn(MIN_PER_CALL, MAX_PER_CALL).toString())
                .addQueryParameter("tweet.fields", SEARCH_TWEET_FIELDS)
                .addQueryParameter("expansions", SEARCH_EXPANSIONS)
                .addQueryParameter("user.fields", SEARCH_USER_FIELDS)
                .apply { nextToken?.let { addQueryParameter("next_token", it) } }
                .build()
            val body = try {
                pool.read(TAGLINE_ORDER, url.toString(), maxResults * DEFAULT_COST_PER_POST_USD).second
            } catch (e: XReadException) {
                throw TaglineApiException(e.message ?: "error", e.httpCode, e)
            }
            parseSearch(body)
        }

    override suspend fun balanceUsd(): Double? {
        pool.refreshBalances(force = true)
        val b = pool.balances.value
        val known = TAGLINE_ORDER.mapNotNull { b.usd(it) }
        return known.takeIf { it.isNotEmpty() }?.sum()
    }

    override fun payerText(): String = pool.payerText(TAGLINE_ORDER)
}
