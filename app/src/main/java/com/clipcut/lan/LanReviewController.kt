package com.clipcut.lan

import androidx.media3.common.C
import androidx.media3.common.Timeline
import androidx.media3.exoplayer.ExoPlayer
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

private const val POSITION_POLL_MS = 200L
private const val FRAME_STEP_MS = 33L // fallback (~30fps) when Format.frameRate isn't reported yet.
private const val DEFAULT_CLIP_SPAN_MS = 15_000L
private const val MIN_CLIP_SPAN_MS = 1_000L
private const val LIVE_OFFSET_THRESHOLD_MS = 4_000L

/**
 * Drives the one HLS player as a scrubbable timeline. The window is MediaMTX's sliding DVR window;
 * its wall-clock origin is the playlist's `#EXT-X-PROGRAM-DATE-TIME`, surfaced by Media3 as
 * `Timeline.Window.windowStartTimeMs`, so `epoch = windowStartTimeMs + position`.
 *
 * In/out points are absolute epoch millis so a selection stays pinned to the same real moment as
 * the window slides. Until the user touches either marker, both default to "the last 15s" and keep
 * following live; the first mark/drag freezes them.
 */
class LanReviewController(val player: ExoPlayer) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val window = Timeline.Window()

    private var inPointEpochMs: Long? = null
    private var outPointEpochMs: Long? = null
    private var previewLoopActive = false

    private val _uiState = MutableStateFlow(LanReviewUiState())
    val uiState: StateFlow<LanReviewUiState> = _uiState.asStateFlow()

    init {
        scope.launch {
            while (isActive) {
                refresh()
                delay(POSITION_POLL_MS)
            }
        }
    }

    private fun windowOrNull(): Timeline.Window? {
        val tl = player.currentTimeline
        if (tl.isEmpty || player.currentMediaItemIndex >= tl.windowCount) return null
        tl.getWindow(player.currentMediaItemIndex, window)
        return window.takeIf { it.durationMs != C.TIME_UNSET && it.durationMs > 0 }
    }

    private fun windowStartEpoch(w: Timeline.Window): Long =
        if (w.windowStartTimeMs != C.TIME_UNSET) w.windowStartTimeMs else System.currentTimeMillis() - w.durationMs

    private fun refresh() {
        val w = windowOrNull() ?: return
        val duration = w.durationMs
        val start = windowStartEpoch(w)
        val end = start + duration
        val position = player.currentPosition.coerceIn(0, duration)

        val inEpoch = inPointEpochMs ?: (end - DEFAULT_CLIP_SPAN_MS).coerceAtLeast(start)
        val outEpoch = outPointEpochMs ?: end
        val offset = player.currentLiveOffset
        val isLive = player.playWhenReady && offset != C.TIME_UNSET && offset < LIVE_OFFSET_THRESHOLD_MS

        val inMs = (inEpoch - start).coerceIn(0, duration)
        val outMs = (outEpoch - start).coerceIn(0, duration)
        _uiState.value = LanReviewUiState(
            durationMs = duration,
            positionMs = position,
            inPointMs = inMs,
            outPointMs = outMs,
            isPlaying = player.isPlaying,
            isPreviewing = previewLoopActive,
            isLive = isLive,
            hasContent = true,
        )

        if (previewLoopActive && outMs > inMs && position >= outMs) seekTo(inMs)
    }

    /** Freezes both markers at their current values so later moves of one don't shift the other. */
    private fun materializeMarkers() {
        val w = windowOrNull() ?: return
        val start = windowStartEpoch(w)
        val end = start + w.durationMs
        if (inPointEpochMs == null) inPointEpochMs = (end - DEFAULT_CLIP_SPAN_MS).coerceAtLeast(start)
        if (outPointEpochMs == null) outPointEpochMs = end
    }

    /** [timelineMs] is in the current window's `0..durationMs` coordinate system. */
    fun seekTo(timelineMs: Long) {
        val d = windowOrNull()?.durationMs ?: return
        player.seekTo(timelineMs.coerceIn(0, d))
    }

    fun togglePlayPause() {
        player.playWhenReady = !player.playWhenReady
    }

    fun jumpToLive() {
        previewLoopActive = false
        player.seekToDefaultPosition()
        player.playWhenReady = true
    }

    fun stepFrame(forward: Boolean) {
        player.playWhenReady = false
        val frameRate = player.videoFormat?.frameRate?.takeIf { it > 0f && it.isFinite() }
        val stepMs = frameRate?.let { (1000.0 / it).toLong().coerceAtLeast(1L) } ?: FRAME_STEP_MS
        seekTo(player.currentPosition + if (forward) stepMs else -stepMs)
    }

    private fun epochAt(timelineMs: Long): Long? = windowOrNull()?.let { windowStartEpoch(it) + timelineMs }

    fun dragInPoint(timelineMs: Long) {
        materializeMarkers()
        val epoch = epochAt(timelineMs) ?: return
        inPointEpochMs = epoch.coerceAtMost((outPointEpochMs ?: epoch) - MIN_CLIP_SPAN_MS)
        refresh()
    }

    fun dragOutPoint(timelineMs: Long) {
        materializeMarkers()
        val epoch = epochAt(timelineMs) ?: return
        outPointEpochMs = epoch.coerceAtLeast((inPointEpochMs ?: epoch) + MIN_CLIP_SPAN_MS)
        refresh()
    }

    fun setInAtPlayhead() = dragInPoint(player.currentPosition)

    fun setOutAtPlayhead() = dragOutPoint(player.currentPosition)

    fun enterPreview() {
        materializeMarkers()
        previewLoopActive = true
        seekTo(_uiState.value.inPointMs)
        player.playWhenReady = true
        refresh()
    }

    fun exitPreview() {
        previewLoopActive = false
        player.playWhenReady = false
        refresh()
    }

    /** The selection as absolute epoch millis, for [ClipDownloader]; null before any window exists. */
    fun inOutEpochRange(): Pair<Long, Long>? {
        materializeMarkers()
        val i = inPointEpochMs ?: return null
        val o = outPointEpochMs ?: return null
        return i to o
    }

    /** Stops polling; the player itself is owned by [LanBufferSession]. */
    fun release() {
        scope.cancel()
    }
}
