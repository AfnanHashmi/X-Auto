package com.clipcut.domain.share

import com.clipcut.domain.taglines.TaglineAnalysis

/** Whether the post shares a running score or the final result of a trend that has ended. Sets the heading. */
enum class ShareStage(val label: String, val heading: String, val imageTag: String) {
    LIVE("Live score", "📊 LIVE SCORE", "LIVE SCORE"),
    FINAL("Final result", "🏁 FINAL RESULT", "FINAL RESULT"),
}

/** X's limit for a long post (X Premium). Ordinary accounts get [X_POST_LIMIT]. */
const val X_LONG_POST_LIMIT = 25_000

/** At most this many accounts are @mentioned per tagline, and in all: many mentions in one post can look like spam to X. */
const val MENTIONS_PER_TAGLINE = 3
const val MENTIONS_TOTAL = 6

/**
 * "Grok 4.3 @xai" from labels like "xAI · grok-4.3" (several models are joined). An OpenAI fallback is named by its model
 * without a tag. Null when nothing was written by an AI.
 */
fun aiCredit(labels: List<String>): String? {
    val models = labels.mapNotNull { label ->
        val parts = label.substringBefore(" (").split(" · ")
        if (parts.size < 2) return@mapNotNull null
        val (provider, model) = parts[0].trim() to parts[1].trim()
        if (provider == "xAI" && model.startsWith("grok-")) "Grok " + model.removePrefix("grok-") else model
    }.distinct()
    if (models.isEmpty()) return null
    val grok = labels.any { it.startsWith("xAI") }
    return "Analysis by " + models.joinToString(" & ") + if (grok) " @xai" else ""
}

/**
 * The post text. Heading from [stage]; then the counts; with [longPost] (X Premium) also the whole AI read-out and each
 * Trend check lines, ending with which model wrote the analysis. Without [longPost] it is
 * cut to one ordinary post (280), keeping the heading, counts, the main voices and the credit.
 */
fun taglineCaption(
    stats: List<TaglineStat>,
    combined: Long,
    stage: ShareStage,
    dateText: String,
    readout: TaglineAnalysis?,
    aiLabels: List<String>,
    longPost: Boolean,
): String {
    val compares = stats.size >= 2
    val heading = if (compares) "${stage.heading} · Tagline battle on X" else "${stage.heading} · \"${stats.firstOrNull()?.tagline.orEmpty()}\" on X"
    val period = (if (stage == ShareStage.FINAL) "Final count, " else "") + "last 24 hours to $dateText"

    val countLines = if (compares) {
        stats.map { s ->
            val medal = MEDALS.getOrElse((s.rank ?: 0) - 1) { "${s.rank}." }
            "$medal ${s.tagline}: ${n(s.total)}" + (s.sharePercent?.let { " (${"%.0f".format(it)}%)" } ?: "") + (s.trending?.let { " · #$it Trending" } ?: "")
        } + "Total: ${n(combined)} tweets"
    } else {
        stats.firstOrNull()?.let { s ->
            listOfNotNull(
                "${n(s.total)} tweets" + (s.trending?.let { " · 🔥 #$it Trending on X" } ?: ""),
                s.takeIf { it.total > 0 }?.let { "${n(it.originals)} original, ${n(it.retweets)} retweets" },
            )
        }.orEmpty()
    }

    // Mentions: the top voices of each looked-at tagline, capped per tagline and in all, never the same account twice.
    val mentioned = LinkedHashSet<String>()
    val voicesByTagline = stats.associate { s ->
        s.tagline to s.voices.filter { h -> mentioned.size < MENTIONS_TOTAL && mentioned.none { it.equals(h, true) } }
            .take(MENTIONS_PER_TAGLINE).also { mentioned += it }
    }

    val credit = listOfNotNull("Counted with the X API", aiCredit(aiLabels)).joinToString(" · ")

    if (!longPost) {
        val voices = mentioned.take(3).takeIf { it.isNotEmpty() }?.let { "Top voices: " + it.joinToString(" ") }
        return fitCaption(heading, listOfNotNull(period) + countLines + listOfNotNull(voices, readout?.headline), credit)
    }

    val blocks = mutableListOf<String>()
    blocks += "$heading\n$period"
    blocks += countLines.joinToString("\n")
    readout?.let { a ->
        blocks += (listOf("🧠 AI read-out", a.headline) + a.insights.map { "• $it" } + "Momentum: ${a.momentum}").joinToString("\n")
    }
    stats.filter { it.hasLook }.forEach { s ->
        val lines = mutableListOf("🔎 Trend check: ${s.tagline}" + (s.samplePosts?.let { " (sample of $it posts)" } ?: ""))
        lines += s.themes.map { "• $it" }
        voicesByTagline[s.tagline]?.takeIf { it.isNotEmpty() }?.let { lines += "Top voices: " + it.joinToString(" ") }
        blocks += lines.joinToString("\n")
    }
    blocks += credit
    val text = blocks.joinToString("\n\n")
    return if (xLength(text) <= X_LONG_POST_LIMIT) text else text.take(X_LONG_POST_LIMIT - 1) + "…"
}

private val MEDALS = listOf("🥇", "🥈", "🥉")

private fun n(v: Long) = "%,d".format(v)
