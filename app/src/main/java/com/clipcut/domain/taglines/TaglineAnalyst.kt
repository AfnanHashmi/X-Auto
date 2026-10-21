package com.clipcut.domain.taglines

import com.clipcut.domain.llm.LlmClient
import com.clipcut.domain.llm.LlmException
import com.clipcut.domain.llm.LlmTask
import com.clipcut.domain.share.taglineShareCard
import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject
import java.time.ZoneId

/** The model's read-out of one count, and who wrote it. [costUsd] is from the reported tokens, when the model is priced. */
data class TaglineAnalysis(
    val headline: String,
    val insights: List<String>,
    val momentum: String,
    val captionAngle: String,
    val label: String,
    val costUsd: Double?,
    val atMs: Long = 0L,
)

/**
 * Turns a Tagline Counter result into a short structured report through [LlmClient] (xAI by default). It is given only
 * the exact figures the app already has (totals, originals vs retweets, busiest hour, milestones, last hour, ranks), so
 * it costs a fraction of a cent and makes no X calls. It cannot see the tweets, and is told not to guess causes, moods
 * or who posted.
 */
class TaglineAnalyst(private val llm: LlmClient) {
    val isConfigured: Boolean get() = llm.isConfigured

    fun plannedLabel(highQuality: Boolean): String? = llm.plannedLabel(LlmTask.ANALYSIS, highQuality)

    val hasHigherTier: Boolean get() = llm.hasHigherTier(LlmTask.ANALYSIS)

    /** About 2,000 tokens in (the figures, Trend checks, last count) and 1,500 out (reasoning included). */
    fun estimateUsd(highQuality: Boolean): Double? = llm.estimateUsd(LlmTask.ANALYSIS, highQuality, 2_000, 1_500)

    /**
     * [checks] are the newest Trend check per tagline, and [previous] the last read-out of an earlier count, so the new
     * one can say what moved since then. All optional.
     */
    suspend fun analyze(
        report: CountReport,
        zone: ZoneId,
        highQuality: Boolean = false,
        checks: Map<String, TrendCheckResult> = emptyMap(),
        previous: ReadoutRecord? = null,
        nowMs: Long = System.currentTimeMillis(),
    ): TaglineAnalysis {
        val facts = taglineFacts(report, zone, checks, previous)
        val reply = llm.respond(LlmTask.ANALYSIS, SYSTEM, facts, ANALYSIS_SCHEMA, highQuality)
        return checkNumbers(parseAnalysis(reply.text), facts).copy(label = reply.label, costUsd = reply.costUsd, atMs = nowMs)
    }

    private companion object {
        val SYSTEM = """
You analyse tweet COUNTS for tagline campaigns on X (Twitter) around the Indian reality show Bigg Boss.
You are given exact figures from X's official counts endpoint. You cannot see any tweet text.

Rules:
- Use only the figures given. Quote numbers exactly as given; do not invent or extrapolate any.
- Do not claim causes, moods, sentiment, bots, or who posted, EXCEPT what a TREND CHECK section states. Its
  "exact" lines come from counts; its "sample" lines are estimates from a sample of real posts: say "in a sample of
  N posts" when you use them, and call accounts "bot-like", never "bots".
- If PREVIOUS COUNT is given, say how the totals moved since then.
- Be concrete and short. Plain English a fan page admin can act on.

Return JSON:
- headline: one line, the single most important fact (max 90 characters).
- insights: 3 to 5 short findings (each max 140 characters), most useful first: comparison between taglines,
  how organic it was (original vs retweets), speed and peak, staying power, current momentum.
- momentum: one line on where it is heading now, from the last full hour versus the peak.
- caption_angle: one line suggesting how to frame a post about these numbers.
""".trim()

        val ANALYSIS_SCHEMA: JSONObject = JSONObject()
            .put("type", "object")
            .put(
                "properties",
                JSONObject()
                    .put("headline", JSONObject().put("type", "string"))
                    .put("insights", JSONObject().put("type", "array").put("items", JSONObject().put("type", "string")))
                    .put("momentum", JSONObject().put("type", "string"))
                    .put("caption_angle", JSONObject().put("type", "string")),
            )
            .put("required", JSONArray().put("headline").put("insights").put("momentum").put("caption_angle"))
            .put("additionalProperties", false)
    }
}

