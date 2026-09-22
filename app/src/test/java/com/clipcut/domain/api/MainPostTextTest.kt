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
    fun longCaptionIsNotTruncated() {
        // Premium accounts post well past 280 chars; the app no longer imposes its own cap.
        val long = "a".repeat(1000)
        val text = MainPostText.build(long, url)
        assertEquals("$long\n$url", text)
    }

    @Test
    fun trailingWhitespaceIsStillTrimmed() {
        assertEquals("caption\n$url", MainPostText.build("caption   ", url))
    }

    @Test
    fun captionOnlyHasNoLinkAndNoCap() {
        assertEquals("kya scene hai 🔥", MainPostText.captionOnly("kya scene hai 🔥"))
        assertEquals("a".repeat(1000), MainPostText.captionOnly("a".repeat(1000)))
        assertEquals("trim me", MainPostText.captionOnly("trim me   "))
    }

    @Test
    fun hindiEnglishCaptionIsPreserved() {
        assertEquals("kya scene hai 🔥\n$url", MainPostText.build("kya scene hai 🔥", url))
    }
}
