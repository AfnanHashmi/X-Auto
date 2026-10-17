package com.clipcut.domain.taglines

import com.clipcut.domain.api.TAGLINE_ORDER
import com.clipcut.domain.api.XReadException
import com.clipcut.domain.api.XReadPool
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl.Companion.toHttpUrl
import java.time.Instant
import javax.inject.Inject

/**
 * Counts tweets through X's official API (`GET /2/tweets/counts/recent`) with an app-only token. RW's app pays first; when
 * RW is low or out of credits, [XReadPool] moves the call to Main's or DK's app. Nothing is ever posted.
 */
class RealTaglinesApi @Inject constructor(private val pool: XReadPool) : TaglinesApi {
    override val isConfigured: Boolean get() = pool.isAnyConfigured(TAGLINE_ORDER)

    override suspend fun counts(query: String, startMs: Long, endMs: Long): CountSeries = withContext(Dispatchers.IO) {
        val url = "https://api.x.com/2/tweets/counts/recent".toHttpUrl().newBuilder()
            .addQueryParameter("query", query)
            .addQueryParameter("granularity", "hour")
            .addQueryParameter("start_time", Instant.ofEpochMilli(startMs).toString())
            .addQueryParameter("end_time", Instant.ofEpochMilli(endMs).toString())
            .build()
        val body = try {
            pool.read(TAGLINE_ORDER, url.toString(), COUNTS_CALL_USD).second
        } catch (e: XReadException) {
            throw TaglineApiException(e.message ?: "error", e.httpCode, e)
        }
        parseCounts(body)
    }
}
