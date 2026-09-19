package com.clipcut.domain.api

/**
 * Built-in post layouts. A layout has a FIXED label the AI must never touch, followed by a
 * user-chosen heading, a blank line, then the caption:
 *
 *   BB Episode Logs - <heading>
 *
 *   <caption>
 *
 * Only the heading and the caption are ever sent to the enhancer; the label is re-attached by the
 * app, so it cannot be reworded, dropped or duplicated by the model.
 */
enum class CaptionFormat(val label: String, val prefix: String?) {
    PLAIN("Plain", null),
    CHANNEL_LOGS("24Hrs Channel Logs", "BB 24Hrs Channel Logs - "),
    EPISODE_LOGS("Episode Logs", "BB Episode Logs - "),
}

/** One post split into its three parts. For [CaptionFormat.PLAIN], everything is in [body]. */
data class FormattedCaption(val format: CaptionFormat, val heading: String, val body: String)

object CaptionFormatText {
    /** Splits [text] by whichever layout label it starts with; text with no label is PLAIN. */
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

    /** The inverse of [parse]. Switching to PLAIN keeps any heading text rather than dropping it. */
    fun compose(format: CaptionFormat, heading: String, body: String): String {
        val prefix = format.prefix
        if (prefix != null) return "$prefix$heading\n\n$body"
        return if (heading.isBlank()) body else if (body.isBlank()) heading else "$heading\n\n$body"
    }
}
