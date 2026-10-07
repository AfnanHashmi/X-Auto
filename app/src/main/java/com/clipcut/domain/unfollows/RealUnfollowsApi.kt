package com.clipcut.domain.unfollows

import com.clipcut.BuildConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.io.IOException
import javax.inject.Inject

/**
 * Talks to the tracker over HTTPS. The token is the Worker's APP_TOKEN (UNFOLLOW_TOKEN in local.properties); it is
 * sent as a header and never logged. The tracker holds the X secrets, so this app holds none for this feature.
 */
class RealUnfollowsApi @Inject constructor(private val client: OkHttpClient) : UnfollowsApi {
    private val baseUrl = BuildConfig.UNFOLLOW_URL.trim().trimEnd('/')
    private val token = BuildConfig.UNFOLLOW_TOKEN.trim()

    override val isConfigured: Boolean get() = baseUrl.startsWith("https://") && token.isNotEmpty()

    override suspend fun fetch(afterVersion: Long): UnfollowPage = withContext(Dispatchers.IO) {
        val request = Request.Builder()
            .url("$baseUrl/unfollows?since_version=$afterVersion")
            .header("Authorization", "Bearer $token")
            .get()
            .build()
        execute(request) { parseUnfollowPage(it) }
    }

    override suspend fun setMode(mode: String) = post("/settings", JSONObject().put("mode", mode))

    override suspend fun resume() = post("/resume", JSONObject())

    override suspend fun whitelist(userId: String, keep: Boolean) = post("/whitelist", JSONObject().put("user_id", userId).put("keep", keep))

    private suspend fun post(path: String, body: JSONObject) = withContext(Dispatchers.IO) {
        val request = Request.Builder()
            .url("$baseUrl$path")
            .header("Authorization", "Bearer $token")
            .post(body.toString().toRequestBody("application/json".toMediaType()))
            .build()
        execute(request) { }
    }

    private fun <T> execute(request: Request, parse: (String) -> T): T = try {
        client.newCall(request).execute().use { response ->
            val text = response.body?.string().orEmpty()
            if (!response.isSuccessful) throw UnfollowApiException("HTTP ${response.code}", response.code)
            parse(text)
        }
    } catch (e: IOException) {
        throw UnfollowApiException(e.message ?: "network error", null, e)
    }
}
