package com.clipcut.domain.gcprune

import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject

/** The user ids that must never be removed, whatever the follower list says. */
interface WhitelistSource {
    /** Throws if the list cannot be read: an unreadable whitelist must stop a scan, not be treated as empty. */
    suspend fun load(): Set<String>
}

private val NUMERIC_ID = Regex("^[0-9]{1,19}$")

/** `whitelist.json` is `{"user_ids": ["123", ...]}`. Anything else is an error rather than an empty list. */
fun parseWhitelist(text: String): Set<String> {
    val root = try {
        JSONObject(text)
    } catch (e: JSONException) {
        throw GcParseException("whitelist.json is not valid JSON")
    }
    val array = root.optJSONArray("user_ids") ?: throw GcParseException("whitelist.json has no user_ids list")
    return LinkedHashSet<String>().also { out ->
        for (i in 0 until array.length()) {
            val id = array.optString(i)
            if (!NUMERIC_ID.matches(id)) throw GcParseException("whitelist.json has an entry that is not a user id")
            out.add(id)
        }
    }
}

fun encodeWhitelist(ids: Set<String>): String =
    JSONObject().put("user_ids", JSONArray(ids.sorted())).toString(2)
