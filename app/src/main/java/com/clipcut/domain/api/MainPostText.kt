package com.clipcut.domain.api

/** Builds the main-account post text: caption, a newline, then the media post's embed link last. */
object MainPostText {
    private const val X_POST_CHAR_LIMIT = 280

    /**
     * The caption is trimmed to `280 - url.length - 1` (the 1 being the newline) so the URL is
     * never the thing that overflows and gets cut. The 280 cap is deliberate: main-account posts
     * are kept short even though the account could take more.
     */
    fun build(caption: String, url: String): String {
        val budget = (X_POST_CHAR_LIMIT - url.length - 1).coerceAtLeast(0)
        return "${caption.take(budget).trimEnd()}\n$url"
    }
}
