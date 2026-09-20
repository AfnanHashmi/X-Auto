package com.clipcut.data.telegram

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
import javax.inject.Singleton

/** A Telegram Bot API error that came back as a real reply (so it is definitive, not a timeout). */
class TelegramException(message: String, val httpCode: Int? = null, val retryAfterSec: Long? = null) : IOException(message)

/**
 * Telegram Bot API, just enough to post a message into a group the bot is already a member of.
 *
 * The bot token comes from BotFather and lives in local.properties. Unlike X, there is no OAuth
 * here: the token is the whole credential, which is why it is never logged or shown in the UI.
 */
@Singleton
class TelegramClient @Inject constructor(private val client: OkHttpClient) {
    val isConfigured: Boolean get() = BuildConfig.TELEGRAM_BOT_TOKEN.isNotBlank()

    /** Returns the sent message's id. [chatId] is a group id (negative, e.g. -1001234567890) or @name. */
    suspend fun sendMessage(chatId: String, text: String, disablePreview: Boolean = false): Long =
        withContext(Dispatchers.IO) {
            val json = JSONObject()
                .put("chat_id", chatId)
                .put("text", text)
                .put("disable_web_page_preview", disablePreview)
            val body = post("sendMessage", json)
            body.getJSONObject("result").getLong("message_id")
        }

    /** The bot's own @username, used to prove the token works before anything is sent. */
    suspend fun me(): String = withContext(Dispatchers.IO) {
        post("getMe", JSONObject()).getJSONObject("result").optString("username")
    }

    private fun post(method: String, json: JSONObject): JSONObject {
        val token = BuildConfig.TELEGRAM_BOT_TOKEN
        check(token.isNotBlank()) { "TELEGRAM_BOT_TOKEN is missing from local.properties" }
        val request = Request.Builder()
            .url("https://api.telegram.org/bot$token/$method")
            .post(json.toString().toRequestBody("application/json".toMediaType()))
            .build()
        return client.newCall(request).execute().use { response ->
            val text = response.body?.string().orEmpty()
            val parsed = runCatching { JSONObject(text) }.getOrNull()
            if (!response.isSuccessful || parsed?.optBoolean("ok") != true) {
                // description carries the real reason ("chat not found", "bot was kicked", ...).
                val reason = parsed?.optString("description")?.takeIf { it.isNotBlank() } ?: text.take(160)
                throw TelegramException(
                    "Telegram $method failed: HTTP ${response.code}: $reason",
                    response.code,
                    parsed?.optJSONObject("parameters")?.optLong("retry_after")?.takeIf { it > 0 },
                )
            }
            parsed
        }
    }
}
