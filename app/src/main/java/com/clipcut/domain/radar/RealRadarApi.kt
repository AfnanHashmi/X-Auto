package com.clipcut.domain.radar

import com.clipcut.BuildConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONException
import org.json.JSONObject
import java.io.IOException
import javax.inject.Inject

/**
 * Talks to the Trend Radar Worker over HTTPS. The token (TREND_TOKEN in local.properties) is the Worker's TREND_APP_TOKEN,
 * sent as a header and never logged. The Worker holds the xAI key, so this app holds none.
 */
class RealRadarApi @Inject constructor(private val client: OkHttpClient) : RadarApi {
    private val baseUrl = BuildConfig.TREND_URL.trim().trimEnd('/')
    private val token = BuildConfig.TREND_TOKEN.trim()

    override val isConfigured: Boolean get() = baseUrl.startsWith("https://") && token.isNotEmpty()

    override suspend fun report(): RadarReport = get("/report") { parseRadarReport(it) }

    override suspend fun preview(type: RunKind, posts: Int?): RunPreview =
        get("/preview?type=${type.name.lowercase()}" + (posts?.let { "&posts=$it" } ?: "")) { parsePreview(it) }

    override suspend fun session(on: Boolean, budgetUsd: Double?) {
        val body = JSONObject().put("on", on)
        if (on && budgetUsd != null) body.put("budget_usd", budgetUsd)
        post("/session", body)
    }

    override suspend fun run(type: RunKind, posts: Int?, focus: String?, dry: Boolean) {
        val body = JSONObject().put("type", type.name.lowercase())
        posts?.let { body.put("posts", it) }
        focus?.let { body.put("focus", it) }
        if (dry) body.put("dry", true)
        post("/run", body)
    }

    override suspend fun config(patch: JSONObject) = post("/config", patch)

    override suspend fun exclude(handle: String) = post("/exclude", JSONObject().put("handle", handle))

    private suspend fun <T> get(path: String, parse: (String) -> T): T = withContext(Dispatchers.IO) {
        execute(Request.Builder().url(baseUrl + path).header("Authorization", "Bearer $token").get().build(), parse)
    }

    private suspend fun post(path: String, body: JSONObject) = withContext(Dispatchers.IO) {
        execute(
            Request.Builder().url(baseUrl + path).header("Authorization", "Bearer $token")
                .post(body.toString().toRequestBody("application/json".toMediaType())).build(),
        ) { }
    }

    private fun <T> execute(request: Request, parse: (String) -> T): T = try {
        client.newCall(request).execute().use { response ->
            val text = response.body?.string().orEmpty()
            if (!response.isSuccessful) throw RadarApiException(reasonOf(text) ?: "HTTP ${response.code}", response.code)
            parse(text)
        }
    } catch (e: IOException) {
        throw RadarApiException(e.message ?: "network error", null, e)
    }

    /** The Worker answers errors as {"error": "..."} with a reason written for a person. */
    private fun reasonOf(body: String): String? =
        try {
            JSONObject(body).optString("error").takeIf { it.isNotBlank() }
        } catch (e: JSONException) {
            null
        }
}
