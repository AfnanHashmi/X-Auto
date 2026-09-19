package com.clipcut.data.x

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class XRateLimitTest {
    private fun of(vararg h: Pair<String, String>) = XRateLimit.from { h.toMap()[it] }

    @Test
    fun readsTheHeaders() {
        val r = of("x-rate-limit-limit" to "50", "x-rate-limit-remaining" to "0", "x-rate-limit-reset" to "1758300000")
        assertEquals(50, r.limit)
        assertEquals(0, r.remaining) // zero is meaningful: it triggers the stop before a 429
        assertEquals(1758300000L, r.resetEpochSec)
    }

    @Test
    fun absentOrGarbageHeadersAreNullNotAnError() {
        val r = of("x-rate-limit-remaining" to "soon", "x-rate-limit-reset" to "")
        assertNull(r.remaining)
        assertNull(r.resetEpochSec)
        assertNull(r.resetAt(1_000))
    }

    @Test
    fun resetPrefersTheAbsoluteHeaderThenRetryAfter() {
        assertEquals(1758300000L, of("x-rate-limit-reset" to "1758300000", "retry-after" to "60").resetAt(1_000))
        assertEquals(1_060L, of("retry-after" to "60").resetAt(1_000))
    }
}
