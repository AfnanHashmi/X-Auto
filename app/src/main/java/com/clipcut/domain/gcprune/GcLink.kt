package com.clipcut.domain.gcprune

import java.net.URI

/**
 * Reads a group chat's id out of what the user pastes.
 *
 * Only a GROUP id is ever accepted (`g` followed by digits, the documented group form). A one-to-one chat
 * id (digits, or digits-digits) is rejected on purpose: this feature removes members from groups, and
 * being forgiving about the id is how the wrong conversation gets touched.
 */
object GcLink {
    private val GROUP_ID = Regex("^g[0-9]{1,19}$")
    private val HOSTS = setOf("x.com", "www.x.com", "twitter.com", "www.twitter.com", "mobile.twitter.com")

    fun isValidGroupId(id: String): Boolean = GROUP_ID.matches(id)

    /**
     * The group id in a bare id or an `x.com/i/chat/<id>` link (also twitter.com, with or without a
     * scheme, query or trailing slash), or null if [input] is neither. The link must be exactly that
     * shape: any other host or path is not guessed at.
     */
    fun parseGroupId(input: String): String? {
        val text = input.trim()
        if (text.isEmpty()) return null
        if (isValidGroupId(text)) return text
        val uri = try {
            URI(if ("://" in text) text else "https://$text")
        } catch (e: Exception) {
            return null
        }
        if (uri.host?.lowercase() !in HOSTS) return null
        val parts = uri.path.orEmpty().split('/').filter { it.isNotEmpty() }
        if (parts.size != 3 || parts[0] != "i" || parts[1] != "chat") return null
        return parts[2].takeIf(::isValidGroupId)
    }
}
