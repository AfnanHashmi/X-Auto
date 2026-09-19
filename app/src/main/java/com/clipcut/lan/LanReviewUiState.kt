package com.clipcut.lan

/**
 * Render-ready state for [LanReviewController], projected onto the current live window's
 * `0..durationMs` timeline. In/out points are tracked internally as wall-clock epoch millis so
 * they stay pinned to the same real moment while the window slides.
 */
data class LanReviewUiState(
    val durationMs: Long = 0L,
    val positionMs: Long = 0L,
    val inPointMs: Long = 0L,
    val outPointMs: Long = 0L,
    val isPlaying: Boolean = false,
    val isPreviewing: Boolean = false,
    val isLive: Boolean = true,
    val hasContent: Boolean = false,
) {
    val selectedDurationMs: Long get() = (outPointMs - inPointMs).coerceAtLeast(0)
}
