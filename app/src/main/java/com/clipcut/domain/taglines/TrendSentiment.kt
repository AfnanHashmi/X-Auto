package com.clipcut.domain.taglines

import com.clipcut.domain.llm.LlmClient
import com.clipcut.domain.llm.LlmException
import com.clipcut.domain.llm.LlmTask
import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject

/** Positive, negative and neutral posts, overall and toward each contestant the posts are about. */
data class Sentiment(
    val positive: Int,
    val negative: Int,
    val neutral: Int,
    /** Contestant to (positive, negative, neutral). */
    val byContestant: Map<String, Triple<Int, Int, Int>>,
    /** How many sampled posts were judged. */
    val judged: Int,
    val label: String,
    val costUsd: Double?,
) {
    val total: Int get() = positive + negative + neutral
}

/** One post's verdict: who it is about (a roster name, or null for nobody in particular) and the mood toward them. */
data class Verdict(val id: String, val about: String?, val mood: Mood)

enum class Mood { POSITIVE, NEGATIVE, NEUTRAL }

/** At most this many posts are sent to the model, newest first; retweets reuse the verdict of the post they repeat. */
const val SENTIMENT_MAX_POSTS = 150

private const val SENTIMENT_SYSTEM = """
You read short fan posts from X about a Bigg Boss 20 (Indian reality show) fan tagline. For each post, decide:
- about: which ONE contestant from the CONTESTANTS list the post is mainly about (exact spelling from the list), or "none".
- mood: the post's attitude toward that contestant: positive (support, praise, love), negative (attack, mockery, blame,
  hate), or neutral (news, questions, only a hashtag or tagline with no feeling). Sarcasm that mocks is negative.
Posts may be Hindi, English or mixed. Judge only what the post says; never guess from who wrote it. Return one entry for
every post id you were given.
"""

private val SENTIMENT_SCHEMA: JSONObject = JSONObject().put("type", "object").put(
    "properties",
    JSONObject().put(
        "verdicts",
        JSONObject().put("type", "array").put(
            "items",
            JSONObject().put("type", "object")
                .put(
                    "properties",
                    JSONObject().put("id", JSONObject().put("type", "string")).put("about", JSONObject().put("type", "string"))
                        .put("mood", JSONObject().put("type", "string").put("enum", JSONArray().put("positive").put("negative").put("neutral"))),
                )
                .put("required", JSONArray().put("id").put("about").put("mood"))
                .put("additionalProperties", false),
        ),
    ),
).put("required", JSONArray().put("verdicts")).put("additionalProperties", false)

/** The model's JSON as verdicts. Ids that were not sent and contestants that are not on the list are dropped. */
fun parseVerdicts(json: String, sentIds: Set<String>, contestants: List<String>): List<Verdict> {
    val arr = try {
        JSONObject(json.trim().removePrefix("```json").removePrefix("```").removeSuffix("```").trim()).getJSONArray("verdicts")
    } catch (e: JSONException) {
        throw LlmException("The model did not return the sentiment as JSON")
    }
    val byLower = contestants.associateBy { it.lowercase() }
    return (0 until arr.length()).mapNotNull { i ->
        val o = arr.optJSONObject(i) ?: return@mapNotNull null
        val id = o.optString("id").takeIf { it in sentIds } ?: return@mapNotNull null
        val mood = when (o.optString("mood").lowercase()) { "positive" -> Mood.POSITIVE; "negative" -> Mood.NEGATIVE; "neutral" -> Mood.NEUTRAL; else -> return@mapNotNull null }
        Verdict(id, byLower[o.optString("about").trim().lowercase()], mood)
    }.distinctBy { it.id }
}

/** Adds the verdicts up. A retweet counts with the verdict of the post it repeats when that post was judged. */
fun tallySentiment(posts: List<TcPost>, verdicts: List<Verdict>, label: String, costUsd: Double?): Sentiment {
    val byId = verdicts.associateBy { it.id }
    var pos = 0; var neg = 0; var neu = 0
    val per = HashMap<String, IntArray>()
    for (p in posts) {
        val v = byId[p.id] ?: p.originalId?.let { byId[it] } ?: continue
        when (v.mood) { Mood.POSITIVE -> pos++; Mood.NEGATIVE -> neg++; Mood.NEUTRAL -> neu++ }
        v.about?.let { a -> per.getOrPut(a) { IntArray(3) }[v.mood.ordinal]++ }
    }
    return Sentiment(pos, neg, neu, per.mapValues { Triple(it.value[0], it.value[1], it.value[2]) }, pos + neg + neu, label, costUsd)
}

/** Judges the sample with the app's text model (one call, a few cents). Posts the model skipped are simply not counted. */
suspend fun judgeSentiment(llm: LlmClient, posts: List<TcPost>, contestants: List<String>): Sentiment {
    val sample = posts.filter { it.type != PostType.RETWEET }.sortedByDescending { it.createdMs }.take(SENTIMENT_MAX_POSTS)
    val user = buildString {
        append("CONTESTANTS: ").append(contestants.joinToString(" | ")).append("\n\nPOSTS:\n")
        sample.forEach { append(it.id).append(" :: ").append(it.text.replace(Regex("\\s+"), " ").take(240)).append('\n') }
    }
    val reply = llm.respond(LlmTask.ANALYSIS, SENTIMENT_SYSTEM.trim(), user, SENTIMENT_SCHEMA)
    val verdicts = parseVerdicts(reply.text, sample.map { it.id }.toSet(), contestants)
    if (verdicts.isEmpty()) throw LlmException("The model judged none of the posts")
    return tallySentiment(posts, verdicts, reply.label, reply.costUsd)
}
