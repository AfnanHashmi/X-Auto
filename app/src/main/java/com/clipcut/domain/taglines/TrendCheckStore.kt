package com.clipcut.domain.taglines

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.time.LocalDate

/** A read-out together with the totals it was written from, so the next one can compare against it. */
data class ReadoutRecord(val reportAtMs: Long, val totals: Map<String, Long>, val analysis: TaglineAnalysis)

/** How many Trend checks are kept per tagline, and how many read-outs in all. Old ones are dropped first. */
const val CHECKS_KEPT_PER_TAGLINE = 30
const val READOUTS_KEPT = 30

/**
 * One tagline's trend over [startMs]..[endMs]: the exact counts (always) and, after a Full analysis, what a sample of the
 * posts showed. [readHours] are the hours whose posts were already bought, so a later run only buys the rest.
 */
data class TrendCheckResult(
    val tagline: String,
    val startMs: Long,
    val endMs: Long,
    val ended: Boolean,
    val atMs: Long,
    val shares: CountShares,
    val hourly: List<HourBucket>,
    val launch: LaunchType?,
    val waves: List<Long>,
    val stats: SampleStats? = null,
    val sentiment: Sentiment? = null,
    val postsRead: Int = 0,
    val readHours: List<Long> = emptyList(),
    /** What X charged for the posts of the latest full run, measured from the balances (null: could not be measured). */
    val costUsd: Double? = null,
) {
    val key: String get() = trendKey(tagline, startMs)
    val isFull: Boolean get() = stats != null
}

fun trendKey(tagline: String, startMs: Long): String = "${tagline.trim().lowercase()}|$startMs"

/** The posts and accounts bought for one trend, kept so a later run adds to them instead of buying them again. */
data class StoredSample(val posts: List<TcPost>, val authors: Map<String, TcAuthor>)

/**
 * Keeps Trend checks (they are paid for), the day's spend for the cap, the measured price of a post, the raw sample behind
 * each check, and the AI read-outs. Results stay tied to their trend; earlier ones remain as history.
 */
class TrendCheckStore(private val context: Context) : TrendCheckRepository {
    private val prefs = context.getSharedPreferences("trend_check", Context.MODE_PRIVATE)
    private val samples = File(context.filesDir, "trend_samples").apply { mkdirs() }

    override fun spentOn(day: LocalDate): Double = prefs.getFloat("spent_$day", 0f).toDouble()

    override fun add(day: LocalDate, usd: Double) {
        val e = prefs.edit()
        prefs.all.keys.filter { it.startsWith("spent_") && it != "spent_$day" }.forEach(e::remove)
        e.putFloat("spent_$day", (spentOn(day) + usd).toFloat()).apply()
    }

    /** X's measured price per post read (set by the first full run), or null before one has been measured. */
    override var costPerPost: Double?
        get() = prefs.getFloat("cost_per_post", -1f).takeIf { it > 0 }?.toDouble()
        set(v) { prefs.edit().apply { if (v == null) remove("cost_per_post") else putFloat("cost_per_post", v.toFloat()) }.apply() }

    // ---- checks ----------------------------------------------------------------------------------------------------

    /** Every kept check, newest first. */
    override fun all(): List<TrendCheckResult> {
        val raw = prefs.getString("checks", null) ?: return emptyList()
        return runCatching { JSONArray(raw).let { a -> List(a.length()) { decode(a.getJSONObject(it)) } } }.getOrDefault(emptyList()).sortedByDescending { it.atMs }
    }

    override fun forTagline(tagline: String): List<TrendCheckResult> = all().filter { it.tagline.equals(tagline, ignoreCase = true) }

    /** The newest check of [tagline] whose range starts at [startMs]: what a new run of the same trend adds to. */
    override fun find(tagline: String, startMs: Long): TrendCheckResult? = forTagline(tagline).firstOrNull { it.startMs == startMs }

    /** Saves [r], replacing an earlier check of the same trend, and keeps the newest per tagline. */
    override fun save(r: TrendCheckResult) {
        val kept = (listOf(r) + all().filter { it.key != r.key })
            .groupBy { it.tagline.lowercase() }
            .flatMap { (_, l) -> l.sortedByDescending { it.atMs }.take(CHECKS_KEPT_PER_TAGLINE) }
        prefs.edit().putString("checks", JSONArray(kept.map(::encode)).toString()).apply()
    }

    // ---- raw samples -----------------------------------------------------------------------------------------------

    private fun file(key: String) = File(samples, key.hashCode().toString() + ".json")

    override fun sample(key: String): StoredSample? = runCatching {
        val o = JSONObject(file(key).readText())
        val a = o.getJSONArray("authors")
        val p = o.getJSONArray("posts")
        StoredSample(
            List(p.length()) { decodePost(p.getJSONObject(it)) },
            List(a.length()) { decodeAuthor(a.getJSONObject(it)) }.associateBy { it.id },
        )
    }.getOrNull()

