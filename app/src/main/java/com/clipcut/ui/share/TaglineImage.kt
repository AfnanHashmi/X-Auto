package com.clipcut.ui.share

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.Typeface
import android.text.TextPaint
import android.text.TextUtils
import com.clipcut.domain.share.TaglineShareCard
import com.clipcut.domain.share.TaglineStat
import com.clipcut.domain.taglines.clockText
import java.time.ZoneId

/**
 * The Tagline Counter share image: 1200 wide, as tall as the taglines need. A "Tweets per hour" graph at the top, then one
 * card per tagline (total, original vs retweets, busiest hour and speed, first seen and time active). With two or more
 * taglines each card also has its rank, its share of all tagline tweets and a comparison bar. Trending positions appear
 * only where the user typed one.
 */
object TaglineImage {
    private const val W = 1200
    private const val M = 60f
    private const val CARD_PAD = 24f
    private const val CARD_GAP = 16f
    private const val DETAIL_STEP = 34f

    /** One colour per tagline (in report order), shared by its graph line and its card. */
    val SERIES = intArrayOf(
        Color.rgb(255, 196, 61), Color.rgb(120, 180, 255), Color.rgb(255, 128, 110), Color.rgb(110, 210, 150), Color.rgb(190, 150, 255),
        Color.rgb(255, 150, 210), Color.rgb(90, 220, 230), Color.rgb(230, 230, 120), Color.rgb(255, 170, 90), Color.rgb(170, 200, 140),
    )
    private val MEDAL = intArrayOf(Color.rgb(242, 192, 55), Color.rgb(192, 198, 210), Color.rgb(205, 127, 50))
    private val MUTED = Color.rgb(190, 196, 215)
    private val GOLD_TEXT = Color.rgb(40, 28, 0)

