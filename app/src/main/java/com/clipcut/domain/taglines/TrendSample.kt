package com.clipcut.domain.taglines

import org.json.JSONException
import org.json.JSONObject
import java.time.Instant

enum class PostType { ORIGINAL, RETWEET, REPLY, QUOTE }

/** One account as X describes it next to a post. */
data class TcAuthor(
    val id: String,
    val username: String,
    val name: String,
    val createdMs: Long?,
    val followers: Long,
    val following: Long,
    val tweets: Long,
    val verified: Boolean,
    val defaultAvatar: Boolean,
    val bio: String,
    val location: String,
)

/** One sampled post. For a retweet, [original] is who wrote the post that was retweeted. */
data class TcPost(
    val id: String,
    val authorId: String,
    val createdMs: Long,
    val text: String,
    val lang: String,
    val type: PostType,
    val likes: Long,
    val retweets: Long,
    val hashtags: List<String>,
    val mentions: List<String>,
    val hasMedia: Boolean,
    val original: String? = null,
    val originalId: String? = null,
)

/** One page of a recent-search reply. */
data class SearchPage(val posts: List<TcPost>, val authors: Map<String, TcAuthor>, val nextToken: String?)

/** A reply from X that cannot be trusted. */
class TrendParseException(message: String) : Exception(message)

/** The fields and expansions asked for on every search call (one request returns the posts and their authors). */
const val SEARCH_TWEET_FIELDS = "created_at,public_metrics,lang,entities,referenced_tweets,author_id,attachments"
const val SEARCH_EXPANSIONS = "author_id,referenced_tweets.id.author_id"
const val SEARCH_USER_FIELDS = "created_at,public_metrics,verified,description,location,profile_image_url,name,username"

private fun parseUser(o: JSONObject): TcAuthor {
    val m = o.optJSONObject("public_metrics")
    val pic = o.optString("profile_image_url")
    return TcAuthor(
        id = o.getString("id"), username = o.optString("username"), name = o.optString("name"),
        createdMs = o.optString("created_at").takeIf { it.isNotEmpty() }?.let { runCatching { Instant.parse(it).toEpochMilli() }.getOrNull() },
        followers = m?.optLong("followers_count") ?: 0, following = m?.optLong("following_count") ?: 0, tweets = m?.optLong("tweet_count") ?: 0,
        verified = o.optBoolean("verified"), defaultAvatar = pic.contains("default_profile"),
        bio = o.optString("description"), location = o.optString("location"),
    )
}

/** Reads one `GET /2/tweets/search/recent` reply with author expansions. Strict about shape; a page with no `data` is a valid empty page. */
fun parseSearch(body: String): SearchPage {
    val root = try { JSONObject(body) } catch (e: JSONException) { throw TrendParseException("X sent a reply that is not valid JSON") }
    val inc = root.optJSONObject("includes")
    val authors = HashMap<String, TcAuthor>()
    inc?.optJSONArray("users")?.let { a -> for (i in 0 until a.length()) runCatching { parseUser(a.getJSONObject(i)) }.getOrNull()?.let { authors[it.id] = it } }
    val refs = HashMap<String, String>() // referenced post id -> its author id
    inc?.optJSONArray("tweets")?.let { a ->
        for (i in 0 until a.length()) a.optJSONObject(i)?.let { t -> if (t.has("id") && t.has("author_id")) refs[t.getString("id")] = t.getString("author_id") }
    }
    val data = root.optJSONArray("data")
    val posts = List(data?.length() ?: 0) { i ->
        val t = data!!.optJSONObject(i) ?: throw TrendParseException("A post in the reply is not an object")
        val ref = t.optJSONArray("referenced_tweets")?.optJSONObject(0)
        val type = when (ref?.optString("type")) { "retweeted" -> PostType.RETWEET; "replied_to" -> PostType.REPLY; "quoted" -> PostType.QUOTE; else -> PostType.ORIGINAL }
        val ents = t.optJSONObject("entities")
        fun list(k: String, f: String) = ents?.optJSONArray(k)?.let { a -> (0 until a.length()).mapNotNull { a.optJSONObject(it)?.optString(f)?.takeIf { s -> s.isNotEmpty() } } }.orEmpty()
        val pm = t.optJSONObject("public_metrics")
        val originalId = ref?.optString("id")?.takeIf { it.isNotEmpty() }
        TcPost(
            id = t.getString("id"), authorId = t.optString("author_id"),
            createdMs = runCatching { Instant.parse(t.getString("created_at")).toEpochMilli() }.getOrElse { throw TrendParseException("A post has no valid time") },
            text = t.optString("text"), lang = t.optString("lang"), type = type,
            likes = pm?.optLong("like_count") ?: 0, retweets = pm?.optLong("retweet_count") ?: 0,
            hashtags = list("hashtags", "tag").map { it.lowercase() }, mentions = list("mentions", "username").map { it.lowercase() },
            hasMedia = t.optJSONObject("attachments")?.has("media_keys") == true,
            original = if (type == PostType.RETWEET) originalId?.let { refs[it] } else null, originalId = originalId,
        )
    }
    return SearchPage(posts, authors, root.optJSONObject("meta")?.optString("next_token")?.takeIf { it.isNotEmpty() })
}

