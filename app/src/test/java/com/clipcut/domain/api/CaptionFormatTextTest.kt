package com.clipcut.domain.api

import org.junit.Assert.assertEquals
import org.junit.Test

class CaptionFormatTextTest {
    @Test
    fun theTwoLabelsAreExactlyAsSpecified() {
        assertEquals("BB 24Hrs Channel Logs - ", CaptionFormat.CHANNEL_LOGS.prefix)
        assertEquals("BB Episode Logs - ", CaptionFormat.EPISODE_LOGS.prefix)
    }

    @Test
    fun composeIsLabelHeadingBlankLineCaption() {
        assertEquals(
            "BB Episode Logs - Kitchen war\n\nSab pe bhaari padi",
            CaptionFormatText.compose(CaptionFormat.EPISODE_LOGS, "Kitchen war", "Sab pe bhaari padi"),
        )
    }

    @Test
    fun parseSplitsHeadingFromCaption() {
        val p = CaptionFormatText.parse("BB 24Hrs Channel Logs - Late night talk\n\nline one\nline two")
        assertEquals(CaptionFormat.CHANNEL_LOGS, p.format)
        assertEquals("Late night talk", p.heading)
        assertEquals("line one\nline two", p.body)
    }

    @Test
    fun parseThenComposeIsLossless() {
        for (text in listOf(
            "BB Episode Logs - A\n\nB",
            "BB Episode Logs - \n\n",
            "BB 24Hrs Channel Logs - Heading only\n\n",
            "BB Episode Logs - H\n\nmulti\n\nparagraph",
        )) {
            val p = CaptionFormatText.parse(text)
            assertEquals(text, CaptionFormatText.compose(p.format, p.heading, p.body))
        }
    }

    @Test
    fun textWithoutALabelIsPlainAndAllBody() {
        val p = CaptionFormatText.parse("just a caption\nwith two lines")
        assertEquals(CaptionFormat.PLAIN, p.format)
        assertEquals("", p.heading)
        assertEquals("just a caption\nwith two lines", p.body)
    }

    @Test
    fun switchingFormatKeepsWhatWasTyped() {
        val toTemplate = CaptionFormatText.compose(CaptionFormat.EPISODE_LOGS, "", "my caption")
        assertEquals("BB Episode Logs - \n\nmy caption", toTemplate)
        val p = CaptionFormatText.parse("BB Episode Logs - Title\n\nBody")
        assertEquals("Title\n\nBody", CaptionFormatText.compose(CaptionFormat.PLAIN, p.heading, p.body))
    }
}
