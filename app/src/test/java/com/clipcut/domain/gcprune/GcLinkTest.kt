package com.clipcut.domain.gcprune

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class GcLinkTest {
    private val id = "g2096111685077696998"

    @Test
    fun readsTheIdOutOfAnXChatLink() {
        assertEquals(id, GcLink.parseGroupId("https://x.com/i/chat/$id"))
        assertEquals(id, GcLink.parseGroupId("https://twitter.com/i/chat/$id"))
        assertEquals(id, GcLink.parseGroupId("https://www.x.com/i/chat/$id/"))
        assertEquals(id, GcLink.parseGroupId("  https://x.com/i/chat/$id?s=20  "))
        assertEquals(id, GcLink.parseGroupId("x.com/i/chat/$id"))
        assertEquals(id, GcLink.parseGroupId("https://x.com/i/chat/$id#top"))
    }

    @Test
    fun acceptsABareGroupId() {
        assertEquals(id, GcLink.parseGroupId(id))
        assertEquals(id, GcLink.parseGroupId("  $id\n"))
    }

    @Test
    fun rejectsEverythingThatIsNotAGroupId() {
        assertNull(GcLink.parseGroupId(""))
        assertNull(GcLink.parseGroupId("hello"))
        assertNull(GcLink.parseGroupId("2096111685077696998")) // digits only: a one-to-one chat, not a group
        assertNull(GcLink.parseGroupId("123-456"))
        assertNull(GcLink.parseGroupId("g"))
        assertNull(GcLink.parseGroupId("G2096111685077696998")) // the prefix is lower-case g
        assertNull(GcLink.parseGroupId("g12345678901234567890")) // 20 digits
        assertNull(GcLink.parseGroupId("g123x"))
    }

    @Test
    fun rejectsLinksThatAreNotExactlyTheChatShape() {
        assertNull(GcLink.parseGroupId("https://x.com/i/chat/"))
        assertNull(GcLink.parseGroupId("https://x.com/messages/$id"))
        assertNull(GcLink.parseGroupId("https://x.com/i/chat/$id/extra"))
        assertNull(GcLink.parseGroupId("https://x.com/someone/i/chat/$id"))
        assertNull(GcLink.parseGroupId("https://x.com/i/chat/123456"))
    }

    @Test
    fun rejectsOtherHostsIncludingDisguisedOnes() {
        assertNull(GcLink.parseGroupId("https://example.com/i/chat/$id"))
        assertNull(GcLink.parseGroupId("https://x.com.evil.example/i/chat/$id"))
        assertNull(GcLink.parseGroupId("https://x.com@evil.example/i/chat/$id"))
        assertNull(GcLink.parseGroupId("https://notx.com/i/chat/$id"))
    }

    @Test
    fun validatesTheDocumentedGroupPattern() {
        assertTrue(GcLink.isValidGroupId("g1"))
        assertTrue(GcLink.isValidGroupId("g1234567890123456789"))
        assertFalse(GcLink.isValidGroupId("1"))
        assertFalse(GcLink.isValidGroupId("g"))
        assertFalse(GcLink.isValidGroupId("g1 "))
    }
}
