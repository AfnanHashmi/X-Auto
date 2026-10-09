package com.clipcut.domain.radar

import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject

/** A reply from the Trend Radar Worker that cannot be trusted: wrong shape or missing parts. */
class RadarParseException(message: String) : Exception(message)

enum class RunKind { PULSE, DEEP }

data class RadarTopic(
    val name: String,
    val keywordsEn: List<String>,
    val keywordsHi: List<String>,
    val keywordsHinglish: List<String>,
    val contestants: List<String>,
    val hashtags: List<String>,
)

/** One kind of day: when its window opens and closes (IST) and when its scheduled deep scans run. */
data class WindowProfile(val name: String, val start: String, val end: String, val deepTimes: List<String>)

data class RadarConfig(
    val pulseModel: String,
    val deepModel: String,
    val pulseSizes: List<Int>,
    val pulsePosts: Int,
    val peakPosts: Int,
    val deepPosts: Int,
    val dailyCapUsd: Double,
    val creditsAddedUsd: Double?,
    val excludedHandles: List<String>,
    val topic: RadarTopic,
    val profiles: List<WindowProfile>,
    /** Which profile each weekday uses, e.g. mon -> weekday. */
    val days: Map<String, String>,
    val pulseEveryMin: Int,
    val peakStart: String,
    val peakEnd: String,
    val spikeMaxPerDay: Int,
)

/** off, armed (waiting for the window), running, or ended (it stopped by itself or was switched off). */
data class SessionInfo(
    val phase: String,
    val on: Boolean,
    val windowStartMs: Long?,
    val windowEndMs: Long?,
    val budgetUsd: Double?,
    val spentUsd: Double,
    val deepSlots: List<Long>,
    val deepDone: List<Long>,
    val spikeQueue: List<String>,
    val endedReason: String?,
    val endedAtMs: Long?,
    val lastLookAtMs: Long?,
)

data class Spend(val todayUsd: Double, val monthUsd: Double, val sinceCreditsUsd: Double, val creditsLeftUsd: Double?)

data class LastJob(val atMs: Long, val type: String, val ok: Boolean, val reason: String?)

data class JobInfo(val queued: Int, val running: Boolean, val last: LastJob?)

data class RunSummary(
    val id: Long,
    val type: String,
    val trigger: String,
    val model: String,
    val startedAtMs: Long,
    val status: String,
    val error: String?,
    val focus: String?,
    val targetPosts: Int,
    val postsFetched: Int,
    val searchCalls: Int,
    val inputTokens: Long,
    val outputTokens: Long,
    val costUsd: Double,
    val droppedUrls: Int,
)

data class SubtopicRow(
    val key: String,
    val name: String,
    val mentions: Int,
    val share: Double,
    val sentiment: String,
    val keywords: List<String>,
    val exampleUrls: List<String>,
    val label: String,
    val growth: Double,
    val baseline: Double,
) {
    val isSpike: Boolean get() = label == "RISING" || (label == "NEW" && mentions >= 3)
}

data class FindingRow(val kind: String, val text: String, val url: String?, val extra: String?)

data class LatestReport(val run: RunSummary, val subtopics: List<SubtopicRow>, val findings: List<FindingRow>)

data class RadarReport(
    val serverTimeMs: Long,
    val config: RadarConfig,
    val session: SessionInfo,
    val spend: Spend,
    val job: JobInfo,
    val latest: LatestReport?,
    val runs: List<RunSummary>,
) {
    val risingCount: Int get() = latest?.subtopics?.count { it.isSpike } ?: 0
}

/** What a run would cost and whether the guard allows it. */
data class RunPreview(
    val type: RunKind,
    val model: String,
    val posts: Int,
    val likelyUsd: Double,
    val worstUsd: Double,
    val allowed: Boolean,
    val refusal: String?,
    val spentTodayUsd: Double,
    val creditsLeftUsd: Double?,
)

private fun JSONObject.strOrNull(key: String): String? = if (isNull(key) || !has(key)) null else optString(key).takeIf { it.isNotBlank() }

private fun JSONObject.dblOrNull(key: String): Double? = if (isNull(key) || !has(key)) null else optDouble(key).takeIf { !it.isNaN() }

private fun JSONObject.longOrNull(key: String): Long? = if (isNull(key) || !has(key)) null else optLong(key)

private fun JSONArray?.strings(): List<String> = if (this == null) emptyList() else List(length()) { optString(it) }.filter { it.isNotBlank() }

private fun JSONArray?.longs(): List<Long> = if (this == null) emptyList() else List(length()) { optLong(it) }

private fun JSONArray?.objects(): List<JSONObject> = if (this == null) emptyList() else List(length()) { optJSONObject(it) }.filterNotNull()

private fun need(o: JSONObject, key: String): JSONObject = o.optJSONObject(key) ?: throw RadarParseException("The reply is missing '$key'")

private fun run(o: JSONObject) = RunSummary(
    id = o.optLong("id", -1).also { if (it < 0) throw RadarParseException("A run has no id") },
    type = o.optString("type"),
    trigger = o.optString("trigger"),
    model = o.optString("model"),
    startedAtMs = o.optLong("started_at"),
    status = o.optString("status").ifBlank { throw RadarParseException("A run has no status") },
    error = o.strOrNull("error"),
    focus = o.strOrNull("focus"),
    targetPosts = o.optInt("target_posts"),
    postsFetched = o.optInt("posts_fetched"),
    searchCalls = o.optInt("search_calls"),
    inputTokens = o.optLong("input_tokens"),
    outputTokens = o.optLong("output_tokens"),
    costUsd = o.optDouble("cost_usd", 0.0),
    droppedUrls = o.optInt("dropped_urls"),
)

