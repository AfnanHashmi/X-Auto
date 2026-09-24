package com.clipcut.domain.api

import org.junit.Assert.assertEquals
import org.junit.Test

class MediaAccountTest {
    private val both: (MediaAccount) -> Boolean = { true }
    private val onlyDk: (MediaAccount) -> Boolean = { it == MediaAccount.DK }

    @Test
    fun theStoredChoiceIsUsedWhenItsKeysArePresent() {
        assertEquals(MediaAccount.BB, resolveMediaAccount("BB", both))
        assertEquals(MediaAccount.DK, resolveMediaAccount("DK", both))
    }

    @Test
    fun nothingStoredMeansDkTheOriginalAccount() {
        assertEquals(MediaAccount.DK, resolveMediaAccount(null, both))
    }

    @Test
    fun anAccountWithoutKeysIsNeverChosenSoAnUploadCannotGoToAnUnusableAccount() {
        assertEquals(MediaAccount.DK, resolveMediaAccount("BB", onlyDk))
    }

    @Test
    fun anUnknownStoredValueFallsBackToDk() {
        assertEquals(MediaAccount.DK, resolveMediaAccount("SOMETHING_ELSE", both))
    }
}
