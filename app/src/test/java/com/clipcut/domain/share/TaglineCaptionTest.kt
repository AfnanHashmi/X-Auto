package com.clipcut.domain.share

import com.clipcut.domain.taglines.CountReport
import com.clipcut.domain.taglines.CountSeries
import com.clipcut.domain.taglines.DeepLook
import com.clipcut.domain.taglines.HourBucket
import com.clipcut.domain.taglines.ReadoutRecord
import com.clipcut.domain.taglines.TaglineAnalysis
import com.clipcut.domain.taglines.TaglineCount
import com.clipcut.domain.taglines.Voice
import com.clipcut.domain.taglines.deepLookUserText
import com.clipcut.domain.taglines.regulars
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

private fun look(tagline: String, at: Long, vararg handles: String, changes: String = "") = DeepLook(
    tagline, 18, listOf("Fans defend him", "Powerhouse of the house"), handles.map { Voice(it, "100 likes") },
    listOf("https://x.com/a/status/1"), 0.10, "xAI · grok-4.3", at, changes, END + 30_000,
)

private val READOUT = TaglineAnalysis(
    "Qazi leads with 49,107 tweets", listOf("Leads #2 by 32,746", "Most retweet-driven", "Peaked hardest"),
    "Fading fast", "Frame the lead", "xAI · grok-4.3", 0.004, 1L,
)

class TaglineCaptionTest {
    private val r = report(t("QAZI THE POWERHOUSE OF BB20", 49_107), t("WE WANT NAKUL BACK", 16_361), t("UDITI STANDS BY HER PEOPLE", 13_902))
    private val looks = mapOf(
        "QAZI THE POWERHOUSE OF BB20" to look("QAZI THE POWERHOUSE OF BB20", 2L, "@fan_account_one", "@example_account", "@fan_account_three", "@_kudi_kashmiri"),
        "WE WANT NAKUL BACK" to look("WE WANT NAKUL BACK", 3L, "@fan_account_two", "@fan_account_one", "@a1", "@a2", "@a3"),
    )

    @Test
    fun aLongPostCarriesTheWholeReadoutTheLooksTaggedAndTheGrokCredit() {
        val c = taglineShareCard(r, "18 Oct 2026, 9:47 PM", IST, readout = READOUT, looks = looks, stage = ShareStage.FINAL, longPost = true).caption
        assertTrue(c, c.startsWith("🏁 FINAL RESULT · Tagline battle on X\nFinal count, last 24 hours to 18 Oct 2026, 9:47 PM"))
        assertTrue(c.contains("🥇 QAZI THE POWERHOUSE OF BB20: 49,107 (62%)"))
        // the whole read-out
        listOf("🧠 AI read-out", "Qazi leads with 49,107 tweets", "• Leads #2 by 32,746", "• Most retweet-driven", "• Peaked hardest", "Momentum: Fading fast").forEach { assertTrue(it, c.contains(it)) }
        assertFalse(c.contains("Frame the lead")) // the post angle is advice to you, not for the post
        // Deep looks, tagged: 3 per tagline, 6 in all, nobody twice
        assertTrue(c.contains("🔎 What fans are saying: QAZI THE POWERHOUSE OF BB20 (18 posts read)"))
        assertTrue(c.contains("Top voices: @fan_account_one @example_account @fan_account_three"))
        assertTrue(c.contains("Top voices: @fan_account_two @a1 @a2")) // @fan_account_one already tagged above
        assertEquals(6, Regex("@\\w+").findAll(c.substringBefore("Analysis by")).count())
        assertTrue(c.endsWith("Counted with the X API · Analysis by Grok 4.3 @xai"))
    }

    @Test
    fun liveScoreHeadingAndAShortPostStaysWithin280() {
        val c = taglineShareCard(r, "18 Oct 2026, 9:47 PM", IST, readout = READOUT, looks = looks, stage = ShareStage.LIVE, longPost = false).caption
        assertTrue(c, c.startsWith("📊 LIVE SCORE · Tagline battle on X"))
        assertTrue(xLength(c) <= X_POST_LIMIT)
        assertTrue(c.endsWith("@xai"))
        assertFalse(c.contains("🧠 AI read-out"))
    }

    @Test
    fun theImageIsLabelledLiveOrFinal() {
        assertTrue(taglineShareCard(r, "x", IST, stage = ShareStage.FINAL).subtitle.startsWith("FINAL RESULT"))
        assertTrue(taglineShareCard(r, "x", IST).subtitle.startsWith("LIVE SCORE"))
    }

    @Test
    fun creditNamesEachModelAndTagsXaiOnlyForGrok() {
        assertEquals("Analysis by Grok 4.3 @xai", aiCredit(listOf("xAI · grok-4.3", "xAI · grok-4.3")))
        assertEquals("Analysis by Grok 4.3 & Grok 4.7 @xai", aiCredit(listOf("xAI · grok-4.3", "xAI · grok-4.7")))
        assertEquals("Analysis by gpt-5.6-terra", aiCredit(listOf("OpenAI · gpt-5.6-terra (xAI failed)")))
        assertNull(aiCredit(emptyList()))
    }

    @Test
    fun earlierLooksFeedTheNextLookAndTheReadout() {
        val history = listOf(look("Q", 3L, "@a", "@b", changes = "@b is new"), look("Q", 2L, "@a", "@c"), look("q", 1L, "@A"))
        assertEquals(listOf("@a" to 3), regulars(history)) // only @a came up in two or more looks (case ignored)
        val text = deepLookUserText("Q", history)
        assertTrue(text.contains("EARLIER LOOKS")); assertTrue(text.contains("already read: https://x.com/a/status/1"))
        assertEquals("Tagline: \"Q\"", deepLookUserText("Q", emptyList()))

        val previous = ReadoutRecord(END - 6 * 3_600_000L, mapOf("QAZI THE POWERHOUSE OF BB20" to 30_000L), READOUT)
        val facts = taglineFacts(r, IST, looks, history = looks.values.toList() + looks.values.toList(), previous = previous)
        assertTrue(facts.contains("DEEP LOOK (Grok read 18 posts)"))
        assertTrue(facts.contains("- top voices: @fan_account_one (100 likes)"))
        assertTrue(facts.contains("steady top voices across looks: @fan_account_one (2 looks)"))
        assertTrue(facts.contains("PREVIOUS COUNT ("))
        assertTrue(facts.contains("\"QAZI THE POWERHOUSE OF BB20\" 30,000"))
    }
}
