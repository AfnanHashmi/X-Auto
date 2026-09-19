package com.clipcut.domain.deletion

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

class DeletionWindowTest {
    private val now = Instant.parse("2026-09-19T12:00:00Z")

    @Test
    fun presetsLookBackTheirSpanFromNow() {
        assertEquals(now.minusSeconds(3600), presetRange(DeletionPreset.LAST_1H, now).start)
        assertEquals(now.minusSeconds(6 * 3600), presetRange(DeletionPreset.LAST_6H, now).start)
        assertEquals(now.minusSeconds(24 * 3600), presetRange(DeletionPreset.LAST_24H, now).start)
        assertEquals(now.minusSeconds(7 * 24 * 3600), presetRange(DeletionPreset.LAST_7D, now).start)
        assertEquals(now, presetRange(DeletionPreset.LAST_1H, now).endExclusive)
    }

    @Test
    fun customIsNotAPreset() {
        assertThrows(IllegalArgumentException::class.java) { presetRange(DeletionPreset.CUSTOM, now) }
    }

    @Test
    fun aCustomRangeCoversWholeLocalDays() {
        val karachi = ZoneId.of("Asia/Karachi")
        val r = customRange(LocalDate.of(2026, 9, 19), LocalDate.of(2026, 9, 19), karachi)
        assertEquals(Instant.parse("2026-09-18T19:00:00Z"), r.start) // 00:00 local, UTC+5
        assertEquals(Instant.parse("2026-09-19T19:00:00Z"), r.endExclusive)
    }

    @Test
    fun aMultiDayRangeEndsAtTheStartOfTheDayAfterTheLastDay() {
        val r = customRange(LocalDate.of(2026, 9, 17), LocalDate.of(2026, 9, 19), ZoneId.of("UTC"))
        assertEquals(Instant.parse("2026-09-17T00:00:00Z"), r.start)
        assertEquals(Instant.parse("2026-09-20T00:00:00Z"), r.endExclusive)
    }

    @Test
    fun aBackwardsRangeIsRejected() {
        assertThrows(IllegalArgumentException::class.java) {
            customRange(LocalDate.of(2026, 9, 20), LocalDate.of(2026, 9, 19), ZoneId.of("UTC"))
        }
    }

    @Test
    fun containsIsHalfOpen() {
        val r = DeletionRange(Instant.parse("2026-09-19T00:00:00Z"), Instant.parse("2026-09-20T00:00:00Z"))
        assertTrue(Instant.parse("2026-09-19T00:00:00Z") in r)
        assertTrue(Instant.parse("2026-09-19T23:59:59Z") in r)
        assertFalse(Instant.parse("2026-09-20T00:00:00Z") in r)
        assertFalse(Instant.parse("2026-09-18T23:59:59Z") in r)
    }
}
