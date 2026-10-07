package com.clipcut.domain.unfollows

import com.clipcut.BuildConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.IOException
import javax.inject.Inject

/**
 * Reads the tracker over HTTPS. The token is the Worker's APP_TOKEN (UNFOLLOW_TOKEN in local.properties); it is
 * sent as a header and never logged. The tracker holds the X secrets, so this app holds none for this feature.
 */
class RealUnfollowsApi @Inject constructor(private val client: OkHttpClient) : UnfollowsApi {
    private val baseUrl = BuildConfig.UNFOLLOW_URL.trim().trimEnd('/')
    private val token = BuildConfig.UNFOLLOW_TOKEN.trim()

    override val isConfigured: Boolean get() = baseUrl.startsWith("https://") && token.isNotEmpty()

    override suspend fun fetch(afterSeq: Long): UnfollowPage = withContext(Dispatchers.IO) {
        val request = Request.Builder()
            .url("$baseUrl/unfollows?after=$afterSeq")
            .header("Authorization", "Bearer $token")
            .get()
            .build()
        try {
            client.newCall(request).execute().use { response ->
                val body = response.body?.string().orEmpty()
                if (!response.isSuccessful) throw UnfollowApiException("HTTP ${response.code}", response.code)
                parseUnfollowPage(body)
            }
        } catch (e: IOException) {
            throw UnfollowApiException(e.message ?: "network error", null, e)
        }
    }
}