/** The figures the model sees: the same ones as the share image, one tagline per block, in the phone's time zone. */
fun taglineFacts(
    report: CountReport,
    zone: ZoneId,
    checks: Map<String, TrendCheckResult> = emptyMap(),
    previous: ReadoutRecord? = null,
): String {
    val card = taglineShareCard(report, dateTimeText(report.fetchedAtMs, zone), zone)
    return buildString {
        append("Period: ").append(dateTimeText(report.startMs, zone)).append(" to ").append(dateTimeText(report.endMs, zone))
            .append(" (").append(zoneLabel(zone, report.fetchedAtMs)).append(", last 24 hours)\n")
        if (card.compares) append("Combined: ").append("%,d".format(report.combinedAll)).append(" tweets across ").append(card.stats.size).append(" taglines\n")
        card.stats.forEach { s ->
            append('\n').append(s.rankText?.let { "$it " }.orEmpty()).append('"').append(s.tagline).append("\": ").append(s.valueText)
            s.shareText?.let { append(" (").append(it).append(')') }
            append('\n')
            s.detailLines(zone).forEach { append("- ").append(it).append('\n') }
            checks[s.tagline]?.let { c -> append(trendCheckFacts(c, zone)) }
        }
        previous?.takeIf { it.reportAtMs != report.fetchedAtMs }?.let { p ->
            append("\nPREVIOUS COUNT (").append(dateTimeText(p.reportAtMs, zone)).append("): ")
            append(p.totals.entries.joinToString("; ") { (t, n) -> "\"$t\" " + "%,d".format(n) })
            append("\nPrevious read-out headline: ").append(p.analysis.headline)
        }
    }.trim()
}

/** The model's JSON as a [TaglineAnalysis]. Anything missing or empty fails rather than showing a half report. */
fun parseAnalysis(json: String): TaglineAnalysis {
    val o = try {
        JSONObject(json.trim().removePrefix("```json").removePrefix("```").removeSuffix("```").trim())
    } catch (e: JSONException) {
        throw LlmException("The model did not return the report as JSON")
    }
    fun text(k: String) = o.optString(k).trim().ifEmpty { throw LlmException("The report is missing \"$k\"") }
    val insights = o.optJSONArray("insights")?.let { a -> (0 until a.length()).map { a.optString(it).trim() }.filter { it.isNotEmpty() } }.orEmpty()
    if (insights.isEmpty()) throw LlmException("The report has no insights")
    return TaglineAnalysis(text("headline"), insights.take(5), text("momentum"), text("caption_angle"), label = "", costUsd = null)
}

private val NUMBER = Regex("""\d[\d,]*(?:\.\d+)?""")

private fun numbersIn(text: String): Set<String> = NUMBER.findAll(text).map { it.value.replace(",", "").trimEnd('.') }.toSet()

/**
 * Every number the read-out quotes must be one of the figures it was given (small numbers up to 24, such as "3 taglines"
 * or "7 PM", are always fine). A finding with a number that is not in the data is dropped; if the headline or the
 * momentum line has one, the whole read-out is refused rather than shown. Free: nothing is sent anywhere.
 */
fun checkNumbers(a: TaglineAnalysis, facts: String): TaglineAnalysis {
    val known = numbersIn(facts)
    fun bad(text: String) = numbersIn(text).filter { n -> n !in known && (n.toDoubleOrNull() ?: 0.0) > 24.0 }
    (bad(a.headline) + bad(a.momentum)).takeIf { it.isNotEmpty() }?.let {
        throw LlmException("The read-out quoted numbers that are not in the data (${it.distinct().joinToString(", ")}), so it was not kept. Write it again.")
    }
    val kept = a.insights.filter { bad(it).isEmpty() }
    if (kept.isEmpty()) throw LlmException("Every finding quoted numbers that are not in the data, so the read-out was not kept. Write it again.")
    return a.copy(insights = kept)
}

/** The Trend check lines the read-out may use: exact ones from counts, and sample ones with the sample size. */
fun trendCheckFacts(c: TrendCheckResult, zone: ZoneId): String = buildString {
    append("TREND CHECK (").append(dateTimeText(c.startMs, zone)).append(" to ").append(dateTimeText(c.endMs, zone))
        .append(if (c.ended) ", ended" else ", still running").append("):\n")
    append("- exact: ").append(c.shares.retweetPct).append("% retweets, ").append(c.shares.replyPct).append("% replies, ")
        .append(c.shares.quotePct).append("% quotes\n")
    c.launch?.let { append("- exact: launch was ").append(it.label).append('\n') }
    if (c.waves.size > 1) append("- exact: ").append(c.waves.size).append(" push waves\n")
    c.stats?.let { s ->
        append("- sample of ").append(s.sample).append(" posts (estimates): about ").append(s.naturalPct).append("% natural, ")
            .append(s.botLikePct).append("% from bot-like accounts, ").append(s.copyPastePct).append("% copy-pasted text, ")
            .append(s.newAccountPct).append("% from accounts under 30 days old, ").append(s.fanClubPct).append("% from fan-club style accounts\n")
        if (s.organizers.isNotEmpty()) append("- most retweeted accounts in the sample: ").append(s.organizers.joinToString(", ") { it.first }).append('\n')
    }
    c.sentiment?.takeIf { it.total > 0 }?.let { m ->
        append("- sentiment in the sample: ").append(pct(m.positive.toLong(), m.total.toLong())).append("% positive, ")
            .append(pct(m.negative.toLong(), m.total.toLong())).append("% negative, ").append(pct(m.neutral.toLong(), m.total.toLong())).append("% neutral\n")
    }
}
