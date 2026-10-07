package com.clipcut.domain.unfollows

import org.json.JSONException
import org.json.JSONObject
import java.time.Instant

/** A reply from the tracker that cannot be trusted: wrong shape, missing parts. Never papered over. */
class UnfollowParseException(message: String) : Exception(message)

/**
 * One person who unfollowed, plus what the group-chat automation did about it. [version] is the tracker's change
 * counter (a row comes again, with a higher one, when its follow-up changes).
 */
data class UnfollowEvent(
    val seq: Long,
    val eventUuid: String,
    val userId: String,
    val username: String?,
    val name: String?,
    val followersCount: Int?,
    val followingCount: Int?,
    val receivedAtEpochMs: Long,
    val gcStatus: String,
    val gcDetail: String?,
    val gcDueAtEpochMs: Long?,
    val gcUpdatedAtEpochMs: Long?,
    val version: Long,
)

/** The group-chat automation's own state. [mode] is off, dry_run or live. */
data class Automation(
    val mode: String,
    val pausedReason: String?,
    val groupId: String?,
    val delayMin: Int,
    val kickedToday: Int,
    val kickCap: Int,
    val pending: Int,
    val lastRunAt: String?,
    val whitelist: List<String>,
)

/** What the tracker says about itself. [pausedReason] is non-null when it stopped itself, so nothing is being billed. */
data class TrackerStatus(
    val totalEvents: Int,
    val trackingSince: String?,
    val lastEventAt: String?,
    val pausedReason: String?,
    val subscriptionDeleted: String?,
    val todayCount: Int,
    val dailyCap: Int,
    val rejectedSignature: Int,
    val lastProblem: String?,
    val automation: Automation?,
)

data class UnfollowPage(val events: List<UnfollowEvent>, val status: TrackerStatus)

private val NUMERIC_ID = Regex("^[0-9]{1,19}$")
private val MODES = setOf("off", "dry_run", "live")

private fun JSONObject.optStringOrNull(key: String): String? =
    if (isNull(key) || !has(key)) null else optString(key).takeIf { it.isNotBlank() }

private fun JSONObject.optIntOrNull(key: String): Int? = if (isNull(key) || !has(key)) null else optInt(key)

private fun instantOrNull(text: String?): Long? = text?.let { runCatching { Instant.parse(it).toEpochMilli() }.getOrNull() }

fun parseUnfollowPage(body: String): UnfollowPage {
    val root = try {
        JSONObject(body)
    } catch (e: JSONException) {
        throw UnfollowParseException("The tracker sent a reply that is not valid JSON")
    }
    val list = root.optJSONArray("events") ?: throw UnfollowParseException("The tracker's reply has no event list")
    val st = root.optJSONObject("status") ?: throw UnfollowParseException("The tracker's reply has no status")
    val events = List(list.length()) { i ->
        val e = list.optJSONObject(i) ?: throw UnfollowParseException("An event could not be read")
        val seq = e.optLong("seq", -1)
        val version = e.optLong("version", -1)
        val uuid = e.optString("event_uuid")
        val userId = e.optString("user_id")
        val gcStatus = e.optString("gc_status")
        if (seq < 1 || version < 1 || uuid.isBlank() || gcStatus.isBlank() || !NUMERIC_ID.matches(userId)) {
            throw UnfollowParseException("An event is missing its number, version, id, user or status")
        }
        val received = instantOrNull(e.optString("received_at")) ?: throw UnfollowParseException("An event has no valid time")
        UnfollowEvent(
            seq, uuid, userId, e.optStringOrNull("username"), e.optStringOrNull("name"),
            e.optIntOrNull("followers_count"), e.optIntOrNull("following_count"), received,
            gcStatus, e.optStringOrNull("gc_detail"), instantOrNull(e.optStringOrNull("due_at")),
            instantOrNull(e.optStringOrNull("gc_updated_at")), version,
        )
    }
    if (!st.has("total_events")) throw UnfollowParseException("The tracker's status is incomplete")
    val auto = st.optJSONObject("automation")?.let { a ->
        val mode = a.optString("mode")
        if (mode !in MODES) throw UnfollowParseException("The tracker reported an unknown mode")
        val wl = a.optJSONArray("whitelist")
        Automation(
            mode = mode,
            pausedReason = a.optStringOrNull("paused_reason"),
            groupId = a.optStringOrNull("group_id"),
            delayMin = a.optInt("delay_min"),
            kickedToday = a.optInt("kicked_today"),
            kickCap = a.optInt("kick_cap"),
            pending = a.optInt("pending"),
            lastRunAt = a.optStringOrNull("last_run_at"),
            whitelist = List(wl?.length() ?: 0) { wl!!.optString(it) }.filter { NUMERIC_ID.matches(it) },
        )
    }
    return UnfollowPage(
        events,
        TrackerStatus(
            totalEvents = st.optInt("total_events"),
            trackingSince = st.optStringOrNull("tracking_since"),
            lastEventAt = st.optStringOrNull("last_event_at"),
            pausedReason = st.optStringOrNull("paused_reason"),
            subscriptionDeleted = st.optStringOrNull("subscription_deleted"),
            todayCount = st.optInt("today_count"),
            dailyCap = st.optInt("daily_cap"),
            rejectedSignature = st.optInt("rejected_signature"),
            lastProblem = st.optStringOrNull("last_problem"),
            automation = auto,
        ),
    )
}
