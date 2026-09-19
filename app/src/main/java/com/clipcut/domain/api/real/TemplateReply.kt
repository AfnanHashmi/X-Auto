package com.clipcut.domain.api.real

/** The enhanced pieces of a templated post. An entry is null when that part was not asked for. */
internal data class TemplateReply(val heading: String?, val caption: String?)

private val HEADING_MARKER = Regex("(?m)^[ \\t]*HEADING:[ \\t]*")
private val CAPTION_MARKER = Regex("(?m)^[ \\t]*CAPTION:[ \\t]*")

/**
 * Reads the model's `HEADING: ...` / `CAPTION: ...` reply. A part that was requested but is missing
 * or empty is an error, never a silent blank, so a bad reply can't wipe the user's text.
 * [fixedLabel] is defensively stripped from the heading in case the model repeats it.
 */
internal fun parseTemplateReply(reply: String, wantHeading: Boolean, wantCaption: Boolean, fixedLabel: String): TemplateReply {
    val h = HEADING_MARKER.find(reply)
    val c = CAPTION_MARKER.find(reply)

    fun sliceAfter(marker: MatchResult?, other: MatchResult?): String? {
        marker ?: return null
        val end = other?.takeIf { it.range.first > marker.range.last }?.range?.first ?: reply.length
        return reply.substring(marker.range.last + 1, end).trim()
    }

    val heading = sliceAfter(h, c)
        ?.replace(Regex("\\s*\\n\\s*"), " ")
        ?.let { stripLabel(it, fixedLabel) }
    val caption = sliceAfter(c, h)

    if (wantHeading && heading.isNullOrBlank()) error("The AI reply had no heading")
    if (wantCaption && caption.isNullOrBlank()) error("The AI reply had no caption")
    return TemplateReply(
        heading = if (wantHeading) heading else null,
        caption = if (wantCaption) caption else null,
    )
}

private fun stripLabel(heading: String, fixedLabel: String): String {
    val label = fixedLabel.trim().trimEnd('-').trim()
    var out = heading
    if (label.isNotEmpty() && out.startsWith(label, ignoreCase = true)) {
        out = out.substring(label.length).trimStart(' ', '-', '–', '—', ':')
    }
    return out.trim()
}
