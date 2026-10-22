package com.clipcut.domain.scheduler

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

/** The laptop's scheduler service could not be reached, refused the request, or sent something unexpected. */
class SchedulerException(message: String, val httpCode: Int = 0) : Exception(message)

val ALL_DAYS = listOf("MON", "TUE", "WED", "THU", "FRI", "SAT", "SUN")
const val MAX_NIGHT_TIMES = 4

/** One group of days with the times the night job runs on them. */
data class NightRule(val days: List<String>, val times: List<String>)

data class MorningInfo(val enabled: Boolean, val time: String, val nextRunAt: String?, val taskEnabled: Boolean)

data class NightInfo(val enabled: Boolean, val rules: List<NightRule>, val nextRunAt: String?, val taskEnabled: Boolean)

/** One entry of the run history. [ok]: true done, false failed or skipped or cancelled, null still running. */
data class SchedRun(val runId: String, val kind: String, val source: String, val startedAt: String, val finishedAt: String?, val ok: Boolean?, val message: String)

data class UploadInfo(val lastOkAt: String?, val lastError: String?, val filesPending: Int)

data class SchedStatus(
    val now: String,
    val timezone: String,
    val recording: Boolean,
    val state: String,
    val obsReachable: Boolean,
    val streaming: Boolean,
    val morning: MorningInfo,
    val night: NightInfo,
    val lastRuns: List<SchedRun>,
    val upload: UploadInfo,
) {
    /** The newest run that has not finished, or null. */
    val activeRun: SchedRun? get() = lastRuns.firstOrNull { it.ok == null }
}

/** What the user wants the schedule to become. Every part is optional: only what is set changes. */
data class ScheduleEdit(
    val morningEnabled: Boolean? = null,
    val morningTime: String? = null,
    val nightEnabled: Boolean? = null,
    val nightRules: List<NightRule>? = null,
)

interface SchedulerApi {
    val isConfigured: Boolean
    suspend fun status(): SchedStatus
    suspend fun triggerMorning(requireStreaming: Boolean): String
    suspend fun triggerNight(shutdown: Boolean): String

    /** Cancels a pending shutdown (or an upload that has not started). Returns the laptop's message. */
    suspend fun cancel(): String
    suspend fun putSchedule(edit: ScheduleEdit)
}

private val TIME_OF_DAY = Regex("""^([01]\d|2[0-3]):[0-5]\d$""")

fun isTimeOfDay(s: String): Boolean = TIME_OF_DAY.matches(s)

fun timeText(hour: Int, minute: Int): String = "%02d:%02d".format(hour, minute)

/** Why [rules] cannot be sent (the laptop would refuse it too), or null when they are fine. */
fun nightRulesProblem(rules: List<NightRule>): String? {
    if (rules.isEmpty()) return "Add at least one night rule, or switch the night job off."
    if (rules.size > ALL_DAYS.size) return "Too many night rules."
    rules.forEachIndexed { i, r ->
        val n = i + 1
        if (r.days.isEmpty()) return "Night rule $n has no days."
        if (r.times.isEmpty()) return "Night rule $n has no time."
        if (r.times.size > MAX_NIGHT_TIMES) return "Night rule $n has more than $MAX_NIGHT_TIMES times."
        r.times.firstOrNull { !isTimeOfDay(it) }?.let { return "Night rule $n has a bad time: $it." }
        if (r.times.toSet().size != r.times.size) return "Night rule $n repeats a time."
    }
    if (rules.flatMap { it.times }.toSet().size > MAX_NIGHT_TIMES) return "At most $MAX_NIGHT_TIMES different night times in all."
    val seen = HashSet<String>()
    for (r in rules) for (d in r.days) {
        if (d !in ALL_DAYS) return "Unknown day: $d."
        if (!seen.add(d)) return "${dayName(d)} is in two night rules. Each day belongs to one rule."
    }
    return null
}

fun dayName(code: String): String = when (code) {
    "MON" -> "Monday"; "TUE" -> "Tuesday"; "WED" -> "Wednesday"; "THU" -> "Thursday"
    "FRI" -> "Friday"; "SAT" -> "Saturday"; "SUN" -> "Sunday"; else -> code
}

/** "Mon 03:00" style lines for one rule set, e.g. "Mon: 03:00", "Tue to Sun: 01:00". */
fun nightRulesText(rules: List<NightRule>): String = rules.joinToString("\n") { r ->
    val days = r.days.sortedBy { ALL_DAYS.indexOf(it) }
    val dayText = when {
        days.size == 7 -> "Every day"
        days.size >= 3 && isRun(days) -> "${days.first().cap()} to ${days.last().cap()}"
        else -> days.joinToString(", ") { it.cap() }
    }
    "$dayText: ${r.times.sorted().joinToString(", ")}"
}

private fun isRun(days: List<String>): Boolean = days.map { ALL_DAYS.indexOf(it) }.let { ix -> ix.zipWithNext().all { (a, b) -> b == a + 1 } }

private fun String.cap(): String = lowercase().replaceFirstChar { it.uppercase() }

fun scheduleBody(edit: ScheduleEdit): JSONObject {
    val body = JSONObject()
    if (edit.morningEnabled != null || edit.morningTime != null) {
        val m = JSONObject()
        edit.morningEnabled?.let { m.put("enabled", it) }
        edit.morningTime?.let { m.put("time", it) }
        body.put("morning", m)
    }
    if (edit.nightEnabled != null || edit.nightRules != null) {
        val n = JSONObject()
        edit.nightEnabled?.let { n.put("enabled", it) }
        edit.nightRules?.let { rules ->
            n.put("rules", JSONArray(rules.map { JSONObject().put("days", JSONArray(it.days)).put("times", JSONArray(it.times)) }))
        }
        body.put("night", n)
    }
    return body
}

