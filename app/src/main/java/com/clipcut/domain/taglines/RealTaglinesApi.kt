package com.clipcut.domain.taglines

import com.clipcut.domain.api.MediaAccount
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import okhttp3.FormBody
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.io.IOException
import java.net.URLEncoder
import java.time.Instant
import java.util.Base64
import javax.inject.Inject

/**
 * Counts tweets through X's official API (`GET /2/tweets/counts/recent`) with RW's app. X does not accept RW's OAuth 1.0a
 * user keys on this endpoint, so an app-only token is requested from RW's own consumer key and secret (a free call) and
 * kept in memory only. Nothing is ever posted. Secrets are never logged.
 */
class RealTaglinesApi @Inject constructor(private val client: OkHttpClient) : TaglinesApi {
    private val creds = MediaAccount.RW.credentials
    private val tokenLock = Mutex()
    private var bearer: String? = null

    override val isConfigured: Boolean get() = creds.consumerKey.isNotBlank() && creds.consumerSecret.isNotBlank()

    override suspend fun counts(query: String, startMs: Long, endMs: Long): CountSeries = withContext(Dispatchers.IO) {
        val url = "https://api.x.com/2/tweets/counts/recent".toHttpUrl().newBuilder()
            .addQueryParameter("query", query)
            .addQueryParameter("granularity", "hour")
            .addQueryParameter("start_time", Instant.ofEpochMilli(startMs).toString())
            .addQueryParameter("end_time", Instant.ofEpochMilli(endMs).toString())
            .build()
        val body = send(url.toString(), token())
        parseCounts(body)
    }

    private suspend fun token(): String = tokenLock.withLock {
        bearer ?: fetchToken().also { bearer = it }
    }

    private fun fetchToken(): String {
        fun enc(s: String) = URLEncoder.encode(s, "UTF-8")
        val basic = Base64.getEncoder().encodeToString("${enc(creds.consumerKey)}:${enc(creds.consumerSecret)}".toByteArray())
        val request = Request.Builder()
            .url("https://api.x.com/oauth2/token")
            .header("Authorization", "Basic $basic")
            .post(FormBody.Builder().add("grant_type", "client_credentials").build())
            .build()
        val text = call(request)
        return JSONObject(text).optString("access_token").takeIf { it.isNotBlank() }
            ?: throw TaglineApiException("X did not return an access token")
    }

    private fun send(url: String, token: String): String =
        call(Request.Builder().url(url).header("Authorization", "Bearer $token").get().build())

    private fun call(request: Request): String = try {
        client.newCall(request).execute().use { response ->
            val text = response.body?.string().orEmpty()
            if (!response.isSuccessful) {
                // X explains itself in "detail"; show that, never the request.
                val detail = runCatching { JSONObject(text).optString("detail") }.getOrNull().orEmpty().ifBlank { "no detail" }
                throw TaglineApiException(detail, response.code)
            }
            text
        }
    } catch (e: IOException) {
        throw TaglineApiException(e.message ?: "network error", null, e)
    }
}
