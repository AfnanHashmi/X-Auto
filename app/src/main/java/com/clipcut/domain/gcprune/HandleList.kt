package com.clipcut.domain.gcprune

/**
 * Turns the text you paste ("names separated by new lines, spaces, commas…") into a list of @handles.
 *
 * Nothing is guessed: anything that is not clearly a handle, or a plain profile link, is reported back as
 * not understood instead of being tidied into something that might be somebody else's account.
 */
object HandleList {
    const val MAX_NAMES = 200

    /** [valid] are distinct (ignoring case) and without the @; [invalid] are the entries as written. */
    data class Parsed(val valid: List<String>, val invalid: List<String>) {
        val tooMany: Boolean get() = valid.size > MAX_NAMES
    }

    private val HANDLE = Regex("^[A-Za-z0-9_]{1,15}$")
    private val SEPARATORS = Regex("""[\s,;]+""")
    private val PROFILE_LINK = Regex("""^(?:https?://)?(?:www\.|mobile\.)?(?:x|twitter)\.com/([^/?#]+)/?(?:[?#].*)?$""", RegexOption.IGNORE_CASE)

    /** First path parts of x.com that are pages, not people ("x.com/i/chat/…" must never read as a user called "i"). */
    private val RESERVED = setOf(
        "i", "home", "explore", "search", "settings", "messages", "notifications", "intent", "share",
        "hashtag", "compose", "login", "signup", "tos", "privacy",
    )

    fun parse(text: String): Parsed {
        val valid = LinkedHashMap<String, String>()
        val invalid = LinkedHashSet<String>()
        for (raw in text.split(SEPARATORS)) {
            if (raw.isBlank()) continue
            val handle = handleOf(raw)
            if (handle == null) invalid += raw else valid.putIfAbsent(handle.lowercase(), handle)
        }
        return Parsed(valid.values.toList(), invalid.toList())
    }

    /** One entry as a handle, or null. Accepts `name`, `@name` and `x.com/name` (or twitter.com) profile links. */
    fun handleOf(entry: String): String? {
        val token = entry.trim()
        val fromLink = PROFILE_LINK.matchEntire(token)?.groupValues?.get(1)
        val candidate = (fromLink ?: token).removePrefix("@")
        if (fromLink != null && candidate.lowercase() in RESERVED) return null
        return candidate.takeIf { HANDLE.matches(it) }
    }
}
