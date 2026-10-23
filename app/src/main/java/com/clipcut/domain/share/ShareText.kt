package com.clipcut.domain.share

import com.clipcut.domain.bossmeter.BossMeterResult

/** X's post limit. */
const val X_POST_LIMIT = 280

/** Roughly how X weighs text: most characters count 1, CJK and emoji count 2. Errs on the long side. */
fun xLength(s: String): Int {
    var n = 0
    var i = 0
    while (i < s.length) {
        val cp = s.codePointAt(i)
        n += if (cp < 0x1100 || cp in 0x2000..0x200D || cp in 0x2010..0x201F) 1 else 2
        i += Character.charCount(cp)
    }
    return n
}

/** One line of a share image: a label, a value to print, and how full its bar is (0..1). */
data class ShareRow(val label: String, val value: String, val fraction: Float)

data class ShareContent(
    val title: String,
    val subtitle: String,
    val rows: List<ShareRow>,
    val footer: String,
    val caption: String,
)

private fun n(v: Long) = "%,d".format(v)

/**
 * Builds the caption from a header, body lines (most important first) and a footer. Lines are dropped from the end of the
 * body, with a "+N more" note, until it fits in one post.
 */
fun fitCaption(header: String, lines: List<String>, footer: String): String {
    for (keep in lines.size downTo 0) {
        val more = lines.size - keep
        val body = lines.take(keep) + if (more > 0) listOf("+$more more") else emptyList()
        val text = (listOf(header, "") + body + listOf("", footer)).joinToString("\n").trim()
        if (xLength(text) <= X_POST_LIMIT) return text
    }
    return header.take(X_POST_LIMIT)
}

private val MEDALS = listOf("🥇", "🥈", "🥉") // gold, silver, bronze

fun bossMeterShare(r: BossMeterResult, dateText: String): ShareContent {
    val ranked = r.ranked
    val top = ranked.firstOrNull()?.count?.coerceAtLeast(1L) ?: 1L
    val rows = ranked.map { ShareRow(it.tag, "${n(it.count)}  (${"%.1f".format(r.percent(it))}%)", it.count.toFloat() / top) }
    val lines = ranked.mapIndexed { i, t -> "${MEDALS.getOrElse(i) { "${i + 1}." }} ${t.tag}: ${n(t.count)} (${"%.1f".format(r.percent(t))}%)" }
    val caption = fitCaption(
        "🔥 Boss Meter : Update",
        lines + "Total votes counted: ${n(r.trackedSum)}",
        "Counted with the X API, $dateText",
    )
    return ShareContent(
        title = "BOSS METER",
        subtitle = "Share of hashtag votes in the thread",
        rows = rows,
        footer = "${n(r.trackedSum)} hashtag votes counted  ·  Counted with the X API  ·  $dateText",
        caption = caption,
    )
}
