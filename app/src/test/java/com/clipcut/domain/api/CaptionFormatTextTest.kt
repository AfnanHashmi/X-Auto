package com.clipcut.domain.api

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Test

class CaptionFormatTextTest {
    @Test
    fun onlyThePlainAndChannelLogsLayoutsExist() {
        assertEquals(listOf("Plain", "24Hrs Channel Logs"), CaptionFormat.entries.map { it.label })
        assertEquals("BB 24Hrs Channel Logs - ", CaptionFormat.CHANNEL_LOGS.prefix)
        assertNull(CaptionFormat.PLAIN.prefix)
    }

    @Test
    fun composeIsLabelHeadingBlankLineCaption() {
        assertEquals(
            "BB 24Hrs Channel Logs - Kitchen war\n\nSab pe bhaari padi",
            CaptionFormatText.compose(CaptionFormat.CHANNEL_LOGS, "Kitchen war", "Sab pe bhaari padi"),
        )
    }

    @Test
    fun aPlainPostWithAHeadingIsHeadingBlankLineCaptionWithNoLabel() {
        assertEquals(
            "Kitchen war\n\nSab pe bhaari padi",
            CaptionFormatText.compose(CaptionFormat.PLAIN, "Kitchen war", "Sab pe bhaari padi"),
        )
    }

    @Test
    fun aPlainPostsHeadingIsOptional() {
        assertEquals("just a caption", CaptionFormatText.compose(CaptionFormat.PLAIN, "", "just a caption"))
        assertEquals("only a heading", CaptionFormatText.compose(CaptionFormat.PLAIN, "only a heading", ""))
        assertEquals("just a caption", CaptionFormatText.compose(CaptionFormat.PLAIN, "   ", "just a caption"))
    }

    @Test
    fun parseSplitsHeadingFromCaption() {
        val p = CaptionFormatText.parse("BB 24Hrs Channel Logs - Late night talk\n\nline one\nline two")
        assertEquals(CaptionFormat.CHANNEL_LOGS, p.format)
        assertEquals("Late night talk", p.heading)
        assertEquals("line one\nline two", p.body)
    }

    @Test
    fun parseThenComposeIsLosslessForALabelledPost() {
        for (text in listOf(
            "BB 24Hrs Channel Logs - A\n\nB",
            "BB 24Hrs Channel Logs - \n\n",
            "BB 24Hrs Channel Logs - Heading only\n\n",
            "BB 24Hrs Channel Logs - H\n\nmulti\n\nparagraph",
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
    fun aBlankLineInsideAPlainCaptionIsNeverMistakenForAHeading() {
        // The reason a plain post's parts are kept separately instead of being parsed out of one string:
        // splitting at the first blank line would move a paragraph into the heading box mid-typing.
        val p = CaptionFormatText.parse("first paragraph\n\nsecond paragraph")
        assertEquals("", p.heading)
        assertEquals("first paragraph\n\nsecond paragraph", p.body)
    }

    @Test
    fun switchingLayoutKeepsBothPartsBecauseThePartsAreNotRecomposed() {
        // Heading and caption survive a layout switch; only the label in front changes.
        assertEquals(
            "BB 24Hrs Channel Logs - Title\n\nBody",
            CaptionFormatText.compose(CaptionFormat.CHANNEL_LOGS, "Title", "Body"),
        )
        assertEquals("Title\n\nBody", CaptionFormatText.compose(CaptionFormat.PLAIN, "Title", "Body"))
    }

    @Test
    fun aRemovedLayoutsLabelIsNowJustPlainText() {
        val p = CaptionFormatText.parse("BB Episode Logs - Old saved template\n\nbody")
        assertEquals(CaptionFormat.PLAIN, p.format)
        assertEquals("BB Episode Logs - Old saved template\n\nbody", p.body)
    }

    @Test
    fun aSnapshotRoundTripsEveryPartExactly() {
        for (snap in listOf(
            CaptionSnapshot(CaptionFormat.PLAIN, "", "caption"),
            CaptionSnapshot(CaptionFormat.PLAIN, "Heading", "line one\n\nline two"),
            CaptionSnapshot(CaptionFormat.CHANNEL_LOGS, "H", ""),
            CaptionSnapshot(CaptionFormat.CHANNEL_LOGS, "", ""),
            CaptionSnapshot(CaptionFormat.PLAIN, "kya scene 🔥", "a \u0000 b"),
        )) {
            assertEquals(snap, CaptionSnapshot.decode(snap.encode()))
        }
    }

    @Test
    fun aSnapshotKeepsAPlainHeadingThatComposedTextCouldNot() {
        val snap = CaptionSnapshot(CaptionFormat.PLAIN, "Heading", "Body")
        val composed = CaptionFormatText.compose(snap.format, snap.heading, snap.body)
        // The composed text loses the split...
        assertEquals("", CaptionFormatText.parse(composed).heading)
        // ...the snapshot does not.
        assertEquals("Heading", CaptionSnapshot.decode(snap.encode())!!.heading)
    }

    @Test
    fun decodingAnythingThatIsNotASnapshotIsNullSoOldPlainTextFallsBack() {
        assertNull(CaptionSnapshot.decode("just old plain text"))
        assertNull(CaptionSnapshot.decode("NOT_A_FORMAT\u001Fa\u001Fb"))
        assertFalse(CaptionSnapshot.decode("PLAIN\u001Fa\u001Fb") == null)
    }
}