private fun config(c: JSONObject): RadarConfig {
    val topic = c.optJSONArray("topics").objects().firstOrNull() ?: throw RadarParseException("The settings have no topic")
    val schedule = need(c, "schedule")
    val profiles = need(schedule, "profiles").let { p -> p.keys().asSequence().map { name -> p.getJSONObject(name).let { WindowProfile(name, it.optString("start"), it.optString("end"), it.optJSONArray("deep_times").strings()) } }.toList().sortedBy { it.name } }
    val daysObj = need(schedule, "days")
    val peak = schedule.optJSONObject("peak")
    return RadarConfig(
        pulseModel = c.optString("pulse_model"),
        deepModel = c.optString("deep_model"),
        pulseSizes = c.optJSONArray("pulse_sizes").let { a -> if (a == null) emptyList() else List(a.length()) { a.optInt(it) } },
        pulsePosts = need(c, "pulse").optInt("posts"),
        peakPosts = need(c, "pulse").optInt("peak_posts"),
        deepPosts = need(c, "deep").optInt("posts"),
        dailyCapUsd = c.optDouble("daily_cap_usd", 6.0),
        creditsAddedUsd = c.dblOrNull("credits_added_usd"),
        excludedHandles = c.optJSONArray("excluded_x_handles").strings(),
        topic = RadarTopic(
            topic.optString("name"), topic.optJSONArray("keywords_en").strings(), topic.optJSONArray("keywords_hi").strings(),
            topic.optJSONArray("keywords_hinglish").strings(), topic.optJSONArray("contestants").strings(), topic.optJSONArray("hashtags").strings(),
        ),
        profiles = profiles,
        days = daysObj.keys().asSequence().associateWith { daysObj.optString(it) },
        pulseEveryMin = schedule.optInt("pulse_every_min", 60),
        peakStart = peak?.optString("start").orEmpty(),
        peakEnd = peak?.optString("end").orEmpty(),
        spikeMaxPerDay = c.optJSONObject("spike")?.optInt("max_per_day", 3) ?: 3,
    )
}

/** The whole /report reply. Strict about the parts the screens depend on. */
fun parseRadarReport(body: String): RadarReport {
    val root = try {
        JSONObject(body)
    } catch (e: JSONException) {
        throw RadarParseException("The Trend Radar sent a reply that is not valid JSON")
    }
    val s = need(root, "session")
    val sp = need(root, "spend")
    val job = need(root, "job")
    val latest = root.optJSONObject("latest")?.let { l ->
        LatestReport(
            run = run(need(l, "run")),
            subtopics = l.optJSONArray("snapshots").objects().map { o ->
                SubtopicRow(
                    key = o.optString("key"), name = o.optString("name"), mentions = o.optInt("mentions"), share = o.optDouble("share"),
                    sentiment = o.optString("sentiment"), keywords = o.optString("keywords").split("|").filter { it.isNotBlank() },
                    exampleUrls = o.optString("example_urls").split("\n").filter { it.isNotBlank() }, label = o.optString("label"),
                    growth = o.optDouble("growth"), baseline = o.optDouble("baseline"),
                )
            },
            findings = l.optJSONArray("findings").objects().map { FindingRow(it.optString("kind"), it.optString("text"), it.strOrNull("url"), it.strOrNull("extra")) },
        )
    }
    return RadarReport(
        serverTimeMs = root.optLong("server_time"),
        config = config(need(root, "config")),
        session = SessionInfo(
            phase = s.optString("phase").ifBlank { throw RadarParseException("The session has no phase") },
            on = s.optBoolean("on"), windowStartMs = s.longOrNull("window_start"), windowEndMs = s.longOrNull("window_end"),
            budgetUsd = s.dblOrNull("budget"), spentUsd = s.optDouble("spent", 0.0), deepSlots = s.optJSONArray("deep_slots").longs(),
            deepDone = s.optJSONArray("deep_done").longs(), spikeQueue = s.optJSONArray("spike_queue").strings(), endedReason = s.strOrNull("ended_reason"),
            endedAtMs = s.longOrNull("ended_at"), lastLookAtMs = s.longOrNull("last_look_at"),
        ),
        spend = Spend(sp.optDouble("today", 0.0), sp.optDouble("month", 0.0), sp.optDouble("since_credits", 0.0), sp.dblOrNull("credits_left")),
        job = JobInfo(
            queued = job.optJSONArray("queued")?.length() ?: 0,
            running = job.optBoolean("running"),
            last = job.optJSONObject("last")?.let { LastJob(it.optLong("at"), it.optString("type"), it.optBoolean("ok"), it.strOrNull("reason")) },
        ),
        latest = latest,
        runs = root.optJSONArray("runs").objects().map(::run),
    )
}

fun parsePreview(body: String): RunPreview {
    val o = try {
        JSONObject(body)
    } catch (e: JSONException) {
        throw RadarParseException("The Trend Radar sent a reply that is not valid JSON")
    }
    val guard = need(o, "guard")
    return RunPreview(
        type = if (o.optString("type") == "DEEP") RunKind.DEEP else RunKind.PULSE,
        model = o.optString("model"),
        posts = o.optInt("posts"),
        likelyUsd = o.optDouble("likely"),
        worstUsd = o.optDouble("worst"),
        allowed = guard.optBoolean("ok"),
        refusal = guard.strOrNull("reason"),
        spentTodayUsd = o.optDouble("spent_today", 0.0),
        creditsLeftUsd = o.dblOrNull("credits_left"),
    )
}
