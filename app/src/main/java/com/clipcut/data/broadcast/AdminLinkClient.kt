package com.clipcut.data.broadcast

import com.clipcut.BuildConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.IOException
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Fetches the current viewing link from the site's admin endpoint.
 *
 * `GET <STREAM_URL>/admin/current-link?key=<STREAM_ADMIN_KEY>` answers with the link as plain text
 * and nothing else, e.g. `https://biggboss24hrs.com/access?token=<48 hex>`. The token behind it is
 * short-lived (~8h), which is the whole reason the link is fetched fresh at send time instead of
 * being typed in once.
 */
@Singleton
class AdminLinkClient @Inject constructor(private val client: OkHttpClient) {
    suspend fun currentLink(): String = withContext(Dispatchers.IO) {
        val base = BuildConfig.STREAM_URL.trimEnd('/')
        val key = BuildConfig.STREAM_ADMIN_KEY
        check(base.isNotBlank() && key.isNotBlank()) {
            "STREAM_URL / STREAM_ADMIN_KEY are missing from local.properties"
        }
        val request = Request.Builder().url("$base/admin/current-link?key=$key").get().build()
        val body = client.newCall(request).execute().use { response ->
            val text = response.body?.string().orEmpty().trim()
            if (!response.isSuccessful) {
                throw IOException("Admin link endpoint returned HTTP ${response.code}: ${text.take(120)}")
            }
            text
        }
        // Never broadcast whatever the endpoint happened to return: an error page or a blank body
        // would otherwise be sent to every group as if it were the link.
        check(body.startsWith("http://") || body.startsWith("https://")) {
            "Admin link endpoint did not return a link: ${body.take(120)}"
        }
        check(body.length < 500 && !body.contains(' ')) { "Admin link endpoint returned something odd" }
        body
    }
}