    override fun saveSample(key: String, s: StoredSample) {
        val o = JSONObject().put("posts", JSONArray(s.posts.map(::encodePost))).put("authors", JSONArray(s.authors.values.map(::encodeAuthor)))
        file(key).writeText(o.toString())
        // Samples older than the newest 60 are dropped; the checks keep their numbers.
        samples.listFiles()?.sortedByDescending { it.lastModified() }?.drop(60)?.forEach { it.delete() }
    }

    // ---- read-outs -------------------------------------------------------------------------------------------------

    fun readouts(): List<ReadoutRecord> {
        val raw = prefs.getString("readouts", null) ?: return emptyList()
        return runCatching { JSONArray(raw).let { a -> List(a.length()) { decodeReadout(a.getJSONObject(it)) } } }.getOrDefault(emptyList())
            .sortedByDescending { it.analysis.atMs }
    }

    fun readoutFor(reportAtMs: Long): TaglineAnalysis? = readouts().firstOrNull { it.reportAtMs == reportAtMs }?.analysis

    fun previousReadout(reportAtMs: Long): ReadoutRecord? = readouts().firstOrNull { it.reportAtMs < reportAtMs }

    fun addReadout(record: ReadoutRecord) {
        val kept = (listOf(record) + readouts()).take(READOUTS_KEPT)
        prefs.edit().putString("readouts", JSONArray(kept.map(::encodeReadout)).toString()).apply()
    }

    // ---- encoding --------------------------------------------------------------------------------------------------

    private fun pairs(l: List<Pair<String, Int>>) = JSONArray(l.map { JSONObject().put("k", it.first).put("n", it.second) })
    private fun readPairs(a: JSONArray?) = a?.let { x -> List(x.length()) { x.getJSONObject(it).let { o -> o.getString("k") to o.getInt("n") } } }.orEmpty()

    private fun encode(r: TrendCheckResult): JSONObject = JSONObject()
        .put("tagline", r.tagline).put("start", r.startMs).put("end", r.endMs).put("ended", r.ended).put("at", r.atMs)
        .put("all", r.shares.all).put("orig", r.shares.originals).put("replies", r.shares.replies).put("quotes", r.shares.quotes)
        .put("hourly", JSONArray(r.hourly.map { JSONArray().put(it.startMs).put(it.count) }))
        .put("launch", r.launch?.name ?: JSONObject.NULL).put("waves", JSONArray(r.waves))
        .put("posts", r.postsRead).put("read", JSONArray(r.readHours)).put("cost", r.costUsd ?: -1.0)
        .apply {
            r.stats?.let { s ->
                put(
                    "stats",
                    JSONObject().put("sample", s.sample).put("originals", s.originals).put("natural", s.naturalPct).put("bot", s.botLikePct)
                        .put("copy", s.copyPastePct).put("newAcc", s.newAccountPct).put("low", s.lowReachPct).put("fan", s.fanClubPct)
                        .put("top10", s.top10AuthorsPct).put("medLikes", s.medianLikes).put("zeroLikes", s.zeroLikePct)
                        .put("organizers", pairs(s.organizers)).put("paired", pairs(s.pairedHashtags)).put("mentions", pairs(s.topMentions))
                        .put("langs", pairs(s.languages)).put("india", s.indiaPctOfLocated ?: -1).put("media", s.mediaPct),
                )
            }
            r.sentiment?.let { s ->
                put(
                    "sentiment",
                    JSONObject().put("pos", s.positive).put("neg", s.negative).put("neu", s.neutral).put("judged", s.judged)
                        .put("label", s.label).put("cost", s.costUsd ?: -1.0)
                        .put("by", JSONObject(s.byContestant.mapValues { JSONArray().put(it.value.first).put(it.value.second).put(it.value.third) })),
                )
            }
        }

    private fun decode(o: JSONObject): TrendCheckResult {
        val h = o.getJSONArray("hourly")
        val stats = o.optJSONObject("stats")?.let { s ->
            SampleStats(
                s.getInt("sample"), s.getInt("originals"), s.getInt("natural"), s.getInt("bot"), s.getInt("copy"), s.getInt("newAcc"),
                s.getInt("low"), s.getInt("fan"), s.getInt("top10"), s.getLong("medLikes"), s.getInt("zeroLikes"),
                readPairs(s.optJSONArray("organizers")), readPairs(s.optJSONArray("paired")), readPairs(s.optJSONArray("mentions")),
                readPairs(s.optJSONArray("langs")), s.optInt("india", -1).takeIf { it >= 0 }, s.optInt("media"),
            )
        }
        val sentiment = o.optJSONObject("sentiment")?.let { s ->
            val by = s.getJSONObject("by")
            Sentiment(
                s.getInt("pos"), s.getInt("neg"), s.getInt("neu"),
                by.keys().asSequence().associateWith { k -> by.getJSONArray(k).let { Triple(it.getInt(0), it.getInt(1), it.getInt(2)) } },
                s.getInt("judged"), s.optString("label"), s.optDouble("cost", -1.0).takeIf { it >= 0 },
            )
        }
        return TrendCheckResult(
            tagline = o.getString("tagline"), startMs = o.getLong("start"), endMs = o.getLong("end"), ended = o.getBoolean("ended"), atMs = o.getLong("at"),
            shares = CountShares(o.getLong("all"), o.getLong("orig"), o.getLong("replies"), o.getLong("quotes")),
            hourly = List(h.length()) { h.getJSONArray(it).let { p -> HourBucket(p.getLong(0), p.getLong(1)) } },
            launch = o.optString("launch").takeIf { it.isNotEmpty() && it != "null" }?.let { runCatching { LaunchType.valueOf(it) }.getOrNull() },
            waves = o.getJSONArray("waves").let { a -> List(a.length()) { a.getLong(it) } },
            stats = stats, sentiment = sentiment, postsRead = o.optInt("posts"),
            readHours = o.optJSONArray("read")?.let { a -> List(a.length()) { a.getLong(it) } }.orEmpty(),
            costUsd = o.optDouble("cost", -1.0).takeIf { it >= 0 },
        )
    }

