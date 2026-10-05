package com.clipcut.domain.gcprune

import org.json.JSONArray
import org.json.JSONObject
import java.time.Instant

/** Where audit files go. Throws if the primary copy cannot be written. */
interface AuditSink {
    /** Writes (or rewrites) [fileName] with [json] and returns where a person can find it. */
    suspend fun write(fileName: String, json: String): String
}

/** A person the run considered, with what was known about them. */
data class AuditPerson(val userId: String, val handle: String?, val name: String?)

/** One pasted name and how it was classified, e.g. "removable", "follows you", "not in group". */
data class AuditRow(val requested: String, val userId: String?, val handle: String?, val name: String?, val status: String)

data class AuditEvent(val person: AuditPerson, val outcome: String, val detail: String, val atEpochMs: Long)

/**
 * The record of one run, dry or live. Kept mutable inside the service and turned into JSON after every
 * change, so the file on disk is never behind what has happened to the group. It is the list to use if
 * someone has to be re-added.
 */
class AuditRun(
    val runId: String,
    val groupId: String,
    val mode: String,
    val startedAtEpochMs: Long,
) {
    var memberCount: Int = 0

    /** False when the follow check could not be proven; nothing was pre-ticked in that case. */
    var followCheckTrusted: Boolean? = null
    var cap: Int? = null
    var delayMs: Long? = null
    var stopReason: String? = null
    var finishedAtEpochMs: Long? = null

    /** Set just BEFORE a removal request goes out and cleared after, so a kill mid-request leaves a trace. */
    var inFlight: AuditPerson? = null

    val review = ArrayList<AuditRow>()
    val removed = ArrayList<AuditEvent>()
    val skipped = ArrayList<AuditEvent>()
    val failures = ArrayList<AuditEvent>()

    fun fileName(): String = "gc-prune-${groupId}-${runId}.json"

    fun toJson(): String {
        fun person(p: AuditPerson) = JSONObject().put("user_id", p.userId).put("handle", p.handle ?: JSONObject.NULL).put("name", p.name ?: JSONObject.NULL)
        fun row(r: AuditRow) = JSONObject().put("requested", r.requested).put("user_id", r.userId ?: JSONObject.NULL)
            .put("handle", r.handle ?: JSONObject.NULL).put("name", r.name ?: JSONObject.NULL).put("status", r.status)
        fun event(e: AuditEvent) = person(e.person).put("outcome", e.outcome).put("detail", e.detail).put("at", Instant.ofEpochMilli(e.atEpochMs).toString())
        return JSONObject()
            .put("run_id", runId)
            .put("group_id", groupId)
            .put("mode", mode)
            .put("started_at", Instant.ofEpochMilli(startedAtEpochMs).toString())
            .put("finished_at", finishedAtEpochMs?.let { Instant.ofEpochMilli(it).toString() } ?: JSONObject.NULL)
            .put("stop_reason", stopReason ?: JSONObject.NULL)
            .put("settings", JSONObject().put("cap", cap ?: JSONObject.NULL).put("delay_ms", delayMs ?: JSONObject.NULL))
            .put("member_count", memberCount)
            .put("follow_check_trusted", followCheckTrusted ?: JSONObject.NULL)
            .put("in_flight", inFlight?.let(::person) ?: JSONObject.NULL)
            .put("review", JSONArray(review.map(::row)))
            .put("removed", JSONArray(removed.map(::event)))
            .put("skipped", JSONArray(skipped.map(::event)))
            .put("failures", JSONArray(failures.map(::event)))
            .toString(2)
    }
}
