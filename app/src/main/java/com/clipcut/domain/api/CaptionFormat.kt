package com.clipcut.domain.api

/**
 * Built-in post layouts. Every post has a heading and a caption; a layout may add a FIXED label in
 * front of the heading that the AI must never touch:
 *
 *   BB 24Hrs Channel Logs - <heading>
 *
 *   <caption>
 *
 * [PLAIN] is the same thing without the label (and its heading is optional). Only the heading and the
 * caption are ever sent to the enhancer; the label is re-attached by the app, so it cannot be
 * reworded, dropped or duplicated by the model.
 */
enum class CaptionFormat(val label: String, val prefix: String?) {
    PLAIN("Plain", null),
    CHANNEL_LOGS("24Hrs Channel Logs", "BB 24Hrs Channel Logs - "),
}

/** One post split into its three parts: the layout, the heading and the caption. */
data class FormattedCaption(val format: CaptionFormat, val heading: String, val body: String)

object CaptionFormatText {
    /**
     * Splits [text] by whichever layout label it starts with. Text with no label is PLAIN and is all
     * body: a plain post's heading is not recoverable from a single string (a blank line inside the
     * caption is not a heading), so it is never guessed at.
     */
    fun parse(text: String): FormattedCaption {
        for (format in CaptionFormat.entries) {
            val prefix = format.prefix ?: continue
            if (text.startsWith(prefix)) {
                val rest = text.removePrefix(prefix)
                val heading = rest.substringBefore('\n')
                val body = rest.substringAfter('\n', "").removePrefix("\n")
                return FormattedCaption(format, heading, body)
            }
        }
        return FormattedCaption(CaptionFormat.PLAIN, heading = "", body = text)
    }

    /**
     * The text that is copied and posted. A labelled layout is `label + heading`, a blank line, then the
     * caption. A plain one is just `heading`, a blank line, then the caption, and either part may be missing.
     */
    fun compose(format: CaptionFormat, heading: String, body: String): String {
        val prefix = format.prefix
        if (prefix != null) return "$prefix$heading\n\n$body"
        return if (heading.isBlank()) body else if (body.isBlank()) heading else "$heading\n\n$body"
    }
}

/**
 * An exact copy of the editable post, for Undo. It has to carry the parts separately: a plain post's
 * composed text cannot be split back into heading and caption (see [CaptionFormatText.parse]).
 */
data class CaptionSnapshot(val format: CaptionFormat, val heading: String, val body: String) {
    fun encode(): String = "${format.name}$SEPARATOR$heading$SEPARATOR$body"

    companion object {
        // ASCII "unit separator": a control character nobody types into a caption.
        private const val SEPARATOR = '\u001F'

        fun decode(text: String): CaptionSnapshot? {
            val parts = text.split(SEPARATOR, limit = 3)
            if (parts.size != 3) return null
            val format = CaptionFormat.entries.firstOrNull { it.name == parts[0] } ?: return null
            return CaptionSnapshot(format, parts[1], parts[2])
        }
    }
}
