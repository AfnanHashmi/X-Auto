package com.clipcut.data.stream

/**
 * Maps an in/out wall-clock selection to the contiguous run of full segments that cover it.
 */
object SegmentSelector {
    /**
     * Returns the segments spanning `[startEpochMs, endEpochMs)`, in playlist order, or `null`
     * if the playlist doesn't fully cover that range -- either it has aged out of the DVR window
     * (start before the oldest segment) or hasn't been recorded yet (end after the newest
     * completed segment).
     */
    fun selectForRange(
        segments: List<PlaylistSegment>,
        startEpochMs: Long,
        endEpochMs: Long,
    ): List<PlaylistSegment>? {
        require(endEpochMs > startEpochMs) { "endEpochMs must be after startEpochMs" }
        if (segments.isEmpty()) return null

        val covering = segments.filter { segment ->
            val segmentEnd = segment.programDateTimeEpochMs + segment.durationMs
            segmentEnd > startEpochMs && segment.programDateTimeEpochMs < endEpochMs
        }
        if (covering.isEmpty()) return null

        val first = covering.first()
        val last = covering.last()
        if (first.programDateTimeEpochMs > startEpochMs) return null
        if (last.programDateTimeEpochMs + last.durationMs < endEpochMs) return null
        return covering
    }
}
