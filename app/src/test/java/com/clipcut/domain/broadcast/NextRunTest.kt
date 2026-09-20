package com.clipcut.domain.broadcast

import org.junit.Assert.assertEquals
import org.junit.Test
import java.time.Instant
import java.time.ZoneId

class NextRunTest {
    private val india = ZoneId.of("Asia/Kolkata")

    private fun ms(iso: String) = Instant.parse(iso).toEpochMilli()

    @Test
    fun beforeSevenAmIstItFiresTodayAtSevenIst() {
        // 06:00 IST on 20 Sep = 00:30 UTC
        assertEquals(ms("2026-09-20T01:30:00Z"), nextDailyRunMs(ms("2026-09-20T00:30:00Z"), 7, 0, india))
    }

    @Test
    fun afterSevenAmIstItFiresTomorrow() {
        // 08:00 IST on 20 Sep = 02:30 UTC
        assertEquals(ms("2026-09-21T01:30:00Z"), nextDailyRunMs(ms("2026-09-20T02:30:00Z"), 7, 0, india))
    }

    @Test
    fun exactlyAtSevenAmMovesToTomorrowSoItCannotDoubleFire() {
        assertEquals(ms("2026-09-21T01:30:00Z"), nextDailyRunMs(ms("2026-09-20T01:30:00Z"), 7, 0, india))
    }

    @Test
    fun theZoneIsIndiaNotThePhonesZone() {
        // The same instant gives the same answer whatever the phone thinks: 7am IST is 01:30 UTC.
        val now = ms("2026-09-20T00:00:00Z")
        assertEquals(nextDailyRunMs(now, 7, 0, india), nextDailyRunMs(now, 7, 0, ZoneId.of("Asia/Kolkata")))
        assertEquals(ms("2026-09-20T01:30:00Z"), nextDailyRunMs(now, 7, 0, india))
    }

    @Test
    fun minutesAreHonoured() {
        assertEquals(ms("2026-09-20T02:15:00Z"), nextDailyRunMs(ms("2026-09-20T00:00:00Z"), 7, 45, india))
    }
}
