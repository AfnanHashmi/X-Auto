package com.clipcut.domain.unfollows

import org.json.JSONException
import org.json.JSONObject
import java.time.Instant

/** A reply from the tracker that cannot be trusted: wrong shape, missing parts. Never papered over. */
class UnfollowParseException(message: String) : Exception(message)

/** One person who unfollowed. [seq] is the tracker's own counter, used to ask for "everything after". */
data class UnfollowEvent(
    val seq: Long,
    val eventUuid: String,
    val userId: String,
    val username: String?,
    val name: String?,
    val followersCount: Int?,
    val followingCount: Int?,
    val receivedAtEpochMs: Long,
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
)

data class UnfollowPage(val events: List<UnfollowEvent>, val status: TrackerStatus)

private val NUMERIC_ID = Regex("^[0-9]{1,19}$")

private fun JSONObject.optStringOrNull(key: String): String? =
    if (isNull(key) || !has(key)) null else optString(key).takeIf { it.isNotBlank() }

private fun JSONObject.optIntOrNull(key: String): Int? = if (isNull(key) || !has(key)) null else optInt(key)

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
        val uuid = e.optString("event_uuid")
        val userId = e.optString("user_id")
        if (seq < 1 || uuid.isBlank() || !NUMERIC_ID.matches(userId)) throw UnfollowParseException("An event is missing its number, id or user")
        val received = try {
            Instant.parse(e.optString("received_at")).toEpochMilli()
        } catch (x: Exception) {
            throw UnfollowParseException("An event has no valid time")
        }
        UnfollowEvent(seq, uuid, userId, e.optStringOrNull("username"), e.optStringOrNull("name"), e.optIntOrNull("followers_count"), e.optIntOrNull("following_count"), received)
    }
    if (!st.has("total_events")) throw UnfollowParseException("The tracker's status is incomplete")
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
        ),
    )
}
