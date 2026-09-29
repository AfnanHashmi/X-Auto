package com.clipcut.domain.api.real

import com.clipcut.domain.api.CaptionMood
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CaptionPromptsTest {
    @Test
    fun autoIsTheDefault() {
        assertEquals(CaptionMood.AUTO, CaptionMood.Default)
    }

    @Test
    fun exactlyTheNinePresetsExistInTheSpecifiedOrder() {
        assertEquals(
            listOf(
                "Dialogue", "Fight", "Emotional / Cute", "Fun", "Bashing", "Appreciation", "Savage", "Victory", "Shock",
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
        assertFalse(fight.contains(CaptionMood.EMOTIONAL.instructions.trim()))
        assertFalse(fight.contains("Mood: Emotional"))
        assertFalse(fight.contains("DIALOGUE MODE"))
    }

    @Test
    fun specValuesAreExactForASample() {
        assertEquals(10, CaptionMood.SAVAGE.intensity)
        assertEquals(2, CaptionMood.DIALOGUE.emojiLevel)
        assertTrue(CaptionMood.EMOTIONAL.instructions.contains("Cute"))
    }

    @Test
    fun dialoguePolishesAndNeverRewrites() {
        val p = buildPrompt(CaptionMood.DIALOGUE)
        assertTrue(p.contains("DIALOGUE MODE -- POLISH, DO NOT REWRITE."))
        // It must explicitly beat the master prompt's "rewrite, don't polish" rule, which comes first.
        assertTrue(p.indexOf("DIALOGUE MODE") > p.indexOf("1. REWRITE, DON'T POLISH"))
        assertTrue(p.contains("This overrides every rewrite instruction"))
        assertTrue(p.contains("Change, replace, reorder, add or remove any spoken word"))
        assertTrue(p.contains("Translate between Hindi and English"))
    }

    @Test
    fun dialogueUsesTheSpeakerQuoteShapeAndContestantLabelsWhenUnnamed() {
        val p = buildPrompt(CaptionMood.DIALOGUE)
        assertTrue(p.contains("SPEAKER: \"What they said\""))
        assertTrue(p.contains("CONTESTANT A: \"Hi there!!\""))
        assertTrue(p.contains("CONTESTANT B: \"Hello, brother.\""))
        assertTrue(p.contains("QAZI: \"Kya bol raha hai tu?!\""))
        // The OUTPUT section forbids quoting the caption; dialogue must be exempt or the lines lose their quotes.
        assertTrue(p.contains("quotation marks around each spoken line are required"))
    }

    @Test
    fun dialogueInATemplateNeverRewritesLinesToAvoidRepeatingTheHeading() {
        val t = buildPrompt(CaptionMood.DIALOGUE, templateMode = true)
        assertTrue(t.contains("the spoken\n  lines are never changed to avoid repeating the heading"))
    }

    @Test
    fun autoLooksBeyondThePresetsAndDoesNotSayWhichVoice() {
        val p = buildPrompt(CaptionMood.AUTO)
        assertTrue(p.startsWith("You are an expert X (Twitter) caption editor"))
        assertTrue(p.contains("Mood: Auto"))
        assertTrue(p.contains("You are NOT limited to a fixed list of moods."))
        assertTrue(p.contains("READ THE MOMENT"))
        assertTrue(p.contains("dry sarcasm"))
        assertTrue(p.contains("MATCH THE SCALE TO THE MOMENT"))
        assertTrue(p.contains("Do not tell the user which voice you chose."))
        assertFalse(p.contains("Intensity:"))
    }

    @Test
    fun autoHandsDialogueToTheDialogueRules() {
        val p = buildPrompt(CaptionMood.AUTO)
        assertTrue(p.contains("DIALOGUE IS A SPECIAL CASE"))
        assertTrue(p.contains(CaptionMood.DIALOGUE.instructions.trim()))
        assertTrue(p.trimEnd().endsWith("It must not change WHAT actually happened."))
    }

    @Test
    fun noCharacterLimitIsStatedAnywhere() {
        // Premium accounts post well past 280 chars, and the app no longer truncates either
        // (MainPostText.build/captionOnly), so the model must not be told to cut itself short.
        for (mood in listOf(CaptionMood.FIGHT, CaptionMood.AUTO)) {
            val p = buildPrompt(mood)
            assertTrue(p.contains("no character limit", ignoreCase = true))
            assertFalse(p.contains("220"))
        }
        assertFalse(buildPrompt(CaptionMood.FIGHT, templateMode = true).contains("150"))
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
    fun templateModeForbidsTheCaptionFromRestatingTheHeading() {
        // Heading and caption used to open with the same hook, so the post said the same thing twice.
        val t = buildPrompt(CaptionMood.FIGHT, templateMode = true)
        assertTrue(t.contains("HEADING AND CAPTION ARE ONE POST"))
        assertTrue(t.contains("must NEVER restate the heading"))
        assertFalse(buildPrompt(CaptionMood.FIGHT).contains("HEADING AND CAPTION ARE ONE POST"))
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
