package com.clipcut.domain.bossmeter

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject

/**
 * Boss Meter's Instagram side. The comments are watched and stored by igscraper on the server; the counting (the same
 * rules as the X Boss Meter) runs there too, so the phone only receives the finished numbers. Nothing here talks to X.
 */
class IgException(message: String, val httpCode: Int? = null) : Exception(message)

data class IgItem(
    val label: String,
    val count: Long,
    val share: Double,
    val strictCount: Long,
    val strictShare: Double,
    val lookalikeTag: String?,
    val lookalikeCount: Long,
)

data class IgBossResult(
    val shortcode: String,
    /** The server had no comments for the window: shown as empty, never as zeros. */
    val empty: Boolean,
    val total: Long,
    val tracked: Long,
    val uncounted: Long,
    val uncountedPct: Double,
    val trackedStrict: Long,
    val doubleCounted: Long,
    val items: List<IgItem>,
    val leadText: String?,
    val topUntracked: List<Pair<String, Long>>,
    val breakdown: Map<String, Long>,
    val checks: Map<String, Boolean>,
    val lastCommentAtIst: String?,
    val reported: Long?,
    /** The cut-off (unix seconds) the count was asked for, or null for "everything fetched so far". */
    val untilTs: Long?,
    val excludeHidden: Boolean,
    /** When the phone received this. */
    val fetchedAtMs: Long,
    /** The server's reply as received, kept so reopening the screen shows it without a request. */
    val raw: String = "",
) {
    val checksOk: Boolean get() = checks.values.all { it }
}

/** Reads the server's `/bossmeter` reply. Strict about the parts it needs; throws [IgException] otherwise. */
fun parseIgBoss(body: String, fetchedAtMs: Long): IgBossResult {
    val o = try {
        JSONObject(body)
    } catch (e: JSONException) {
        throw IgException("The Instagram server sent a reply that is not valid JSON")
    }
    val shortcode = o.optString("shortcode")
    val until = if (o.isNull("untilTs") || !o.has("untilTs")) null else o.optLong("untilTs")
    val hidden = o.optBoolean("excludeHidden")
    if (o.optBoolean("empty")) {
        return IgBossResult(shortcode, true, 0, 0, 0, 0.0, 0, 0, emptyList(), null, emptyList(), emptyMap(), emptyMap(), null, null, until, hidden, fetchedAtMs, body)
    }
    try {
        val arr = o.getJSONArray("items")
        val items = List(arr.length()) { i ->
            val it = arr.getJSONObject(i)
            val look = it.optJSONObject("lookalike")
            IgItem(
                it.getString("label"), it.getLong("count"), it.getDouble("share"),
                it.optLong("strictCount"), it.optDouble("strictShare"),
                look?.optString("tag")?.takeIf { t -> t.isNotEmpty() }, look?.optLong("count") ?: 0L,
            )
        }
        val lead = o.optJSONObject("lead")?.let { "${it.getString("first")} leads ${it.getString("second")} by ${"%,d".format(it.getLong("by"))}" }
        val un = o.optJSONArray("topUntracked") ?: JSONArray()
        val bd = o.optJSONObject("breakdown")
        val ck = o.optJSONObject("checks")
        val fresh = o.optJSONObject("freshness")
        return IgBossResult(
            shortcode, false, o.getLong("total"), o.getLong("tracked"), o.getLong("uncounted"), o.getDouble("uncountedPct"),
            o.optLong("trackedStrict"), o.optLong("doubleCounted"), items, lead,
            List(un.length()) { i -> un.getJSONObject(i).let { it.getString("tag") to it.getLong("count") } },
            bd?.keys()?.asSequence()?.associateWith { k -> bd.getLong(k) } ?: emptyMap(),
            ck?.keys()?.asSequence()?.associateWith { k -> ck.getBoolean(k) } ?: emptyMap(),
            fresh?.optString("lastCommentAtIst")?.takeIf { it.isNotEmpty() && it != "null" },
            fresh?.takeIf { !it.isNull("reported") }?.optLong("reported"),
            until, hidden, fetchedAtMs, body,
        )
    } catch (e: JSONException) {
        throw IgException("The Instagram server's reply was missing something: ${e.message}")
    }
}

/** One watched post as the server reports it. */
data class IgWatch(val shortcode: String, val status: String, val lastPoll: String?, val stored: Long) {
    /** The cookie stopped working: polling has stopped and a new sessionid is needed on the server. */
    val sessionExpired: Boolean get() = status.startsWith("session expired")
    val blocked: Boolean get() = status.startsWith("blocked")
}

private val POST_LINK = Regex("""instagram\.com/(?:[^/?#\s]+/)?(?:p|reel|reels|tv)/([A-Za-z0-9_-]+)""", RegexOption.IGNORE_CASE)
private val BARE_SHORTCODE = Regex("""^[A-Za-z0-9_-]{6,20}$""")

/** The shortcode from an Instagram post or reel link (or a bare shortcode). Null when it is neither. */
fun shortcodeFrom(link: String): String? {
    val s = link.trim()
    POST_LINK.find(s)?.let { return it.groupValues[1] }
    return s.takeIf { BARE_SHORTCODE.matches(it) && "." !in it }
}

interface IgBossApi {
    val isConfigured: Boolean

    /** The Boss Meter counts for [shortcode]. [untilTs] (unix seconds) cuts the count off at that moment; null counts everything fetched. */
    suspend fun boss(shortcode: String, tags: List<String>, untilTs: Long?, excludeHidden: Boolean): IgBossResult

