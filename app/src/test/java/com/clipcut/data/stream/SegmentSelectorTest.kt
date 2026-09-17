package com.clipcut.data.stream

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class SegmentSelectorTest {

    // Five consecutive 6s segments starting at t=0, t=6000, t=12000, t=18000, t=24000.
    private val segments = (0 until 5).map { i ->
        PlaylistSegment(uri = "seg_$i.m4s", programDateTimeEpochMs = i * 6_000L, durationMs = 6_000L)
    }

    @Test
    fun `selects the single segment fully containing a sub-segment range`() {
        val selected = SegmentSelector.selectForRange(segments, startEpochMs = 1_000, endEpochMs = 3_000)
        assertEquals(listOf(segments[0]), selected)
    }

    @Test
    fun `selects a contiguous run spanning multiple segments`() {
        val selected = SegmentSelector.selectForRange(segments, startEpochMs = 4_000, endEpochMs = 20_000)
        assertEquals(listOf(segments[0], segments[1], segments[2], segments[3]), selected)
    }

    @Test
    fun `returns null when the range starts before the oldest segment (aged out)`() {
        val selected = SegmentSelector.selectForRange(segments, startEpochMs = -1_000, endEpochMs = 3_000)
        assertNull(selected)
    }

    @Test
    fun `returns null when the range ends after the newest completed segment (not buffered yet)`() {
        val selected = SegmentSelector.selectForRange(segments, startEpochMs = 20_000, endEpochMs = 40_000)
        assertNull(selected)
    }

    @Test
    fun `returns null for an empty playlist`() {
        assertNull(SegmentSelector.selectForRange(emptyList(), startEpochMs = 0, endEpochMs = 1_000))
    }

    @Test
    fun `range exactly matching one segment's bounds selects just that segment`() {
        val selected = SegmentSelector.selectForRange(segments, startEpochMs = 6_000, endEpochMs = 12_000)
        assertEquals(listOf(segments[1]), selected)
    }
}
