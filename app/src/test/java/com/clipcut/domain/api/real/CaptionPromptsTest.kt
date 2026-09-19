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
            CaptionMood.entries.map { it.label },
        )
    }

    @Test
    fun everyPromptHasTheMasterPromptAndItsOwnMoodBlock() {
        for (mood in CaptionMood.entries) {
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
}
