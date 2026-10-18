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
 * How much a Deep look may read. Measured on 2026-10-18: about $0.0058 per post read (posts plus Grok reading them) and
 * about 9 posts per search, plus a fixed ~$0.01. Grok decides the exact number of posts itself; [searches] caps its
 * search rounds and the instructions ask for about [posts], so the real cost lands near [budgetUsd], sometimes a little
 * over or under. xAI's own figure is shown after every run.
 */
enum class DeepLookSize(val label: String, val budgetUsd: Double, val posts: Int, val searches: Int, val voices: Int) {
    STANDARD("Standard", 0.30, 50, 6, 8),
    MAX("Max", 0.50, 85, 10, 10),
    FULL("Full", 1.00, 170, 18, 10),
}

/**
 * What Grok found reading a sample of a tagline's posts on X. A sample, never exact figures: those come from the counts.
 * [starters] are the earliest accounts it found posting the tagline (who started it); [rivals] other fandoms' taglines
 * pushed at the same time; [pairedHashtags] the hashtags used alongside it.
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
    val starters: List<Voice> = emptyList(),
    val rivals: List<String> = emptyList(),
    val pairedHashtags: List<String> = emptyList(),
    /** Which size was asked for ("Standard", "Max", "Full"); blank for looks from before sizes existed. */
    val size: String = "",
)

/** The smallest size's budget: what the cost box and the cap assume when no size is given. */
val DEEP_LOOK_ESTIMATE_USD = DeepLookSize.STANDARD.budgetUsd

/** No more Deep looks once this much has been spent today (phone's date), by xAI's own reported cost. */
const val DEEP_LOOK_DAILY_CAP_USD = 3.00

/** Today's Deep look spend, kept so the cap holds across app restarts. */
interface DeepLookLedger {
    fun spentOn(day: LocalDate): Double
    fun add(day: LocalDate, usd: Double)
}

private val HANDLE = Regex("""^@?([A-Za-z0-9_]{1,15})$""")
private val HASHTAG = Regex("""^#?([\p{L}\p{N}_]{2,60})$""")
private val POST_URL = Regex("""^https?://(?:www\.)?(?:x|twitter)\.com/([A-Za-z0-9_]{1,15})/status/(\d{5,25})(?:[/?#].*)?$""", RegexOption.IGNORE_CASE)

/**
 * Runs a Deep look through xAI's X search (xAI only; there is no OpenAI fallback for this). Read only: it never posts,
 * and the X API keys are not involved. Refuses before spending anything when today's spend plus the chosen size's
 * budget would pass the cap.
 */
class DeepLookService(
    private val llm: LlmClient,
    private val ledger: DeepLookLedger,
    private val zone: () -> ZoneId = ZoneId::systemDefault,
    private val nowMs: () -> Long = System::currentTimeMillis,
) {
    fun today(): LocalDate = Instant.ofEpochMilli(nowMs()).atZone(zone()).toLocalDate()

    fun spentToday(): Double = ledger.spentOn(today())

    /** Null when a run of [size] may go ahead, else why not. */
    fun blockedReason(size: DeepLookSize = DeepLookSize.STANDARD): String? {
        val spent = spentToday()
        return if (spent + size.budgetUsd > DEEP_LOOK_DAILY_CAP_USD) {
            "A ${size.label} Deep look (about $" + "%.2f".format(size.budgetUsd) + ") would pass today's limit ($" + "%.2f".format(spent) +
                " of $" + "%.2f".format(DEEP_LOOK_DAILY_CAP_USD) + " spent). Try a smaller size, or tomorrow."
        } else {
            null
        }
    }

    /**
     * [previous] are earlier looks at the same tagline, newest first. The newest three are shown to Grok so it reads fresh
     * posts and says what changed, which makes each run add to the last instead of repeating it.
     */
    suspend fun look(tagline: String, report: CountReport, previous: List<DeepLook> = emptyList(), size: DeepLookSize = DeepLookSize.STANDARD): DeepLook {
        blockedReason(size)?.let { throw LlmException(it) }
        val reply = llm.respond(
            LlmTask.ANALYSIS,
            system = deepLookSystem(size),
            user = deepLookUserText(tagline, previous),
            schema = DEEP_LOOK_SCHEMA,
            xSearch = searchWindow(report, size),
        )
        // Only xAI's own figure includes the search fees (token prices alone miss them), so without it the budget counts.
        val cost = reply.usage?.reportedCostUsd
        ledger.add(today(), cost ?: size.budgetUsd)
        return parseDeepLook(reply.text, tagline, size.voices).copy(
            costUsd = cost, label = reply.label, atMs = nowMs(), reportAtMs = report.fetchedAtMs, size = size.label,
        )
    }
}

/** The counted window as X search dates (UTC days), with the size's search rounds. The end day is always included. */
fun searchWindow(report: CountReport, size: DeepLookSize = DeepLookSize.STANDARD): XSearch {
    val from = Instant.ofEpochMilli(report.startMs).atZone(ZoneOffset.UTC).toLocalDate()
    val to = Instant.ofEpochMilli(report.endMs).atZone(ZoneOffset.UTC).toLocalDate().plusDays(1)
    return XSearch(from.toString(), to.toString(), maxTurns = size.searches)
}

private fun voicesFrom(a: JSONArray?, why: String, max: Int): List<Voice> = a?.let {
    (0 until it.length()).mapNotNull { i ->
        val v = it.optJSONObject(i) ?: return@mapNotNull null
        val h = HANDLE.matchEntire(v.optString("handle").trim())?.groupValues?.get(1) ?: return@mapNotNull null
        Voice("@$h", v.optString(why).trim().take(200))
    }
}.orEmpty().distinctBy { it.handle.lowercase() }.take(max)

