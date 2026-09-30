package com.clipcut.domain.api

import org.junit.Assert.assertEquals
import org.junit.Test

class MediaAccountTest {
    private val both: (MediaAccount) -> Boolean = { true }
    private val onlyDk: (MediaAccount) -> Boolean = { it == MediaAccount.DK }

    @Test
    fun theStoredChoiceIsUsedWhenItsKeysArePresent() {
        assertEquals(MediaAccount.RW, resolveMediaAccount("RW", both))
        assertEquals(MediaAccount.DK, resolveMediaAccount("DK", both))
    }

    @Test
    fun nothingStoredMeansDkTheOriginalAccount() {
        assertEquals(MediaAccount.DK, resolveMediaAccount(null, both))
    }

    @Test
    fun anAccountWithoutKeysIsNeverChosenSoAnUploadCannotGoToAnUnusableAccount() {
        assertEquals(MediaAccount.DK, resolveMediaAccount("RW", onlyDk))
    }

    @Test
    fun anUnknownStoredValueFallsBackToDk() {
        assertEquals(MediaAccount.DK, resolveMediaAccount("SOMETHING_ELSE", both))
    }

    @Test
    fun aCaptionIsTheLabelADashAndAThreeDigitNumber() {
        assertEquals("BB LOGS - 014", postCaption("BB LOGS", 14))
        assertEquals("BB LOGS - 001", postCaption("BB LOGS", 1))
        assertEquals("RW LOGS - 000", postCaption("RW LOGS", 0))
        assertEquals("RW LOGS - 999", postCaption("RW LOGS", 999))
    }
}
