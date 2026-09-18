package com.clipcut.lan

/**
 * Render-ready state for a [LanReviewController] session, all values already projected onto the
 * frozen review window's `0..durationMs` timeline. In/out points are tracked internally as
 * wall-clock epoch millis (see [LanReviewController]) so they stay pinned to the same real moment
 * regardless of how they're displayed; this is the post-projection view for the UI. Ported from
 * the old (LLHLS-era) `CutUiState` -- this shape was never actually stream-specific.
 */
data class LanReviewUiState(
    val durationMs: Long = 0L,
    val positionMs: Long = 0L,
    val inPointMs: Long = 0L,
    val outPointMs: Long = 0L,
    val isPlaying: Boolean = false,
    val isPreviewing: Boolean = false,
) {
    val selectedDurationMs: Long get() = (outPointMs - inPointMs).coerceAtLeast(0)
}