    private fun encodePost(p: TcPost) = JSONObject().put("id", p.id).put("a", p.authorId).put("t", p.createdMs).put("x", p.text).put("l", p.lang)
        .put("ty", p.type.name).put("li", p.likes).put("rt", p.retweets).put("h", JSONArray(p.hashtags)).put("m", JSONArray(p.mentions))
        .put("md", p.hasMedia).put("o", p.original ?: JSONObject.NULL).put("oi", p.originalId ?: JSONObject.NULL)

    private fun decodePost(o: JSONObject): TcPost {
        fun strs(k: String) = o.getJSONArray(k).let { a -> List(a.length()) { a.getString(it) } }
        return TcPost(
            o.getString("id"), o.getString("a"), o.getLong("t"), o.getString("x"), o.getString("l"), PostType.valueOf(o.getString("ty")),
            o.getLong("li"), o.getLong("rt"), strs("h"), strs("m"), o.getBoolean("md"),
            o.optString("o").takeIf { it.isNotEmpty() && it != "null" }, o.optString("oi").takeIf { it.isNotEmpty() && it != "null" },
        )
    }

    private fun encodeAuthor(a: TcAuthor) = JSONObject().put("id", a.id).put("u", a.username).put("n", a.name).put("c", a.createdMs ?: -1L)
        .put("f", a.followers).put("fg", a.following).put("tw", a.tweets).put("v", a.verified).put("d", a.defaultAvatar).put("b", a.bio).put("lo", a.location)

    private fun decodeAuthor(o: JSONObject) = TcAuthor(
        o.getString("id"), o.getString("u"), o.getString("n"), o.getLong("c").takeIf { it >= 0 }, o.getLong("f"), o.getLong("fg"), o.getLong("tw"),
        o.getBoolean("v"), o.getBoolean("d"), o.getString("b"), o.getString("lo"),
    )

    private fun encodeReadout(r: ReadoutRecord) = JSONObject()
        .put("report", r.reportAtMs).put("totals", JSONObject(r.totals))
        .put("headline", r.analysis.headline).put("insights", JSONArray(r.analysis.insights))
        .put("momentum", r.analysis.momentum).put("angle", r.analysis.captionAngle)
        .put("label", r.analysis.label).put("cost", r.analysis.costUsd ?: -1.0).put("at", r.analysis.atMs)

    private fun decodeReadout(o: JSONObject): ReadoutRecord {
        val t = o.getJSONObject("totals")
        val i = o.getJSONArray("insights")
        return ReadoutRecord(
            reportAtMs = o.getLong("report"),
            totals = t.keys().asSequence().associateWith { t.getLong(it) },
            analysis = TaglineAnalysis(
                headline = o.getString("headline"), insights = List(i.length()) { i.getString(it) }, momentum = o.getString("momentum"),
                captionAngle = o.getString("angle"), label = o.getString("label"), costUsd = o.getDouble("cost").takeIf { it >= 0 }, atMs = o.getLong("at"),
            ),
        )
    }
}

/** What the Trend check service needs from its storage: the day's spend, the measured price per post, checks and raw samples. */
interface TrendCheckRepository {
    fun spentOn(day: LocalDate): Double
    fun add(day: LocalDate, usd: Double)
    var costPerPost: Double?
    fun all(): List<TrendCheckResult>
    fun forTagline(tagline: String): List<TrendCheckResult>
    fun find(tagline: String, startMs: Long): TrendCheckResult?
    fun save(r: TrendCheckResult)
    fun sample(key: String): StoredSample?
    fun saveSample(key: String, s: StoredSample)
}

/** Up to three short lines about a Trend check for the share image and post. Sample figures are marked as estimates. */
fun trendCheckLines(c: TrendCheckResult): List<String> = listOfNotNull(
    c.stats?.let { "~${it.naturalPct}% natural · ${it.botLikePct}% bot-like · ${it.copyPastePct}% copy-pasted (estimate)" },
    c.sentiment?.takeIf { it.total > 0 }?.let { m ->
        "Mood: ${pct(m.positive.toLong(), m.total.toLong())}% positive · ${pct(m.negative.toLong(), m.total.toLong())}% negative"
    },
    "${c.shares.retweetPct}% retweets" + (c.launch?.let { " · launch " + it.label.substringBefore(':').lowercase() } ?: ""),
).take(3)