// ---- what the sample shows ---------------------------------------------------------------------------------------------------

private const val DAY_MS = 24 * HOUR_MS

private val URL = Regex("""https?://\S+""")
private val MENTION = Regex("""@\w+""")
private val NOT_WORD = Regex("""[^\p{L}\p{N}\s]""")
private val FAN_CLUB = Regex("""\b(fc|fanclub|fan club|fan page|fanpage|army|fam|fans?|stan|stans|updates)\b""", RegexOption.IGNORE_CASE)
private val INDIA = Regex("""india|mumbai|delhi|bengal|kolkata|chennai|hyderabad|pune|jaipur|lucknow|bihar|patna|gujarat|ahmedabad|punjab|bhopal|indore|noida|gurgaon|haryana|assam|odisha|kerala|\bup\b|\bmp\b""", RegexOption.IGNORE_CASE)

/** The text of a post with links, @mentions, the tagline and punctuation taken out, lower-cased, spaces collapsed. */
fun normalizedText(text: String, tagline: String): String =
    text.replace(URL, " ").replace(MENTION, " ").replace(tagline, " ", ignoreCase = true)
        .replace(Regex("#\\w+"), " ").replace(NOT_WORD, " ").replace(Regex("\\s+"), " ").trim().lowercase()

/** Why an account looks automated or throwaway. Two or more flags make a post "bot-like". Never proof. */
data class AccountFlags(val newAccount: Boolean, val lowReach: Boolean, val extremeRate: Boolean) {
    val count: Int get() = listOf(newAccount, lowReach, extremeRate).count { it }
    val botLike: Boolean get() = count >= 2
}

fun accountFlags(a: TcAuthor?, postMs: Long): AccountFlags {
    if (a == null) return AccountFlags(false, false, false)
    val created = a.createdMs
    val ageDays = created?.let { ((postMs - it).toDouble() / DAY_MS).coerceAtLeast(1.0) }
    return AccountFlags(
        newAccount = created != null && postMs - created in 0..30 * DAY_MS,
        lowReach = a.followers < 10 || a.defaultAvatar,
        extremeRate = ageDays != null && a.tweets / ageDays > 200.0,
    )
}

/** Statistics over the posts read. Estimates: the sample is a slice of the trend, never all of it. */
data class SampleStats(
    val sample: Int,
    val originals: Int,
    val naturalPct: Int,
    val botLikePct: Int,
    val copyPastePct: Int,
    val newAccountPct: Int,
    val lowReachPct: Int,
    val fanClubPct: Int,
    val top10AuthorsPct: Int,
    val medianLikes: Long,
    val zeroLikePct: Int,
    val organizers: List<Pair<String, Int>>,
    val pairedHashtags: List<Pair<String, Int>>,
    val topMentions: List<Pair<String, Int>>,
    val languages: List<Pair<String, Int>>,
    val indiaPctOfLocated: Int?,
    val mediaPct: Int,
)

