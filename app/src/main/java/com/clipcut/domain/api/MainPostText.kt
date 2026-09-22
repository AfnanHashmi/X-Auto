package com.clipcut.domain.api

/** Builds the main-account post text: caption, a newline, then the media post's embed link last. */
object MainPostText {
    /** Caption, a newline, then the embed link. No length cap: the account's own limit applies on X. */
    fun build(caption: String, url: String): String = "${caption.trimEnd()}\n$url"

    /** Just the caption, for the standalone generator: exactly what was written, no cap. */
    fun captionOnly(caption: String): String = caption.trimEnd()
}
