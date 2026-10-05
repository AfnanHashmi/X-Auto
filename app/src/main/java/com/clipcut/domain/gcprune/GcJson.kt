package com.clipcut.domain.gcprune

import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject

/** A reply that cannot be trusted: wrong shape, missing parts or an `errors` array. Never papered over. */
class GcParseException(message: String) : Exception(message)

private val NUMERIC_ID = Regex("^[0-9]{1,19}$")

data class SelfInfo(val id: String, val username: String, val followersCount: Int?)

data class GcConversation(val id: String, val type: String, val memberIds: List<String>, val adminIds: List<String>)

data class FollowersPage(val ids: List<String>, val nextToken: String?)

data class GcUserInfo(val id: String, val username: String, val name: String)

/**
 * The per-person "do they follow me" answer. X leaves `connection_status` OUT for someone with no
 * relationship (measured: followers return ["followed_by"], strangers return no field at all), so
 * "does not follow" is only accepted when the person was positively found and X reported no error.
 */
sealed interface FollowCheck {
    data object Follows : FollowCheck
    data object NotFollowing : FollowCheck
    data class Unverifiable(val reason: String) : FollowCheck
}

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
    val followers = data.optJSONObject("public_metrics")?.takeIf { it.has("followers_count") }?.optInt("followers_count")
    return SelfInfo(id, data.optString("username"), followers)
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
 * One page of followers. A page with no `data` is only an end-of-list if X says it holds zero results;
 * anything else missing is an error, because a short list is how real followers get flagged.
 */
fun parseFollowersPage(body: String): FollowersPage {
    val root = obj(body)
    failOnErrors(root)
    val meta = root.optJSONObject("meta") ?: throw GcParseException("The follower page has no meta")
    if (!meta.has("result_count")) throw GcParseException("The follower page does not say how many results it holds")
    val data = root.optJSONArray("data")
    val ids = if (data == null) {
        if (meta.optInt("result_count") != 0) throw GcParseException("The follower page says it has results but sent none")
        emptyList()
    } else {
        List(data.length()) { i ->
            val id = data.optJSONObject(i)?.optString("id").orEmpty()
            if (!NUMERIC_ID.matches(id)) throw GcParseException("A follower entry has no valid id")
            id
        }
    }
    if (ids.size != meta.optInt("result_count", -1)) throw GcParseException("The follower page is shorter than it claims")
    return FollowersPage(ids, meta.optString("next_token").takeIf { it.isNotBlank() })
}

/** Users that exist. An account that was deleted or suspended is simply absent, which is not an error. */
fun parseUsers(body: String): List<GcUserInfo> {
    val root = obj(body)
    val data = root.optJSONArray("data") ?: return emptyList()
    return List(data.length()) { i ->
        val u = data.getJSONObject(i)
        GcUserInfo(u.optString("id"), u.optString("username"), u.optString("name"))
    }.filter { NUMERIC_ID.matches(it.id) }
}

fun parseSingleUser(body: String): GcUserInfo? {
    val root = obj(body)
    val u = root.optJSONObject("data") ?: return null
    return GcUserInfo(u.optString("id"), u.optString("username"), u.optString("name")).takeIf { NUMERIC_ID.matches(it.id) }
}

fun parseFollowCheck(body: String, userId: String): FollowCheck {
    val root = try {
        obj(body)
    } catch (e: GcParseException) {
        return FollowCheck.Unverifiable(e.message.orEmpty())
    }
    val errors = root.optJSONArray("errors")
    if (errors != null && errors.length() > 0) return FollowCheck.Unverifiable("X reported an error for this account")
    val data = root.optJSONObject("data") ?: return FollowCheck.Unverifiable("X returned no such account")
    if (data.optString("id") != userId) return FollowCheck.Unverifiable("X answered about a different account")
    val status = data.optJSONArray("connection_status")
    val followedBy = status != null && (0 until status.length()).any { status.optString(it) == "followed_by" }
    return if (followedBy) FollowCheck.Follows else FollowCheck.NotFollowing
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
