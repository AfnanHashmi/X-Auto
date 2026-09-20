package com.clipcut.domain.api

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class MainPostTextTest {
    private val url = "https://x.com/dk_account/status/2100000000000000000/video/1"

    @Test
    fun captionThenNewlineThenEmbedLink() {
        assertEquals("Caption here\n$url", MainPostText.build("Caption here", url))
    }

    @Test
    fun linkIsTheEmbedFormNotTheRawPostLink() {
        assertTrue(MainPostText.build("x", url).endsWith("/video/1"))
    }

    @Test
    fun longCaptionIsTrimmedSoTheLinkIsNeverCut() {
        val text = MainPostText.build("a".repeat(1000), url)
        assertTrue(text.endsWith("\n$url"))
        assertEquals(280, text.length)
    }

    @Test
    fun trailingSpaceBeforeTheCutIsTrimmed() {
        val budget = 280 - url.length - 1
        val text = MainPostText.build("a".repeat(budget - 1) + " bbb", url)
        assertEquals("a".repeat(budget - 1) + "\n$url", text)
    }

    @Test
    fun captionOnlyHasNoLinkAndKeepsTheSameCap() {
        assertEquals("kya scene hai 🔥", MainPostText.captionOnly("kya scene hai 🔥"))
        assertEquals(280, MainPostText.captionOnly("a".repeat(1000)).length)
        assertEquals("trim me", MainPostText.captionOnly("trim me   "))
    }

    @Test
    fun hindiEnglishCaptionIsPreserved() {
        assertEquals("kya scene hai 🔥\n$url", MainPostText.build("kya scene hai 🔥", url))
    }
}
