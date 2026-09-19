package com.clipcut.domain.deletion

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class BbLogsMatcherTest {
    @Test
    fun matchesTheBareCaptionAndItsNumber() {
        assertTrue(isBbLogsPost("BB LOGS - 042"))
        assertEquals(42, bbNumber("BB LOGS - 042"))
        assertEquals(0, bbNumber("BB LOGS - 000"))
        assertEquals(999, bbNumber("BB LOGS - 999"))
    }

    @Test
    fun matchesTheShapeXReallyReturnsWithTheMediaLinkAppended() {
        assertEquals(12, bbNumber("BB LOGS - 012 https://t.co/WFqkEQWQTH"))
        assertTrue(isBbLogsPost("BB LOGS - 012 https://t.co/WFqkEQWQTH\n"))
        assertTrue(isBbLogsPost("BB LOGS - 012 https://t.co/aaa https://t.co/bbb"))
    }

    @Test
    fun rejectsAnythingWithFreeTextAfterTheNumber() {
        assertFalse(isBbLogsPost("BB LOGS - 042 the fire scene"))
        assertFalse(isBbLogsPost("BB LOGS - 042 https://t.co/abc and more words"))
        assertFalse(isBbLogsPost("BB LOGS - 042\n\nanything"))
    }

    @Test
    fun rejectsNonTcoLinks() {
        assertFalse(isBbLogsPost("BB LOGS - 042 https://evil.example/x"))
        assertFalse(isBbLogsPost("BB LOGS - 042 http://t.co/abc"))
    }

    @Test
    fun rejectsAMentionOrQuoteNotAtTheStart() {
        assertFalse(isBbLogsPost("Great clip! BB LOGS - 012"))
        assertFalse(isBbLogsPost("RT @x: BB LOGS - 042"))
        assertFalse(isBbLogsPost(" BB LOGS - 042"))
    }

    @Test
    fun rejectsWrongDigitCountsCaseAndSeparators() {
        assertFalse(isBbLogsPost("BB LOGS - 42"))
        assertFalse(isBbLogsPost("BB LOGS - 0421"))
        assertFalse(isBbLogsPost("bb logs - 042"))
        assertFalse(isBbLogsPost("BB LOGS -042"))
        assertFalse(isBbLogsPost("BB LOGS- 042"))
        assertFalse(isBbLogsPost("BB LOGSS - 042"))
    }

    @Test
    fun rejectsTheMainAccountFormatsAndBlanks() {
        assertFalse(isBbLogsPost("BB Episode Logs - Kitchen war"))
        assertFalse(isBbLogsPost("BB 24Hrs Channel Logs - x"))
        assertFalse(isBbLogsPost(""))
        assertNull(bbNumber("hello"))
    }
}
