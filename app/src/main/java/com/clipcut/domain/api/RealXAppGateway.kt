package com.clipcut.domain.api

import com.clipcut.BuildConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient

/** App-only reads with each account's own X app: Main (`X_MAIN_*`), RW (`X_RW_*`) and DK (`X_MEDIA_*`). Read only. */
class RealXAppGateway(client: OkHttpClient) : XAppGateway {
    private val clients = mapOf(
        XApp.MAIN to XAppOnlyClient(client, BuildConfig.X_MAIN_CONSUMER_KEY, BuildConfig.X_MAIN_CONSUMER_SECRET),
        XApp.RW to XAppOnlyClient(client, BuildConfig.X_RW_CONSUMER_KEY, BuildConfig.X_RW_CONSUMER_SECRET),
        XApp.DK to XAppOnlyClient(client, BuildConfig.X_MEDIA_CONSUMER_KEY, BuildConfig.X_MEDIA_CONSUMER_SECRET),
    )

    override fun isConfigured(app: XApp): Boolean = clients.getValue(app).isConfigured

    override suspend fun get(app: XApp, url: String): String = withContext(Dispatchers.IO) { clients.getValue(app).get(url) }
}
