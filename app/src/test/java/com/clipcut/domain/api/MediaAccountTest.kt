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
    fun aCaptionIsTheLabelADashAndTheNumberPaddedToTheGivenDigits() {
        assertEquals("BB LOGS - 014", postCaption("BB LOGS", 14, 3))
        assertEquals("BB LOGS - 001", postCaption("BB LOGS", 1, 3))
        assertEquals("BB LOGS - 0000", postCaption("BB LOGS", 0, 4))
        assertEquals("BB LOGS - 0014", postCaption("BB LOGS", 14, 4))
        assertEquals("BB LOGS - 9999", postCaption("BB LOGS", 9999, 4))
    }

    @Test
    fun bothAccountsPostUnderBbLogsButDkUsesThreeDigitsAndRwFour() {
        assertEquals("BB LOGS", MediaAccount.DK.captionLabel)
        assertEquals("BB LOGS", MediaAccount.RW.captionLabel)
        assertEquals(3, MediaAccount.DK.numberDigits)
        assertEquals(4, MediaAccount.RW.numberDigits)
        // DK's existing posts (BB LOGS - 082 and so on) must keep their shape.
        assertEquals("BB LOGS - 082", postCaption(MediaAccount.DK.captionLabel, 82, MediaAccount.DK.numberDigits))
        assertEquals("BB LOGS - 0000", postCaption(MediaAccount.RW.captionLabel, 0, MediaAccount.RW.numberDigits))
    }

    @Test
    fun eachAccountsNumberWrapsAtItsOwnWidthSoRwNeverPostsAFiveDigitNumber() {
        assertEquals(1_000, MediaAccount.DK.counterModulus)
        assertEquals(10_000, MediaAccount.RW.counterModulus)
        assertEquals(0, (999 + 1) % MediaAccount.DK.counterModulus)
        assertEquals(0, (9_999 + 1) % MediaAccount.RW.counterModulus)
    }
}