private fun JSONObject.strOrNull(key: String): String? = if (isNull(key)) null else optString(key).takeIf { it.isNotEmpty() }

private fun JSONArray.strings(): List<String> = (0 until length()).map { getString(it) }

fun parseStatus(body: String): SchedStatus {
    try {
        val o = JSONObject(body)
        val m = o.getJSONObject("morning")
        val n = o.getJSONObject("night")
        val rules = n.optJSONArray("rules")?.let { arr ->
            (0 until arr.length()).map { arr.getJSONObject(it) }.map { NightRule(it.getJSONArray("days").strings(), it.getJSONArray("times").strings()) }
        } ?: if (!n.isNull("times") && !n.isNull("days")) listOf(NightRule(n.getJSONArray("days").strings(), n.getJSONArray("times").strings())) else emptyList()
        val runs = o.optJSONArray("lastRuns")?.let { arr ->
            (0 until arr.length()).map { arr.getJSONObject(it) }.map { parseRun(it) }
        }.orEmpty()
        val up = o.optJSONObject("upload")
        return SchedStatus(
            now = o.getString("now"),
            timezone = o.optString("timezone"),
            recording = o.optBoolean("recording"),
            state = o.optString("state", "idle"),
            obsReachable = o.optBoolean("obsReachable"),
            streaming = o.optBoolean("streaming"),
            morning = MorningInfo(m.optBoolean("enabled"), m.optString("time"), m.strOrNull("nextRunAt"), m.optBoolean("taskEnabled", true)),
            night = NightInfo(n.optBoolean("enabled"), rules, n.strOrNull("nextRunAt"), n.optBoolean("taskEnabled", true)),
            lastRuns = runs,
            upload = UploadInfo(up?.strOrNull("lastOkAt"), up?.strOrNull("lastError"), up?.optInt("filesPending") ?: 0),
        )
    } catch (e: JSONException) {
        throw SchedulerException("The laptop's scheduler sent a reply that was not what was expected")
    }
}

private fun parseRun(r: JSONObject) = SchedRun(
    runId = r.optString("runId"),
    kind = r.optString("kind"),
    source = r.optString("source"),
    startedAt = r.optString("startedAt"),
    finishedAt = r.strOrNull("finishedAt"),
    ok = if (r.isNull("ok")) null else r.optBoolean("ok"),
    message = r.optString("message"),
)

class RealSchedulerApi(
    private val client: OkHttpClient,
    private val baseUrl: String,
    private val token: String,
) : SchedulerApi {
    override val isConfigured: Boolean get() = baseUrl.isNotBlank() && token.isNotBlank()

    private fun request(path: String): Request.Builder {
        if (!isConfigured) throw SchedulerException("The laptop scheduler is not set up. Add SCHEDULER_URL and SCHEDULER_TOKEN to local.properties and rebuild.")
        val url = "${baseUrl.trimEnd('/')}/$path".toHttpUrlOrNull() ?: throw SchedulerException("SCHEDULER_URL is not a valid address.")
        return Request.Builder().url(url).header("Authorization", "Bearer $token")
    }

    private fun send(req: Request): String = try {
        client.newCall(req).execute().use { r ->
            val text = r.body?.string().orEmpty()
            if (!r.isSuccessful) {
                val why = when (r.code) {
                    401 -> "the token was refused"
                    429 -> "too many requests, wait a minute"
                    else -> runCatching { JSONObject(text).optString("error") }.getOrNull().orEmpty().ifEmpty { "HTTP ${r.code}" }
                }
                throw SchedulerException("The laptop said: $why", r.code)
            }
            text
        }
    } catch (e: java.io.IOException) {
        throw SchedulerException("Could not reach the laptop (is it on, and is Tailscale connected?): ${e.message}")
    }

    private fun json(o: JSONObject) = o.toString().toRequestBody("application/json".toMediaType())

    private fun runIdOf(body: String): String = try {
        JSONObject(body).optString("runId")
    } catch (e: JSONException) {
        throw SchedulerException("The laptop's scheduler sent a reply that was not what was expected")
    }

    override suspend fun status(): SchedStatus = withContext(Dispatchers.IO) { parseStatus(send(request("status").get().build())) }

    override suspend fun triggerMorning(requireStreaming: Boolean): String = withContext(Dispatchers.IO) {
        runIdOf(send(request("trigger/morning").post(json(JSONObject().put("requireStreaming", requireStreaming))).build()))
    }

    override suspend fun triggerNight(shutdown: Boolean): String = withContext(Dispatchers.IO) {
        runIdOf(send(request("trigger/night").post(json(JSONObject().put("shutdown", shutdown))).build()))
    }

    override suspend fun cancel(): String = withContext(Dispatchers.IO) {
        val body = send(request("cancel").post(json(JSONObject())).build())
        try {
            JSONObject(body).let { it.optString("message").ifEmpty { if (it.optBoolean("cancelled")) "Cancelled." else "Nothing to cancel." } }
        } catch (e: JSONException) {
            throw SchedulerException("The laptop's scheduler sent a reply that was not what was expected")
        }
    }

    override suspend fun putSchedule(edit: ScheduleEdit): Unit = withContext(Dispatchers.IO) {
        send(request("schedule").put(json(scheduleBody(edit))).build())
        Unit
    }
}