/**
 * The model's JSON as a [DeepLook]. Handles, hashtags and links are checked: anything that is not a real-looking X
 * handle, hashtag or x.com post link is dropped rather than shown. Themes are required; everything else may be empty.
 */
fun parseDeepLook(json: String, tagline: String, maxVoices: Int = 8): DeepLook {
    val o = try {
        JSONObject(json.trim().removePrefix("```json").removePrefix("```").removeSuffix("```").trim())
    } catch (e: JSONException) {
        throw LlmException("Grok did not return the Deep look as JSON")
    }
    fun strings(k: String) = o.optJSONArray(k)?.let { a -> (0 until a.length()).map { a.optString(it).trim() }.filter { it.isNotEmpty() } }.orEmpty()
    val themes = strings("themes").take(5)
    if (themes.isEmpty()) throw LlmException("Grok found nothing to report for this tagline")
    val posts = strings("example_posts").mapNotNull { url -> POST_URL.matchEntire(url)?.let { "https://x.com/${it.groupValues[1]}/status/${it.groupValues[2]}" } }.distinct().take(3)
    return DeepLook(
        tagline, o.optInt("posts_read").coerceAtLeast(0), themes, voicesFrom(o.optJSONArray("top_voices"), "why", maxVoices), posts,
        costUsd = null, label = "", atMs = 0L,
        changes = o.optString("changes").trim().take(300),
        starters = voicesFrom(o.optJSONArray("starters"), "when", 3),
        rivals = strings("rival_taglines").map { it.take(120) }.filter { !it.equals(tagline, ignoreCase = true) }.distinctBy { it.lowercase() }.take(5),
        pairedHashtags = strings("paired_hashtags").mapNotNull { HASHTAG.matchEntire(it)?.groupValues?.get(1)?.let { h -> "#$h" } }.distinctBy { it.lowercase() }.take(8),
    )
}

/** The tagline, plus what the earlier looks found (newest three), so Grok can read fresh posts and report changes. */
fun deepLookUserText(tagline: String, previous: List<DeepLook>): String = buildString {
    append("Tagline: \"").append(tagline).append('"')
    val earlier = previous.take(3)
    if (earlier.isEmpty()) return@buildString
    append("\n\nEARLIER LOOKS (newest first). Prefer posts you have not seen before, and say in \"changes\" what is new.\n")
    earlier.forEach { l ->
        append("\n- ").append(Instant.ofEpochMilli(l.atMs)).append(", ").append(l.postsRead).append(" posts read")
        append("\n  themes: ").append(l.themes.joinToString(" | "))
        if (l.voices.isNotEmpty()) append("\n  top voices: ").append(l.voices.joinToString(", ") { it.handle })
        if (l.starters.isNotEmpty()) append("\n  started by: ").append(l.starters.joinToString(", ") { it.handle })
        if (l.examplePosts.isNotEmpty()) append("\n  already read: ").append(l.examplePosts.joinToString(" "))
    }
}

/** The instructions for one size: how many searches and posts, and how much to report. */
fun deepLookSystem(size: DeepLookSize): String = """
You look into one fan tagline campaign around the Indian reality show Bigg Boss 20 on X.
Use up to ${size.searches} searches for the exact tagline: some for Top posts, some for Latest, and one aimed at the
EARLIEST posts in the period. Read about ${size.posts} posts in total, then answer. Do not stop early if more posts are
available; do not read far more than that.

Return JSON:
- posts_read: how many posts you actually read.
- themes: 3 to 5 short themes (each under 80 characters): what the posts are saying.
- top_voices: up to ${size.voices} accounts whose posts in your sample had the most engagement, each with a short reason
  that quotes the engagement you saw (for example "1,646 likes on the launch post").
- starters: up to 3 of the earliest accounts you saw posting the tagline, each with "when" (time as shown, short).
- rival_taglines: other fandoms' taglines or hashtags you saw pushed in the same period (may be empty).
- paired_hashtags: hashtags most often used together with this tagline (may be empty).
- example_posts: 3 post URLs (https://x.com/<user>/status/<id>) that you actually read.
- changes: if EARLIER LOOKS are given, one or two short sentences on what is new (new voices, themes rising or
  fading); otherwise an empty string.

Never invent handles, URLs, times or numbers. If you saw fewer, return fewer.
""".trim()

private fun voiceSchema(why: String) = JSONObject().put("type", "array").put(
    "items",
    JSONObject().put("type", "object")
        .put("properties", JSONObject().put("handle", JSONObject().put("type", "string")).put(why, JSONObject().put("type", "string")))
        .put("required", JSONArray().put("handle").put(why))
        .put("additionalProperties", false),
)

private fun stringArray() = JSONObject().put("type", "array").put("items", JSONObject().put("type", "string"))

private val DEEP_LOOK_SCHEMA: JSONObject = JSONObject()
    .put("type", "object")
    .put(
        "properties",
        JSONObject()
            .put("posts_read", JSONObject().put("type", "integer"))
            .put("themes", stringArray())
            .put("top_voices", voiceSchema("why"))
            .put("starters", voiceSchema("when"))
            .put("rival_taglines", stringArray())
            .put("paired_hashtags", stringArray())
            .put("example_posts", stringArray())
            .put("changes", JSONObject().put("type", "string")),
    )
    .put(
        "required",
        JSONArray().put("posts_read").put("themes").put("top_voices").put("starters").put("rival_taglines").put("paired_hashtags").put("example_posts").put("changes"),
    )
    .put("additionalProperties", false)
