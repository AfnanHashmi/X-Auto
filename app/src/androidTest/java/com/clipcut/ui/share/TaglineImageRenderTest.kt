package com.clipcut.ui.share

import android.graphics.Bitmap
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.clipcut.domain.share.taglineShareCard
import com.clipcut.domain.taglines.CountReport
import com.clipcut.domain.taglines.CountSeries
import com.clipcut.domain.taglines.HOUR_MS
import com.clipcut.domain.taglines.HourBucket
import com.clipcut.domain.taglines.TaglineCount
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.time.ZoneId

/**
 * Renders the Tagline Counter share image for one, three (one of them empty) and ten taglines, and writes the PNGs to the
 * app's external files folder (share-check/) so they can be pulled and looked at. Run with `am instrument`, which
 * does not uninstall the app the way connectedAndroidTest does.
 */
@RunWith(AndroidJUnit4::class)
class TaglineImageRenderTest {
    private val zone = ZoneId.of("Asia/Kolkata")
    private val fetched = 1_792_170_839_216L
    private val end = fetched - 30_000L
    private val start = end - 24 * HOUR_MS

    private fun tagline(name: String, peakHour: Int, scale: Long): TaglineCount {
        val buckets = (0..24).map { i ->
            val d = (i - peakHour).toLong()
            val v = if (i < peakHour - 3 || i > peakHour + 6) 0L else scale * 100 / (1 + d * d)
            HourBucket(if (i == 0) start else Math.floorDiv(start, HOUR_MS) * HOUR_MS + i * HOUR_MS, v)
        }
        val total = buckets.sumOf { it.count }
        return TaglineCount(name, CountSeries(total, buckets), CountSeries(total * 12 / 100, emptyList()))
    }

    private fun save(name: String, bmp: Bitmap) {
        val ctx = InstrumentationRegistry.getInstrumentation().targetContext
        val dir = File(ctx.getExternalFilesDir(null), "share-check").apply { mkdirs() }
        File(dir, name).outputStream().use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }

    @Test
    fun renderOneThreeAndTen() {
        val one = CountReport(start, end, fetched, listOf(tagline("QAZI BORN TO SHINE", 13, 180)))
        save("one.png", TaglineImage.render(taglineShareCard(one, "17 Oct 2026, 2:04 PM", zone, mapOf("QAZI BORN TO SHINE" to 2)), zone))
        save("one_plain.png", TaglineImage.render(taglineShareCard(one, "17 Oct 2026, 2:04 PM", zone), zone))

        val three = CountReport(start, end, fetched, listOf(
            tagline("STOP NEGATIVE PR AGAINST QAZI", 12, 300),
            tagline("QAZI RISING ABOVE HATE", 13, 220),
            tagline("NO NAKUL NO BB", 0, 0),
        ))
        save("three.png", TaglineImage.render(taglineShareCard(three, "17 Oct 2026, 2:04 PM", zone, mapOf("QAZI RISING ABOVE HATE" to 4)), zone))

        val check = com.clipcut.domain.taglines.TrendCheckResult(
            "STOP NEGATIVE PR AGAINST QAZI", start, end, true, end,
            com.clipcut.domain.taglines.CountShares(1000, 100, 20, 10), emptyList(), com.clipcut.domain.taglines.LaunchType.SUDDEN, emptyList(),
            stats = com.clipcut.domain.taglines.SampleStats(200, 90, 38, 22, 41, 17, 30, 12, 25, 0, 70, emptyList(), emptyList(), emptyList(), emptyList(), null, 10),
        )
        val readout = com.clipcut.domain.taglines.TaglineAnalysis(
            "Qazi tagline leads with 81,727 tweets (58% share)",
            listOf("Qazi leads #2 by 21,793 tweets; 58% vs 42% vs 0% split", "Qazi most retweet-driven: 88% RTs (7.3 per original) vs 88%", "Qazi peaked hardest: 30,000 tweets in one hour (500/min) vs 22,000", "extra"),
            "Fading", "Angle", "xAI · grok-4.3", 0.004,
        )
        save("three_ai.png", TaglineImage.render(taglineShareCard(three, "17 Oct 2026, 2:04 PM", zone, mapOf("QAZI RISING ABOVE HATE" to 4), checks = mapOf(check.tagline to check), readout = readout), zone))

        val ten = CountReport(start, end, fetched, (1..10).map { tagline("A VERY LONG TAGLINE NUMBER $it FOR THE SHOW THAT KEEPS ON GOING AND GOING", 2 + it * 2, 50L * it) })
        val bmp = TaglineImage.render(taglineShareCard(ten, "17 Oct 2026, 2:04 PM", zone, mapOf(ten.counts[3].tagline to 1)), zone)
        save("ten.png", bmp)
        assertEquals(1200, bmp.width)
    }
}