    private fun paint(size: Float, bold: Boolean, color: Int) = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
        textSize = size; this.color = color; typeface = if (bold) Typeface.DEFAULT_BOLD else Typeface.DEFAULT
    }

    private fun color(i: Int) = SERIES[i % SERIES.size]

    private fun hasSecondRow(s: TaglineStat) = s.trending != null || s.shareText != null

    private const val LOOK_STEP = 32f
    private const val TEXT_W = W - 2 * M - 16f

    private fun detailPaint() = paint(24f, false, Color.rgb(222, 226, 238))
    private fun lookPaint() = paint(23f, false, Color.rgb(200, 206, 222))
    private fun voicePaint() = paint(23f, true, Color.rgb(150, 180, 255))

    /** Greedy word wrap to [width], at most [maxLines] (the last one ellipsized). */
    private fun wrap(text: String, p: TextPaint, width: Float, maxLines: Int): List<String> {
        val out = mutableListOf<String>()
        var line = ""
        for (word in text.split(' ').filter { it.isNotEmpty() }) {
            val next = if (line.isEmpty()) word else "$line $word"
            if (p.measureText(next) <= width || line.isEmpty()) line = next else { out += line; line = word }
        }
        if (line.isNotEmpty()) out += line
        if (out.size <= maxLines) return out.map { TextUtils.ellipsize(it, p, width, TextUtils.TruncateAt.END).toString() }
        val kept = out.take(maxLines).toMutableList()
        kept[maxLines - 1] = TextUtils.ellipsize(kept[maxLines - 1] + " " + out.drop(maxLines).joinToString(" "), p, width, TextUtils.TruncateAt.END).toString()
        return kept
    }

    /** The Deep look lines under a card's figures: a label, up to three themes, then the top voices. */
    private fun lookRows(s: TaglineStat): List<Pair<String, TextPaint>> {
        if (!s.hasLook) return emptyList()
        val rows = mutableListOf<Pair<String, TextPaint>>()
        rows += "WHAT PEOPLE ARE SAYING" + (s.samplePosts?.let { "  ·  Grok read $it posts" } ?: "") to paint(19f, true, Color.rgb(150, 180, 255))
        s.themes.forEach { t -> wrap("• $t", lookPaint(), TEXT_W, 2).forEach { rows += it to lookPaint() } }
        if (s.voices.isNotEmpty()) wrap("Top voices: " + s.voices.joinToString("  "), voicePaint(), TEXT_W, 2).forEach { rows += it to voicePaint() }
        return rows
    }

    private fun cardHeight(s: TaglineStat, zone: ZoneId): Float {
        val look = lookRows(s)
        return CARD_PAD + 44f + (if (hasSecondRow(s)) 48f else 0f) + (if (s.fraction != null) 22f else 0f) + s.detailLines(zone).size * DETAIL_STEP +
            (if (look.isEmpty()) 0f else 18f + look.size * LOOK_STEP) + CARD_PAD - 8f
    }

    /** The AI read-out box: headline, then up to three findings, each wrapped to two lines at most. */
    private fun readoutRows(card: TaglineShareCard): List<Pair<String, TextPaint>> {
        if (card.readout.isEmpty()) return emptyList()
        val rows = mutableListOf<Pair<String, TextPaint>>()
        rows += "AI READ-OUT" to paint(19f, true, Color.rgb(150, 180, 255))
        wrap(card.readout.first(), paint(28f, true, Color.WHITE), TEXT_W - 16f, 2).forEach { rows += it to paint(28f, true, Color.WHITE) }
        card.readout.drop(1).forEach { r -> wrap("• $r", lookPaint(), TEXT_W - 16f, 2).forEach { rows += it to lookPaint() } }
        return rows
    }

    private fun readoutHeight(rows: List<Pair<String, TextPaint>>): Float =
        if (rows.isEmpty()) 0f else 2 * CARD_PAD + rows.sumOf { (it.second.textSize * 1.4f).toDouble() }.toFloat() + CARD_GAP

    fun render(card: TaglineShareCard, zone: ZoneId): Bitmap {
        val graphH = if (card.graph != null) 360f else 0f
        val readout = readoutRows(card)
        val readoutTop = 175f + graphH
        val cardsTop = readoutTop + readoutHeight(readout)
        val cardsH = card.stats.sumOf { (cardHeight(it, zone) + CARD_GAP).toDouble() }.toFloat()
        val noteH = if (card.aiNote != null) 34f else 0f
        val h = (cardsTop + cardsH + 80f + noteH).toInt().coerceAtLeast(675)

        val bmp = Bitmap.createBitmap(W, h, Bitmap.Config.ARGB_8888)
        val cv = Canvas(bmp)
        cv.drawRect(0f, 0f, W.toFloat(), h.toFloat(), Paint().apply { shader = LinearGradient(0f, 0f, W.toFloat(), h.toFloat(), Color.rgb(16, 22, 40), Color.rgb(40, 22, 58), Shader.TileMode.CLAMP) })

        cv.drawText(card.title, M, 95f, paint(54f, true, Color.WHITE))
        cv.drawText(card.subtitle, M, 140f, paint(28f, false, MUTED))

        card.graph?.let { drawGraph(cv, card, zone, top = 175f) }

        if (readout.isNotEmpty()) {
            val boxH = readoutHeight(readout) - CARD_GAP
            cv.drawRoundRect(RectF(M - 16f, readoutTop, W - M + 16f, readoutTop + boxH), 22f, 22f, Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.argb(46, 143, 168, 255) })
            var ry = readoutTop + CARD_PAD
            readout.forEach { (text, p) ->
                ry += p.textSize * 1.4f
                cv.drawText(text, M + 8f, ry - p.textSize * 0.4f, p)
            }
        }

        var y = cardsTop
        card.stats.forEach { s ->
            val ch = cardHeight(s, zone)
            drawCard(cv, s, zone, y, ch)
            y += ch + CARD_GAP
        }

        card.aiNote?.let { note ->
            val np = paint(19f, false, Color.rgb(150, 156, 176))
            cv.drawText(TextUtils.ellipsize(note, np, W - 2 * M, TextUtils.TruncateAt.END).toString(), M, h - 74f, np)
        }
        val footP = paint(22f, false, Color.rgb(170, 176, 196))
        cv.drawText(TextUtils.ellipsize(card.footer, footP, W - 2 * M, TextUtils.TruncateAt.END).toString(), M, h - 40f, footP)
        return bmp
    }

    private fun drawGraph(cv: Canvas, card: TaglineShareCard, zone: ZoneId, top: Float) {
        val g = card.graph ?: return
        val maxV = g.series.maxOf { s -> s.maxOrNull() ?: 0L }.coerceAtLeast(1L)
        cv.drawText("TWEETS PER HOUR", M, top + 26f, paint(24f, true, MUTED))
        val peakLabel = "Top line = ${"%,d".format(maxV)} in one hour"
        val peakP = paint(22f, false, MUTED)
        cv.drawText(peakLabel, W - M - peakP.measureText(peakLabel), top + 26f, peakP)

        val left = M
        val right = W - M
        val plotTop = top + 50f
        val plotBottom = top + 280f
        val grid = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.argb(45, 255, 255, 255); strokeWidth = 2f }
        for (i in 0..3) {
            val gy = plotTop + (plotBottom - plotTop) * i / 3f
            cv.drawLine(left, gy, right, gy, grid)
        }
        fun x(i: Int) = left + (right - left) * i / (g.hours - 1).toFloat()
        fun y(v: Long) = plotBottom - (plotBottom - plotTop) * (v.toFloat() / maxV)

        val single = g.series.size == 1
        g.series.forEachIndexed { si, series ->
            val path = Path()
            series.forEachIndexed { i, v -> if (i == 0) path.moveTo(x(i), y(v)) else path.lineTo(x(i), y(v)) }
            if (single) {
                val fill = Path(path).apply { lineTo(x(series.size - 1), plotBottom); lineTo(x(0), plotBottom); close() }
                cv.drawPath(fill, Paint(Paint.ANTI_ALIAS_FLAG).apply {
                    shader = LinearGradient(0f, plotTop, 0f, plotBottom, Color.argb(120, Color.red(color(si)), Color.green(color(si)), Color.blue(color(si))), Color.argb(0, 0, 0, 0), Shader.TileMode.CLAMP)
                })
            }
            cv.drawPath(path, Paint(Paint.ANTI_ALIAS_FLAG).apply {
                style = Paint.Style.STROKE; strokeWidth = 5f; color = color(si); strokeJoin = Paint.Join.ROUND; strokeCap = Paint.Cap.ROUND
            })
        }

        // About five time labels along the bottom, in the phone's time zone.
        val tickP = paint(22f, false, MUTED)
        val step = ((g.hours - 1) / 4).coerceAtLeast(1)
        val ticks = (0 until g.hours step step).toMutableList()
        if (ticks.last() != g.hours - 1 && g.hours - 1 - ticks.last() < step / 2) ticks[ticks.size - 1] = g.hours - 1 else if (ticks.last() != g.hours - 1) ticks += g.hours - 1
        ticks.forEach { i ->
            val label = clockText(g.hourStart(i), zone)
            val tw = tickP.measureText(label)
            val tx = (x(i) - tw / 2).coerceIn(left, right - tw)
            cv.drawText(label, tx, plotBottom + 34f, tickP)
        }
    }

    private fun drawCard(cv: Canvas, s: TaglineStat, zone: ZoneId, top: Float, height: Float) {
        cv.drawRoundRect(RectF(M - 16f, top, W - M + 16f, top + height), 22f, 22f, Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.argb(30, 255, 255, 255) })
        var x = M + 8f
        val row1 = top + CARD_PAD + 32f

        s.rank?.let { rank ->
            val cx = x + 22f
            val cy = row1 - 11f
            val badgeColor = MEDAL.getOrElse(rank - 1) { Color.argb(70, 255, 255, 255) }
            cv.drawCircle(cx, cy, 22f, Paint(Paint.ANTI_ALIAS_FLAG).apply { color = badgeColor })
            val rp = paint(22f, true, if (rank <= 3) GOLD_TEXT else Color.WHITE)
            val rt = s.rankText ?: ""
            cv.drawText(rt, cx - rp.measureText(rt) / 2, cy + 8f, rp)
            x += 56f
        }
        cv.drawCircle(x + 8f, row1 - 11f, 8f, Paint(Paint.ANTI_ALIAS_FLAG).apply { color = color(s.colorIndex) })
        x += 28f

        val valueP = paint(34f, true, Color.WHITE)
        val valueW = valueP.measureText(s.valueText)
        cv.drawText(s.valueText, W - M - 8f - valueW, row1, valueP)
        val nameP = paint(30f, true, Color.WHITE)
        val name = TextUtils.ellipsize(s.tagline, nameP, W - M - 8f - valueW - 24f - x, TextUtils.TruncateAt.END).toString()
        cv.drawText(name, x, row1, nameP)

        var y = row1
        if (hasSecondRow(s)) {
            y += 48f
            s.trendingText?.let { t ->
                val tp = paint(24f, true, GOLD_TEXT)
                val tw = tp.measureText(t)
                cv.drawRoundRect(RectF(M + 8f, y - 28f, M + 8f + tw + 32f, y + 10f), 19f, 19f, Paint(Paint.ANTI_ALIAS_FLAG).apply { color = MEDAL[0] })
                cv.drawText(t, M + 24f, y, tp)
            }
            s.shareText?.let { t ->
                val sp = paint(26f, false, MUTED)
                cv.drawText(t, W - M - 8f - sp.measureText(t), y, sp)
            }
        }
        s.fraction?.let { f ->
            y += 22f
            val barTop = y - 6f
            val track = RectF(M + 8f, barTop, W - M - 8f, barTop + 10f)
            cv.drawRoundRect(track, 5f, 5f, Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.argb(40, 255, 255, 255) })
            if (s.total > 0) {
                cv.drawRoundRect(RectF(track.left, barTop, track.left + track.width() * f.coerceIn(0.01f, 1f), barTop + 10f), 5f, 5f, Paint(Paint.ANTI_ALIAS_FLAG).apply { color = color(s.colorIndex) })
            }
        }
        val detailP = detailPaint()
        s.detailLines(zone).forEach { line ->
            y += DETAIL_STEP
            cv.drawText(TextUtils.ellipsize(line, detailP, TEXT_W, TextUtils.TruncateAt.END).toString(), M + 8f, y, detailP)
        }
        val look = lookRows(s)
        if (look.isNotEmpty()) {
            y += 18f
            cv.drawLine(M + 8f, y - 4f, W - M - 8f, y - 4f, Paint().apply { color = Color.argb(40, 255, 255, 255); strokeWidth = 1.5f })
            look.forEach { (text, p) ->
                y += LOOK_STEP
                cv.drawText(text, M + 8f, y, p)
            }
        }
    }
}
