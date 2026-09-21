package com.clipcut.domain.api.real

import com.clipcut.domain.api.CaptionMood
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CaptionPromptsTest {
    @Test
    fun dialogueIsTheDefault() {
        assertEquals(CaptionMood.DIALOGUE, CaptionMood.Default)
    }

    @Test
    fun allFifteenMoodsExistInTheSpecifiedOrder() {
        assertEquals(
            listOf(
                "Dialogue", "Fight", "Singing", "Fun", "Bashing", "Expose", "Emotional", "Appreciation",
                "Savage", "Shock", "Cute", "Victory", "Frustration", "Confession", "Episode",
            ),
            CaptionMood.entries.filter { it != CaptionMood.AUTO }.map { it.label },
        )
    }

    @Test
    fun everyPromptHasTheMasterPromptAndItsOwnMoodBlock() {
        for (mood in CaptionMood.entries.filter { it != CaptionMood.AUTO }) {
            val p = buildPrompt(mood)
            assertTrue("master prompt missing for ${mood.label}", p.startsWith("You are an expert X (Twitter) caption editor"))
            assertTrue(p.contains("Mood: ${mood.label}\n"))
            assertTrue(p.contains("Intensity:\n${mood.intensity}/10"))
            assertTrue(p.contains("Emoji level:\n${mood.emojiLevel}/3"))
            assertTrue(p.contains(mood.instructions.trim()))
            assertTrue(p.trimEnd().endsWith("It must not change WHAT actually happened."))
        }
    }

    @Test
    fun aMoodBlockNeverLeaksIntoAnotherMood() {
        val fight = buildPrompt(CaptionMood.FIGHT)
        assertFalse(fight.contains(CaptionMood.CUTE.instructions.trim()))
        assertFalse(fight.contains("Mood: Cute"))
    }

    @Test
    fun specValuesAreExactForASample() {
        assertEquals(10, CaptionMood.SAVAGE.intensity)
        assertEquals(1, CaptionMood.CONFESSION.emojiLevel)
        assertEquals("headline + commentary", CaptionMood.EPISODE.structure)
    }

    @Test
    fun autoAsksTheModelToPickFromAllFifteenAndNotToSayWhich() {
        val p = buildPrompt(CaptionMood.AUTO)
        assertTrue(p.startsWith("You are an expert X (Twitter) caption editor"))
        assertTrue(p.contains("Mood: Auto"))
        for (mood in CaptionMood.entries.filter { it != CaptionMood.AUTO }) {
            assertTrue("Auto prompt should list ${mood.label}", p.contains("\n${mood.label}\n"))
        }
        assertTrue(p.contains("Do not tell the user which mood was selected."))
        assertFalse(p.contains("Intensity:"))
    }

    @Test
    fun theHardLengthLimitIsStatedInEveryPrompt() {
        // The app cuts the caption at 280 minus the media link, so an over-long reply is truncated
        // mid-word before it is posted. The limit has to reach the model, not just the clipboard.
        for (mood in listOf(CaptionMood.FIGHT, CaptionMood.AUTO)) {
            assertTrue(buildPrompt(mood).contains("220 characters"))
        }
    }

    @Test
    fun templateModeOverridesTheBareCaptionOutputRule() {
        // Without this the OUTPUT section ("return ONLY the caption") contradicts HEADING:/CAPTION:,
        // and parseTemplateReply throws on the missing part, surfacing as "Enhance failed".
        val t = buildPrompt(CaptionMood.FIGHT, templateMode = true)
        assertTrue(t.contains("OVERRIDES the OUTPUT section"))
        assertTrue(t.indexOf("TEMPLATE MODE") > t.indexOf("OUTPUT"))
    }

    @Test
    fun templateModeAddsTheHeadingRulesOnlyWhenAsked() {
        assertFalse(buildPrompt(CaptionMood.FIGHT).contains("TEMPLATE MODE"))
        val t = buildPrompt(CaptionMood.FIGHT, templateMode = true)
        assertTrue(t.contains("TEMPLATE MODE"))
        assertTrue(t.contains("NEVER write, repeat, reword or"))
        assertTrue(t.contains("HEADING: <the enhanced heading>"))
        assertTrue(t.contains("Mood: Fight"))
    }
}
