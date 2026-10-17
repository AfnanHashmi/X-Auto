package com.clipcut.domain.taglines

import com.clipcut.domain.llm.LlmClient
import com.clipcut.domain.llm.LlmException
import com.clipcut.domain.llm.LlmTask
import com.clipcut.domain.llm.XSearch
import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZoneOffset

/** One account Grok saw driving the tagline, and why. */
data class Voice(val handle: String, val why: String)

/**
 * What Grok found reading a sample of a tagline's posts on X: themes, the accounts with the most engagement in its
 * sample, and posts to open. A sample (often 15 to 30 posts), never exact figures: those come from the counts.
 */
data class DeepLook(
    val tagline: String,
    val postsRead: Int,
    val themes: List<String>,
    val voices: List<Voice>,
    val examplePosts: List<String>,
    val costUsd: Double?,
    val label: String,
    val atMs: Long,
    /** What is new compared with the earlier looks at this tagline (blank on the first look). */
    val changes: String = "",
    /** The count this look was taken for. */
    val reportAtMs: Long = 0L,
)

/** Measured on 2026-10-18: $0.105 for one tagline (18 posts, 2 searches) with grok-4.3. Shown before each run. */
const val DEEP_LOOK_ESTIMATE_USD = 0.12

/** No more Deep looks once this much has been spent today (phone's date), by xAI's own reported cost. */
const val DEEP_LOOK_DAILY_CAP_USD = 1.00

/** Today's Deep look spend, kept so the cap holds across app restarts. */
interface DeepLookLedger {
    fun spentOn(day: LocalDate): Double
    fun add(day: LocalDate, usd: Double)
}

private val HANDLE = Regex("""^@?([A-Za-z0-9_]{1,15})$""")
private val POST_URL = Regex("""^https?://(?:www\.)?(?:x|twitter)\.com/([A-Za-z0-9_]{1,15})/status/(\d{5,25})(?:[/?#].*)?$""", RegexOption.IGNORE_CASE)

/**
 * Runs a Deep look through xAI's X search (xAI only; there is no OpenAI fallback for this). Read only: it never posts,
 * and the X API keys are not involved. Refuses before spending anything when today's spend would pass the cap.
 */
class DeepLookService(
    private val llm: LlmClient,
    private val ledger: DeepLookLedger,
    private val zone: () -> ZoneId = ZoneId::systemDefault,
    private val nowMs: () -> Long = System::currentTimeMillis,
) {
    fun today(): LocalDate = Instant.ofEpochMilli(nowMs()).atZone(zone()).toLocalDate()

    fun spentToday(): Double = ledger.spentOn(today())

    /** Null when a run may go ahead, else why not. */
    fun blockedReason(): String? {
        val spent = spentToday()
        return if (spent + DEEP_LOOK_ESTIMATE_USD > DEEP_LOOK_DAILY_CAP_USD) {
            "Today's Deep look limit is reached ($" + "%.2f".format(spent) + " of $" + "%.2f".format(DEEP_LOOK_DAILY_CAP_USD) + "). It resets tomorrow."
        } else {
            null
        }
    }

    /**
     * [previous] are earlier looks at the same tagline, newest first. The newest three are shown to Grok so it reads fresh
     * posts and says what changed, which makes each run add to the last instead of repeating it.
     */
    suspend fun look(tagline: String, report: CountReport, previous: List<DeepLook> = emptyList()): DeepLook {
        blockedReason()?.let { throw LlmException(it) }
        val reply = llm.respond(
            LlmTask.ANALYSIS,
            system = DEEP_LOOK_SYSTEM,
            user = deepLookUserText(tagline, previous),
            schema = DEEP_LOOK_SCHEMA,
            xSearch = searchWindow(report),
        )
        // Only xAI's own figure includes the search fees (token prices alone miss them), so without it the estimate counts.
        val cost = reply.usage?.reportedCostUsd
        ledger.add(today(), cost ?: DEEP_LOOK_ESTIMATE_USD)
        return parseDeepLook(reply.text, tagline).copy(costUsd = cost, label = reply.label, atMs = nowMs(), reportAtMs = report.fetchedAtMs)
    }
}

/** The counted window as X search dates (UTC days). The end day is included whether or not xAI treats to_date as exclusive. */
fun searchWindow(report: CountReport): XSearch {
    val from = Instant.ofEpochMilli(report.startMs).atZone(ZoneOffset.UTC).toLocalDate()
    val to = Instant.ofEpochMilli(report.endMs).atZone(ZoneOffset.UTC).toLocalDate().plusDays(1)
    return XSearch(from.toString(), to.toString(), maxTurns = 2)
}

