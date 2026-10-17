package com.clipcut.domain.bossmeter

import com.clipcut.domain.api.BOSS_METER_ORDER
import com.clipcut.domain.api.XReadException
import com.clipcut.domain.api.XReadPool
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl.Companion.toHttpUrl
import javax.inject.Inject

/**
 * Boss Meter's counts with an app-only token. Main's app pays first; when Main is low or out of credits, [XReadPool] moves the
 * call to RW's or DK's app. Read only.
 */
class RealBossMeterApi @Inject constructor(private val pool: XReadPool) : BossMeterApi {
    override val isConfigured: Boolean get() = pool.isAnyConfigured(BOSS_METER_ORDER)

    override suspend fun count(query: String): Long = withContext(Dispatchers.IO) {
        val url = "https://api.x.com/2/tweets/counts/recent".toHttpUrl().newBuilder()
            .addQueryParameter("query", query)
            .addQueryParameter("granularity", "day")
            .build()
        val body = try {
            pool.read(BOSS_METER_ORDER, url.toString(), BOSS_CALL_USD).second
        } catch (e: XReadException) {
            throw BossMeterException(e.message ?: "error", e.httpCode, e.retryAtEpochSec)
        }
        parseTotal(body)
    }
}