    /** Asks the server to start watching a post (it backfills, then keeps up). Free: no X calls. */
    suspend fun watch(url: String)

    /** The watcher's state for [shortcode], or null when the server is not watching it. */
    suspend fun status(shortcode: String): IgWatch?

    /**
     * Manual mode: tells the server to fetch every comment since the last stored one, and waits until it is done (or
     * [maxWaitMs] passes). A server that polls continuously has nothing to catch up, so that case returns at once.
     */
    suspend fun catchUp(shortcode: String, maxWaitMs: Long = 15 * 60_000L)

    /**
     * Comments (all of them, replies too) that mention each contestant by hashtag, @handle or plain text, between
     * [sinceTs] and [untilTs] (unix seconds). Contestant name to number of comments. Free: the server counts.
     */
    suspend fun mentions(shortcode: String, keys: List<com.clipcut.domain.history.MentionKeys>, sinceTs: Long, untilTs: Long?): Map<String, Long>
}

/** Reads the server's `/mentions` reply: contestant name to the number of comments that mention them. */
fun parseMentions(body: String): Map<String, Long> {
    try {
        val arr = JSONObject(body).getJSONArray("contestants")
        return (0 until arr.length()).associate { i -> arr.getJSONObject(i).let { it.getString("name") to it.getLong("comments") } }
    } catch (e: JSONException) {
        throw IgException("The Instagram server's mentions reply was not what was expected")
    }
}

class RealIgBossApi(
    private val client: OkHttpClient,
    private val baseUrl: String,
    private val apiKey: String,
    private val nowMs: () -> Long = System::currentTimeMillis,
) : IgBossApi {
    override val isConfigured: Boolean get() = baseUrl.isNotBlank() && apiKey.isNotBlank()

    private fun request(path: String, build: (okhttp3.HttpUrl.Builder) -> Unit = {}): Request.Builder {
        if (!isConfigured) throw IgException("Instagram is not set up. Add IG_BASE_URL and IG_API_KEY to local.properties and rebuild.")
        val url = "${baseUrl.trimEnd('/')}/$path".toHttpUrlOrNull()?.newBuilder()?.also(build)?.build()
            ?: throw IgException("IG_BASE_URL is not a valid address.")
        return Request.Builder().url(url).header("X-API-Key", apiKey)
    }

    private fun send(req: Request): String = try {
        client.newCall(req).execute().use { r ->
            val text = r.body?.string().orEmpty()
            if (!r.isSuccessful) {
                val why = if (r.code == 401) "the API key was refused" else runCatching { JSONObject(text).optString("detail") }.getOrNull().orEmpty().ifEmpty { "HTTP ${r.code}" }
                throw IgException("The Instagram server said: $why", r.code)
            }
            text
        }
    } catch (e: java.io.IOException) {
        throw IgException("Could not reach the Instagram server: ${e.message}")
    }

    override suspend fun boss(shortcode: String, tags: List<String>, untilTs: Long?, excludeHidden: Boolean): IgBossResult = withContext(Dispatchers.IO) {
        val req = request("bossmeter/$shortcode") { b ->
            b.addQueryParameter("tags", tags.joinToString(","))
            if (untilTs != null) b.addQueryParameter("until_ts", untilTs.toString())
            if (excludeHidden) b.addQueryParameter("exclude_hidden", "true")
        }.get().build()
        parseIgBoss(send(req), nowMs())
    }

    override suspend fun watch(url: String): Unit = withContext(Dispatchers.IO) {
        val body = JSONObject().put("url", url).toString().toRequestBody("application/json".toMediaType())
        send(request("watch").post(body).build())
        Unit
    }

    override suspend fun mentions(shortcode: String, keys: List<com.clipcut.domain.history.MentionKeys>, sinceTs: Long, untilTs: Long?): Map<String, Long> =
        withContext(Dispatchers.IO) {
            val body = JSONObject().put(
                "contestants",
                JSONArray(keys.map { JSONObject().put("name", it.name).put("strong", JSONArray(it.strong)).put("single", JSONArray(it.single)) }),
            ).toString().toRequestBody("application/json".toMediaType())
            val req = request("mentions/$shortcode") { b ->
                b.addQueryParameter("since_ts", sinceTs.toString())
                if (untilTs != null) b.addQueryParameter("until_ts", untilTs.toString())
            }.post(body).build()
            parseMentions(send(req))
        }

    override suspend fun catchUp(shortcode: String, maxWaitMs: Long) {
        withContext(Dispatchers.IO) {
            try {
                send(request("watch/$shortcode/catchup").post(ByteArray(0).toRequestBody(null)).build())
            } catch (e: IgException) {
                if (e.httpCode == 409) return@withContext // continuous mode: always up to date
                throw e
            }
        }
        val deadline = nowMs() + maxWaitMs
        while (nowMs() < deadline) {
            kotlinx.coroutines.delay(3_000)
            val w = status(shortcode) ?: return
            if (!w.status.startsWith("catching up")) return
        }
    }

    override suspend fun status(shortcode: String): IgWatch? = withContext(Dispatchers.IO) {
        val arr = try {
            JSONArray(send(request("watch").get().build()))
        } catch (e: JSONException) {
            throw IgException("The Instagram server sent a reply that is not valid JSON")
        }
        (0 until arr.length()).map { arr.getJSONObject(it) }.firstOrNull { it.optString("shortcode") == shortcode }?.let {
            IgWatch(shortcode, it.optString("status"), it.optString("last_poll").takeIf { s -> s.isNotEmpty() && s != "null" }, it.optLong("stored"))
        }
    }
}
