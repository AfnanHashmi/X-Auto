package com.clipcut.domain.gcprune

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class HandleListTest {
    @Test
    fun splitsOnNewLinesSpacesCommasAndSemicolons() {
        val p = HandleList.parse("a1\nb2  c3,d4;e5\r\n\tf6")
        assertEquals(listOf("a1", "b2", "c3", "d4", "e5", "f6"), p.valid)
        assertTrue(p.invalid.isEmpty())
    }

    @Test
    fun acceptsAtSignsAndProfileLinks() {
        val p = HandleList.parse("@one https://x.com/two http://www.twitter.com/Three/ x.com/four mobile.twitter.com/five?s=20 https://x.com/six#top")
        assertEquals(listOf("one", "two", "Three", "four", "five", "six"), p.valid)
        assertTrue(p.invalid.isEmpty())
    }

    @Test
    fun duplicatesIgnoringCaseAreDroppedKeepingTheFirstSpelling() {
        assertEquals(listOf("Ana", "bo"), HandleList.parse("Ana @ana ANA bo @BO").valid)
    }

    @Test
    fun anythingThatIsNotClearlyAHandleIsReportedNotTidied() {
        val p = HandleList.parse("good bad-name has.dot toolonghandle_12345 @ ??? x.com/a/status/12 https://evil.example/ana")
        assertEquals(listOf("good"), p.valid)
        assertEquals(
            listOf("bad-name", "has.dot", "toolonghandle_12345", "@", "???", "x.com/a/status/12", "https://evil.example/ana"),
            p.invalid,
        )
    }

    @Test
    fun theLengthLimitIsFifteenCharactersAndUnderscoresAndDigitsAreAllowed() {
        assertEquals(listOf("abcdefghijklmno", "a_1"), HandleList.parse("abcdefghijklmno a_1").valid)
        assertEquals(listOf("abcdefghijklmnop"), HandleList.parse("abcdefghijklmnop").invalid)
    }

    @Test
    fun pageLinksThatAreNotProfilesNeverBecomeAUserCalledI() {
        // A group link pasted by mistake must not turn into a lookup (and a removal) of "@i".
        val p = HandleList.parse("https://x.com/i/chat/g2096111685077696998 https://x.com/home https://x.com/intent/follow")
        assertTrue(p.valid.isEmpty())
        assertEquals(3, p.invalid.size)
    }

    @Test
    fun aLinkOnAnotherSiteIsNotAProfileLink() {
        assertTrue(HandleList.parse("https://notx.com/ana https://x.com.evil.example/ana https://evil.example/x.com/ana").valid.isEmpty())
    }

    @Test
    fun emptyAndBlankTextGivesNothing() {
        assertTrue(HandleList.parse("").valid.isEmpty())
        assertTrue(HandleList.parse(" \n , ; \t").let { it.valid.isEmpty() && it.invalid.isEmpty() })
    }

    @Test
    fun moreThanTheMaximumIsFlagged() {
        val many = (1..HandleList.MAX_NAMES + 1).joinToString(" ") { "n$it" }
        assertTrue(HandleList.parse(many).tooMany)
        assertFalse(HandleList.parse((1..HandleList.MAX_NAMES).joinToString(" ") { "n$it" }).tooMany)
    }

    @Test
    fun handleOfReadsOneEntry() {
        assertEquals("ana", HandleList.handleOf(" @ana "))
        assertEquals("ana", HandleList.handleOf("x.com/ana"))
        assertNull(HandleList.handleOf("a b"))
        assertNull(HandleList.handleOf(""))
    }
}
