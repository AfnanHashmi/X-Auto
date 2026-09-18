package com.clipcut.domain.api.real

import com.clipcut.BuildConfig
import com.clipcut.domain.api.CaptionEnhancer
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import javax.inject.Inject

private const val ENDPOINT = "https://api.openai.com/v1/chat/completions"
private const val MODEL = "gpt-4o-mini" // small/cheap/fast: a fitting tier for a short caption rewrite.
private const val SYSTEM_PROMPT =
    "You rewrite short social media captions to be punchier and more engaging, while preserving " +
        "the original meaning, tone, and any hashtags. Reply with only the rewritten caption, no " +
        "commentary or quotation marks."

class RealCaptionEnhancer @Inject constructor(
    private val okHttpClient: OkHttpClient,
) : CaptionEnhancer {
    override suspend fun enhance(text: String): Result<String> = runCatching {
        require(BuildConfig.OPENAI_API_KEY.isNotBlank()) { "OPENAI_API_KEY is not set in local.properties" }

        val body = JSONObject().apply {
            put("model", MODEL)
            put(
                "messages",
                JSONArray().apply {
                    put(JSONObject().put("role", "system").put("content", SYSTEM_PROMPT))
                    put(JSONObject().put("role", "user").put("content", text))
                },
            )
        }

        val request = Request.Builder().url(ENDPOINT)
            .header("Authorization", "Bearer ${BuildConfig.OPENAI_API_KEY}")
            .header("Content-Type", "application/json")
            .post(body.toString().toRequestBody("application/json".toMediaType()))
            .build()

        withContext(Dispatchers.IO) {
            okHttpClient.newCall(request).execute().use { response ->
                val responseText = response.body?.string().orEmpty()
                check(response.isSuccessful) { "OpenAI request failed: HTTP ${response.code}: $responseText" }
                JSONObject(responseText)
                    .getJSONArray("choices")
                    .getJSONObject(0)
                    .getJSONObject("message")
                    .getString("content")
                    .trim()
            }
        }
    }
}
