package com.clipcut.domain.gcprune

import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject

/** A reply that cannot be trusted: wrong shape, missing parts or an `errors` array. Never papered over. */
class GcParseException(message: String) : Exception(message)

private val NUMERIC_ID = Regex("^[0-9]{1,19}$")

data class SelfInfo(val id: String, val username: String)

data class GcConversation(val id: String, val type: String, val memberIds: List<String>, val adminIds: List<String>)

/** An account found by @handle. [followsMain] is true only when X positively said `followed_by`. */
data class FoundUser(val id: String, val username: String, val name: String, val followsMain: Boolean)

/** The answer for one handle that was asked about: found, or not found with X's reason. */
data class NameLookup(val requested: String, val found: FoundUser?, val notFoundReason: String?)

sealed interface RemoveOutcome {
    data object Removed : RemoveOutcome
    data class NotRemoved(val reason: String) : RemoveOutcome
}

private fun obj(body: String): JSONObject = try {
    JSONObject(body)
} catch (e: JSONException) {
    throw GcParseException("X sent a reply that is not valid JSON")
}

private fun failOnErrors(root: JSONObject) {
    val errors = root.optJSONArray("errors")
    if (errors != null && errors.length() > 0) {
        val first = errors.optJSONObject(0)
        throw GcParseException("X reported an error: ${first?.optString("detail")?.ifBlank { first.optString("title") } ?: "unknown"}")
    }
}

private fun idList(array: JSONArray?, what: String): List<String> {
    if (array == null) throw GcParseException("The reply has no $what")
    return List(array.length()) { i ->
        val id = array.optString(i)
        if (!NUMERIC_ID.matches(id)) throw GcParseException("The reply has a $what entry that is not a user id")
        id
    }
}

fun parseSelf(body: String): SelfInfo {
    val root = obj(body)
    failOnErrors(root)
    val data = root.optJSONObject("data") ?: throw GcParseException("The reply has no account")
    val id = data.optString("id")
    if (!NUMERIC_ID.matches(id)) throw GcParseException("The account has no valid id")
    return SelfInfo(id, data.optString("username"))
}

/** One chat group. The id must echo [expectedId], so a reply about some other conversation is never used. */
fun parseConversation(body: String, expectedId: String): GcConversation {
    val root = obj(body)
    failOnErrors(root)
    val data = root.optJSONObject("data") ?: throw GcParseException("The reply has no conversation")
    val id = data.optString("id")
    if (id != expectedId) throw GcParseException("X answered about a different conversation")
    return GcConversation(
        id = id,
        type = data.optString("type"),
        memberIds = idList(data.optJSONArray("member_ids"), "member list"),
        adminIds = idList(data.optJSONArray("admin_ids"), "admin list"),
    )
}

/**
 * The answer to "who are these @handles". Every handle asked about comes back exactly once: found (with
 * whether it follows the main account, from `connection_status`, which X leaves OUT for strangers), or not
 * found with X's reason. Anything unexplained fails the whole lookup: an answer about an account nobody asked
 * about, an error that names none of the handles, or an id that is not a number.
 */
fun parseUsersByName(body: String, requested: List<String>): List<NameLookup> {
    val root = obj(body)
    val byLower = requested.associateBy { it.lowercase() }
    val found = HashMap<String, FoundUser>()
    val missing = HashMap<String, String>()

    root.optJSONArray("data")?.let { data ->
        for (i in 0 until data.length()) {
            val u = data.optJSONObject(i) ?: throw GcParseException("X sent a user entry that could not be read")
            val username = u.optString("username")
            val asked = byLower[username.lowercase()] ?: throw GcParseException("X answered about an account that was not asked about")
            val id = u.optString("id")
            if (!NUMERIC_ID.matches(id)) throw GcParseException("An account has no valid id")
            val status = u.optJSONArray("connection_status")
            val follows = status != null && (0 until status.length()).any { status.optString(it) == "followed_by" }
            found[asked] = FoundUser(id, username, u.optString("name"), follows)
        }
    }
    root.optJSONArray("errors")?.let { errors ->
        for (i in 0 until errors.length()) {
            val e = errors.optJSONObject(i) ?: throw GcParseException("X sent an error that could not be read")
            val named = listOf(e.optString("value"), e.optString("resource_id")).firstNotNullOfOrNull { byLower[it.lowercase()] }
                ?: throw GcParseException("X reported an error: ${e.optString("detail").ifBlank { e.optString("title") }}")
            missing[named] = e.optString("detail").ifBlank { e.optString("title") }.ifBlank { "X could not find this account" }
        }
    }
    return requested.map { name ->
        NameLookup(name, found[name], if (name in found) null else missing[name] ?: "X gave no answer for this name")
    }
}

fun parseRemoveResponse(body: String): RemoveOutcome {
    val root = try {
        obj(body)
    } catch (e: GcParseException) {
        return RemoveOutcome.NotRemoved(e.message.orEmpty())
    }
    val errors = root.optJSONArray("errors")
    if (errors != null && errors.length() > 0) {
        val first = errors.optJSONObject(0)
        return RemoveOutcome.NotRemoved(first?.optString("detail")?.ifBlank { first.optString("title") } ?: "X reported an error")
    }
    val removed = root.optJSONObject("data")?.takeIf { it.has("removed") }?.optBoolean("removed")
    return if (removed == true) RemoveOutcome.Removed else RemoveOutcome.NotRemoved("X did not confirm the removal")
}
