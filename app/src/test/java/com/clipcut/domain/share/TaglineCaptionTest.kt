package com.clipcut.domain.share

import com.clipcut.domain.taglines.CountReport
import com.clipcut.domain.taglines.CountSeries
import com.clipcut.domain.taglines.HourBucket
import com.clipcut.domain.taglines.ReadoutRecord
import com.clipcut.domain.taglines.TaglineAnalysis
import com.clipcut.domain.taglines.TaglineCount
import com.clipcut.domain.taglines.fxCheck
import com.clipcut.domain.taglines.taglineFacts
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.ZoneId

private val IST = ZoneId.of("Asia/Kolkata")
private const val END = 1_792_271_838_261L
private const val START = END - 24 * 3_600_000L

private fun t(name: String, n: Long) = TaglineCount(name, CountSeries(n, listOf(HourBucket(END - 3_600_000L, n))), CountSeries(n / 10, emptyList()))
private fun report(vararg c: TaglineCount) = CountReport(START, END, END + 30_000, c.toList())

private val READOUT = TaglineAnalysis(
    "Qazi leads with 49,107 tweets", listOf("Leads #2 by 32,746", "Most retweet-driven", "Peaked hardest"),
    "Fading fast", "Frame the lead", "OpenAI · gpt-5.6-terra", 0.004, 1L,
)

class TaglineCaptionTest {
    private val r = report(t("QAZI THE POWERHOUSE OF BB20", 49_107), t("WE WANT NAKUL BACK", 16_361), t("UDITI STANDS BY HER PEOPLE", 13_902))
    private val checks = mapOf(
        "QAZI THE POWERHOUSE OF BB20" to fxCheck("QAZI THE POWERHOUSE OF BB20"),
        "WE WANT NAKUL BACK" to fxCheck("WE WANT NAKUL BACK", natural = 71, all = 16_361),
    )

    @Test
    fun aLongPostCarriesTheReadoutAndTheTrendCheckLinesAndTagsNobody() {
        val c = taglineShareCard(r, "18 Oct 2026, 9:47 PM", IST, readout = READOUT, checks = checks, stage = ShareStage.FINAL, longPost = true).caption
        assertTrue(c, c.startsWith("🏁 FINAL RESULT · Tagline battle on X\nFinal count, last 24 hours to 18 Oct 2026, 9:47 PM"))
        assertTrue(c.contains("🥇 QAZI THE POWERHOUSE OF BB20: 49,107 (62%)"))
        listOf("🧠 AI read-out", "Qazi leads with 49,107 tweets", "• Leads #2 by 32,746", "• Most retweet-driven", "• Peaked hardest", "Momentum: Fading fast").forEach { assertTrue(it, c.contains(it)) }
        assertFalse(c.contains("Frame the lead")) // the post angle is advice to you, not for the post
        assertTrue(c.contains("🔎 Trend check: QAZI THE POWERHOUSE OF BB20 (sample of 200 posts)"))
        assertTrue(c.contains("• ~38% natural · 22% bot-like · 41% copy-pasted (estimate)"))
        assertTrue(c.contains("• ~71% natural"))
        // Accounts found by a check are never tagged in a public post.
        assertFalse(c.contains("@fan_account_one")); assertFalse(c.contains("Top voices"))
        assertTrue(c.endsWith("Counted with the X API · Analysis by gpt-5.6-terra"))
    }

    @Test
    fun creditNamesEachModelAndTagsXaiOnlyForGrok() {
        assertEquals("Analysis by Grok 4.3 @xai", aiCredit(listOf("xAI · grok-4.3", "xAI · grok-4.3")))
        assertEquals("Analysis by Grok 4.3 & Grok 4.7 @xai", aiCredit(listOf("xAI · grok-4.3", "xAI · grok-4.7")))
        assertEquals("Analysis by gpt-5.6-terra", aiCredit(listOf("OpenAI · gpt-5.6-terra (xAI failed)")))
        assertNull(aiCredit(emptyList()))
    }

    @Test
    fun theReadoutIsGivenTheTrendCheckFiguresAndLabelsThemAsSampleOrExact() {
        val previous = ReadoutRecord(END - 6 * 3_600_000L, mapOf("QAZI THE POWERHOUSE OF BB20" to 30_000L), READOUT)
        val facts = taglineFacts(r, IST, checks, previous)
        assertTrue(facts.contains("TREND CHECK ("))
        assertTrue(facts.contains("- exact: 90% retweets, 2% replies, 1% quotes"))
        assertTrue(facts.contains("- sample of 200 posts (estimates): about 38% natural, 22% from bot-like accounts, 41% copy-pasted text"))
        assertTrue(facts.contains("- sentiment in the sample: 60% positive, 20% negative, 20% neutral"))
        assertTrue(facts.contains("PREVIOUS COUNT ("))
        assertTrue(facts.contains("\"QAZI THE POWERHOUSE OF BB20\" 30,000"))
        assertFalse(facts.contains("DEEP LOOK"))
    }
}
