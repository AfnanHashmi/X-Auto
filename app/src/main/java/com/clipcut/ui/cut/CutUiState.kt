package com.clipcut.ui.cut

/**
 * Render-ready state for the Cut screen, all values already projected onto the player's current
 * timeline (`0..durationMs`, `durationMs` = the live-reported DVR window). In/out points are
 * tracked internally as wall-clock epoch millis (see [CutViewModel]) so they stay pinned to the
 * same real moment as the DVR window slides forward; this is the post-projection view for the UI.
 */
data class CutUiState(
    val durationMs: Long = 0L,
    val positionMs: Long = 0L,
    val inPointMs: Long = 0L,
    val outPointMs: Long = 0L,
    val isPlaying: Boolean = false,
    val isLive: Boolean = true,
    val isExporting: Boolean = false,
    val statusMessage: String? = null,
) {
    val selectedDurationMs: Long get() = (outPointMs - inPointMs).coerceAtLeast(0)
}
