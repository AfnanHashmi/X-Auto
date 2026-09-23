package com.clipcut.domain.api.real

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Test

class TemplateReplyTest {
    private val label = "BB 24Hrs Channel Logs - "

    @Test
    fun readsBothPartsIncludingAMultiLineCaption() {
        val r = parseTemplateReply("HEADING: Kitchen war\nCAPTION: line one\nline two 🔥", true, true, label)
        assertEquals("Kitchen war", r.heading)
        assertEquals("line one\nline two 🔥", r.caption)
    }

    @Test
    fun onlyTheRequestedPartsAreReturned() {
        val r = parseTemplateReply("CAPTION: just the caption", wantHeading = false, wantCaption = true, fixedLabel = label)
        assertNull(r.heading)
        assertEquals("just the caption", r.caption)
    }

    @Test
    fun aRepeatedLabelInTheHeadingIsStripped() {
        val r = parseTemplateReply("HEADING: BB 24Hrs Channel Logs - Kitchen war\nCAPTION: x", true, true, label)
        assertEquals("Kitchen war", r.heading)
    }

    @Test
    fun aHeadingSplitOverLinesIsJoinedNotTruncated() {
        val r = parseTemplateReply("HEADING: Kitchen\nwar again\nCAPTION: x", true, true, label)
        assertEquals("Kitchen war again", r.heading)
    }

    @Test
    fun aMissingRequestedPartIsAnErrorNotABlank() {
        assertThrows(IllegalStateException::class.java) {
            parseTemplateReply("HEADING: only a heading", wantHeading = true, wantCaption = true, fixedLabel = label)
        }
        assertThrows(IllegalStateException::class.java) {
            parseTemplateReply("Sure! Here you go", wantHeading = true, wantCaption = true, fixedLabel = label)
        }
        assertThrows(IllegalStateException::class.java) {
            parseTemplateReply("HEADING: \nCAPTION: x", wantHeading = true, wantCaption = true, fixedLabel = label)
        }
    }
}