/**
 * The model's JSON as a [DeepLook]. Handles and links are checked: anything that is not a real-looking X handle or an
 * x.com post link is dropped rather than shown. Themes are required; voices and posts may be empty.
 */
fun parseDeepLook(json: String, tagline: String): DeepLook {
    val o = try {
        JSONObject(json.trim().removePrefix("```json").removePrefix("```").removeSuffix("```").trim())
    } catch (e: JSONException) {
        throw LlmException("Grok did not return the Deep look as JSON")
    }
    fun strings(k: String) = o.optJSONArray(k)?.let { a -> (0 until a.length()).map { a.optString(it).trim() }.filter { it.isNotEmpty() } }.orEmpty()
    val themes = strings("themes").take(5)
    if (themes.isEmpty()) throw LlmException("Grok found nothing to report for this tagline")
    val voices = o.optJSONArray("top_voices")?.let { a ->
        (0 until a.length()).mapNotNull { i ->
            val v = a.optJSONObject(i) ?: return@mapNotNull null
            val h = HANDLE.matchEntire(v.optString("handle").trim())?.groupValues?.get(1) ?: return@mapNotNull null
            Voice("@$h", v.optString("why").trim())
        }
    }.orEmpty().distinctBy { it.handle.lowercase() }.take(5)
    val posts = strings("example_posts").mapNotNull { url -> POST_URL.matchEntire(url)?.let { "https://x.com/${it.groupValues[1]}/status/${it.groupValues[2]}" } }.distinct().take(3)
    return DeepLook(
        tagline, o.optInt("posts_read").coerceAtLeast(0), themes, voices, posts, costUsd = null, label = "", atMs = 0L,
        changes = o.optString("changes").trim().take(300),
    )
}

/** The tagline, plus what the earlier looks found (newest three), so Grok can read fresh posts and report changes. */
fun deepLookUserText(tagline: String, previous: List<DeepLook>): String = buildString {
    append("Tagline: \"").append(tagline).append('"')
    val earlier = previous.take(3)
    if (earlier.isEmpty()) return@buildString
    append("\n\nEARLIER LOOKS (newest first). Prefer posts you have not seen before, and say in \"changes\" what is new.\n")
    earlier.forEach { l ->
        append("\n- ").append(java.time.Instant.ofEpochMilli(l.atMs)).append(", ").append(l.postsRead).append(" posts read")
        append("\n  themes: ").append(l.themes.joinToString(" | "))
        if (l.voices.isNotEmpty()) append("\n  top voices: ").append(l.voices.joinToString(", ") { it.handle })
        if (l.examplePosts.isNotEmpty()) append("\n  already read: ").append(l.examplePosts.joinToString(" "))
    }
}

private val DEEP_LOOK_SYSTEM = """
You look into one fan tagline campaign around the Indian reality show Bigg Boss 20 on X.
Search X for the exact tagline at most twice, read about 30 posts (a mix of top and latest), then answer.

Return JSON:
- posts_read: how many posts you actually read.
- themes: 3 short themes (each under 80 characters): what the posts are saying.
- top_voices: up to 5 accounts whose posts in your sample had the most engagement, each with a short reason that
  quotes the engagement you saw (for example "1,646 likes on the launch post").
- example_posts: 3 post URLs (https://x.com/<user>/status/<id>) that you actually read.
- changes: if EARLIER LOOKS are given, one or two short sentences on what is new (new voices, themes rising or
  fading); otherwise an empty string.

Never invent handles, URLs or numbers. If you saw fewer, return fewer.
""".trim()

private val DEEP_LOOK_SCHEMA: JSONObject = JSONObject()
    .put("type", "object")
    .put(
        "properties",
        JSONObject()
            .put("posts_read", JSONObject().put("type", "integer"))
            .put("themes", JSONObject().put("type", "array").put("items", JSONObject().put("type", "string")))
            .put(
                "top_voices",
                JSONObject().put("type", "array").put(
                    "items",
                    JSONObject().put("type", "object")
                        .put("properties", JSONObject().put("handle", JSONObject().put("type", "string")).put("why", JSONObject().put("type", "string")))
                        .put("required", JSONArray().put("handle").put("why"))
                        .put("additionalProperties", false),
                ),
            )
            .put("example_posts", JSONObject().put("type", "array").put("items", JSONObject().put("type", "string")))
            .put("changes", JSONObject().put("type", "string")),
    )
    .put("required", JSONArray().put("posts_read").put("themes").put("top_voices").put("example_posts").put("changes"))
    .put("additionalProperties", false)
