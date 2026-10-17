package com.clipcut.domain.api

import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import okhttp3.FormBody
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.io.IOException
import java.net.URLEncoder
import java.util.Base64

/** A failed read from X. [httpCode] is null when no reply came back; [retryAtEpochSec] is X's rate-limit reset, if sent. */
class XReadException(message: String, val httpCode: Int? = null, val retryAtEpochSec: Long? = null, cause: Throwable? = null) : Exception(message, cause)

/**
 * Read-only GETs to X with an app-only (Bearer) token. The token is requested from an app's consumer key and secret
 * (`POST /oauth2/token`, free) the first time it is needed and kept in memory only. Secrets and tokens are never logged.
 * Blocking: call from Dispatchers.IO.
 */
class XAppOnlyClient(private val client: OkHttpClient, private val consumerKey: String, private val consumerSecret: String) {
    private val lock = Mutex()
    private var bearer: String? = null

    val isConfigured: Boolean get() = consumerKey.isNotBlank() && consumerSecret.isNotBlank()

    suspend fun get(url: String): String {
        val token = lock.withLock { bearer ?: fetchToken().also { bearer = it } }
        return call(Request.Builder().url(url).header("Authorization", "Bearer $token").get().build())
    }

    private fun fetchToken(): String {
        fun enc(s: String) = URLEncoder.encode(s, "UTF-8")
        val basic = Base64.getEncoder().encodeToString("${enc(consumerKey.trim())}:${enc(consumerSecret.trim())}".toByteArray())
        val request = Request.Builder()
            .url("https://api.x.com/oauth2/token")
            .header("Authorization", "Basic $basic")
            .post(FormBody.Builder().add("grant_type", "client_credentials").build())
            .build()
        return JSONObject(call(request)).optString("access_token").takeIf { it.isNotBlank() }
            ?: throw XReadException("X did not return an access token")
    }

    private fun call(request: Request): String = try {
        client.newCall(request).execute().use { response ->
            val text = response.body?.string().orEmpty()
            if (!response.isSuccessful) {
                // X explains itself in "detail"; show that, never the request.
                val detail = runCatching { JSONObject(text).optString("detail") }.getOrNull().orEmpty().ifBlank { "no detail" }
                throw XReadException(detail, response.code, response.header("x-rate-limit-reset")?.toLongOrNull())
            }
            text
        }
    } catch (e: IOException) {
        throw XReadException(e.message ?: "network error", null, null, e)
    }
}
