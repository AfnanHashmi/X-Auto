package com.clipcut.domain.api.real

import com.clipcut.BuildConfig
import com.clipcut.domain.api.CaptionEnhancer
import com.clipcut.domain.api.CaptionMood
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.TimeUnit
import javax.inject.Inject

private const val ENDPOINT = "https://api.openai.com/v1/responses"
private const val MODEL = "gpt-5.6-luna"
// A reasoning-capable model can take well past OkHttp's default 10s read timeout.
private const val READ_TIMEOUT_SECONDS = 60L

/**
 * Rewrites the caption via the OpenAI Responses API. Never posts anything: the result only replaces
 * the text in the caption box (with an undo step), and the user still reviews it before posting.
 */
class RealCaptionEnhancer @Inject constructor(
    private val okHttpClient: OkHttpClient,
) : CaptionEnhancer {
    override suspend fun enhance(text: String, mood: CaptionMood): Result<String> = runCatching {
        require(BuildConfig.OPENAI_API_KEY.isNotBlank()) { "OPENAI_API_KEY is not set in local.properties" }

        val body = JSONObject()
            .put("model", MODEL)
            .put(
                "input",
                JSONArray()
                    .put(JSONObject().put("role", "system").put("content", buildPrompt(mood)))
                    .put(JSONObject().put("role", "user").put("content", text)),
            )

        val request = Request.Builder().url(ENDPOINT)
            .header("Authorization", "Bearer ${BuildConfig.OPENAI_API_KEY}")
            .header("Content-Type", "application/json")
            .post(body.toString().toRequestBody("application/json".toMediaType()))
            .build()

        val client = okHttpClient.newBuilder().readTimeout(READ_TIMEOUT_SECONDS, TimeUnit.SECONDS).build()
        withContext(Dispatchers.IO) {
            client.newCall(request).execute().use { response ->
                val responseText = response.body?.string().orEmpty()
                check(response.isSuccessful) { "OpenAI request failed: HTTP ${response.code}: $responseText" }
                extractText(JSONObject(responseText))
            }
        }
    }

    /**
     * The REST API has no `output_text` shortcut (that is an SDK convenience): the text lives in
     * `output[]` items of type `message`, in `content[]` parts of type `output_text`. Reasoning
     * items are skipped. An empty or refused result is an error, never an empty caption -- silently
     * replacing the user's text with nothing would be worse than failing.
     */
    private fun extractText(json: JSONObject): String {
        json.optJSONObject("error")?.let { error("OpenAI returned an error: ${it.optString("message", it.toString())}") }
        if (json.optString("status") == "incomplete") error("OpenAI response was incomplete: ${json.optJSONObject("incomplete_details")}")

        val output = json.optJSONArray("output") ?: error("OpenAI response had no output")
        val text = StringBuilder()
        for (i in 0 until output.length()) {
            val item = output.getJSONObject(i)
            if (item.optString("type") != "message") continue
            val content = item.optJSONArray("content") ?: continue
            for (j in 0 until content.length()) {
                val part = content.getJSONObject(j)
                when (part.optString("type")) {
                    "output_text" -> text.append(part.optString("text"))
                    "refusal" -> error("OpenAI declined to rewrite this caption: ${part.optString("refusal")}")
                }
            }
        }
        return text.toString().trim().ifEmpty { error("OpenAI returned an empty caption") }
    }
}
