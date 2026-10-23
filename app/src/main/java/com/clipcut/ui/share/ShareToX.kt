package com.clipcut.ui.share

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.Typeface
import android.text.TextUtils
import android.text.TextPaint
import androidx.core.content.FileProvider
import com.clipcut.domain.share.ShareContent
import com.clipcut.domain.share.TaglineShareCard
import java.io.File
import java.time.ZoneId
import com.clipcut.domain.GalleryStore

/**
 * Opens X's composer with the results image and the caption filled in. The user checks it and taps Post in X: the app
 * itself never posts. Falls back to the normal share sheet when the X app is not installed.
 */

object ShareToX {
    private const val X_PACKAGE = "com.twitter.android"

    /** [error] is set when X could not be opened; [savedToGallery] says whether the image is also in Pictures/X Auto. */
    data class Outcome(val error: String?, val savedToGallery: Boolean) {
        /** One line for the screen. */
        val message: String get() = error ?: if (savedToGallery) "Caption copied and image saved to Pictures/X Auto. For a thread: post your first tweet, tap + under it, paste, attach the image." else "Opened X. (The image could not be saved to the gallery.)"
    }

    fun share(context: Context, content: ShareContent, name: String = "boss-meter"): Outcome = share(context, render(content), content.caption, name)

    /** The Tagline Counter's detailed image (graph and one card per tagline) with its caption. */
    fun share(context: Context, card: TaglineShareCard, zone: ZoneId): Outcome = share(context, TaglineImage.render(card, zone), card.caption, "taglines")

    private fun share(context: Context, image: Bitmap, caption: String, name: String): Outcome {
        val file = File(File(context.cacheDir, "shared").apply { mkdirs() }, "result.png")
        file.outputStream().use { image.compress(Bitmap.CompressFormat.PNG, 100, it) }
        runCatching {
            val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
            clipboard.setPrimaryClip(android.content.ClipData.newPlainText("caption", caption))
        }
        val saved = runCatching { GalleryStore.saveImage(context, file, "x-auto-$name-${System.currentTimeMillis()}.png") }.getOrDefault(false)
        return Outcome(open(context, file, caption), saved)
    }

    private fun open(context: Context, file: File, caption: String): String? {
        val uri = FileProvider.getUriForFile(context, "${context.packageName}.files", file)
        val send = Intent(Intent.ACTION_SEND).apply {
            type = "image/png"
            putExtra(Intent.EXTRA_STREAM, uri)
            putExtra(Intent.EXTRA_TEXT, caption)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        return try {
            context.startActivity(Intent(send).setPackage(X_PACKAGE))
            null
        } catch (e: ActivityNotFoundException) {
            try {
                context.startActivity(Intent.createChooser(send, "Share results").addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION))
                null
            } catch (e: ActivityNotFoundException) {
                "No app on this phone can share an image."
            }
        }
    }

    /** A 1200 x 675 card (X's 16:9 preview size): title, one bar per row, footer. */
    fun render(c: ShareContent): Bitmap {
        val w = 1200
        val rows = c.rows.take(10)
        val h = 675
        val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val cv = Canvas(bmp)
        cv.drawRect(0f, 0f, w.toFloat(), h.toFloat(), Paint().apply { shader = LinearGradient(0f, 0f, w.toFloat(), h.toFloat(), Color.rgb(16, 22, 40), Color.rgb(40, 22, 58), Shader.TileMode.CLAMP) })

        fun paint(size: Float, bold: Boolean, color: Int) = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
            textSize = size; this.color = color; typeface = if (bold) Typeface.DEFAULT_BOLD else Typeface.DEFAULT
        }
        val m = 60f
        cv.drawText(c.title, m, 100f, paint(58f, true, Color.WHITE))
        cv.drawText(c.subtitle, m, 145f, paint(28f, false, Color.rgb(190, 196, 215)))
        val top = 185f
        val bottom = h - 90f
        val rowH = ((bottom - top) / rows.size.coerceAtLeast(1)).coerceAtMost(88f)
        val labelP = paint((rowH * 0.34f).coerceIn(18f, 30f), true, Color.WHITE)
        val valueP = paint((rowH * 0.30f).coerceIn(16f, 26f), false, Color.rgb(230, 232, 240))
        val track = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.argb(40, 255, 255, 255) }
        val bar = Paint(Paint.ANTI_ALIAS_FLAG)
        val colors = intArrayOf(Color.rgb(255, 196, 61), Color.rgb(120, 180, 255), Color.rgb(255, 128, 110), Color.rgb(110, 210, 150), Color.rgb(190, 150, 255))
        rows.forEachIndexed { i, r ->
            val y = top + i * rowH
            val valueW = valueP.measureText(r.value)
            val label = TextUtils.ellipsize(r.label, labelP, w - 2 * m - valueW - 30f, TextUtils.TruncateAt.END).toString()
            cv.drawText(label, m, y + labelP.textSize, labelP)
            cv.drawText(r.value, w - m - valueW, y + labelP.textSize, valueP)
            val barTop = y + labelP.textSize + 10f
            val barH = (rowH * 0.18f).coerceIn(8f, 16f)
            cv.drawRoundRect(RectF(m, barTop, w - m, barTop + barH), barH / 2, barH / 2, track)
            val barW = (w - 2 * m) * r.fraction.coerceIn(0.02f, 1f)
            bar.color = colors[i % colors.size]
            cv.drawRoundRect(RectF(m, barTop, m + barW, barTop + barH), barH / 2, barH / 2, bar)
        }
        val footP = paint(22f, false, Color.rgb(170, 176, 196))
        cv.drawText(TextUtils.ellipsize(c.footer, footP, w - 2 * m, TextUtils.TruncateAt.END).toString(), m, h - 45f, footP)
        return bmp
    }
}