private fun <T> top(items: List<T>, n: Int): List<Pair<T, Int>> =
    items.groupingBy { it }.eachCount().entries.sortedByDescending { it.value }.take(n).map { it.key to it.value }

/**
 * Computes [SampleStats] from the posts read for [tagline]. Rules:
 * - copy-paste: a post (not a retweet) whose cleaned text of 8+ characters was posted by 3+ different accounts. Posts that
 *   are only the tagline are left out of this, since saying the tagline is the whole campaign;
 * - bot-like: the post's account has 2 or more of: made within 30 days of the post, under 10 followers or default picture,
 *   over 200 posts a day on average;
 * - natural: not copy-paste and not bot-like;
 * - organizers: the accounts whose own posts were retweeted most in the sample.
 * Null for an empty sample.
 */
fun sampleStats(posts: List<TcPost>, authors: Map<String, TcAuthor>, tagline: String, roster: List<String> = emptyList()): SampleStats? {
    if (posts.isEmpty()) return null
    val n = posts.size
    val nonRt = posts.filter { it.type != PostType.RETWEET }
    val cleaned = nonRt.associateWith { normalizedText(it.text, tagline) }
    val copied = cleaned.filter { it.value.length >= 8 }.entries.groupBy({ it.value }, { it.key })
        .filter { (_, v) -> v.map { it.authorId }.distinct().size >= 3 }.values.flatten().toSet()
    val flags = posts.associateWith { accountFlags(authors[it.authorId], it.createdMs) }
    val botLike = posts.filter { flags.getValue(it).botLike }.toSet()
    val organized = copied + botLike
    val fanWords = roster.map { it.lowercase() }.filter { it.length >= 4 }
    fun fan(a: TcAuthor?) = a != null && (FAN_CLUB.containsMatchIn(a.name) || FAN_CLUB.containsMatchIn(a.bio) ||
        fanWords.any { w -> a.name.lowercase().contains(w) || a.bio.lowercase().contains(w) || a.username.lowercase().contains(w.replace(" ", "")) })
    val likes = nonRt.map { it.likes }.sorted()
    val located = posts.mapNotNull { authors[it.authorId]?.location?.takeIf { l -> l.isNotBlank() } }
    val tag = tagline.lowercase().replace(Regex("[^\\p{L}\\p{N}]"), "")
    return SampleStats(
        sample = n, originals = nonRt.size,
        naturalPct = pct((n - organized.size).toLong(), n.toLong()),
        botLikePct = pct(botLike.size.toLong(), n.toLong()),
        copyPastePct = pct(copied.size.toLong(), cleaned.count { it.value.length >= 8 }.toLong()),
        newAccountPct = pct(posts.count { flags.getValue(it).newAccount }.toLong(), n.toLong()),
        lowReachPct = pct(posts.count { flags.getValue(it).lowReach }.toLong(), n.toLong()),
        fanClubPct = pct(posts.count { fan(authors[it.authorId]) }.toLong(), n.toLong()),
        top10AuthorsPct = pct(top(posts.map { it.authorId }, 10).sumOf { it.second }.toLong(), n.toLong()),
        medianLikes = likes.getOrElse(likes.size / 2) { 0L },
        zeroLikePct = pct(nonRt.count { it.likes == 0L }.toLong(), nonRt.size.toLong()),
        organizers = top(posts.filter { it.type == PostType.RETWEET }.mapNotNull { r -> r.original?.let { authors[it]?.username?.let { u -> "@$u" } } }, 5),
        pairedHashtags = top(posts.flatMap { it.hashtags }.filter { it.replace("_", "") != tag }, 5).map { "#${it.first}" to it.second },
        topMentions = top(posts.flatMap { it.mentions }, 5).map { "@${it.first}" to it.second },
        languages = top(posts.map { it.lang.ifBlank { "und" } }, 3),
        indiaPctOfLocated = located.takeIf { it.size >= 10 }?.let { l -> pct(l.count { INDIA.containsMatchIn(it) }.toLong(), l.size.toLong()) },
        mediaPct = pct(posts.count { it.hasMedia }.toLong(), n.toLong()),
    )
}
