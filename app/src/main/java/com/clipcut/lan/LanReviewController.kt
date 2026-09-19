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
private const val MIN_CLIP_SPAN_MS = 1_000L
// Clock-free live model. HLS live offset from ExoPlayer depends on the phone clock matching the PC's
// (it does not), so "how far behind live" is measured as window end minus position instead. The
// player sits LIVE_CUSHION_MS behind the edge so a WiFi hiccup (measured: occasional ~4s TCP stalls
// on held LL-HLS requests, both devices on WiFi) drains the cushion instead of freezing playback;
// if a stall pushes it past CATCHUP_ABOVE_MS it plays slightly fast until back at the cushion.
private const val LIVE_CUSHION_MS = 3_000L
private const val CATCHUP_ABOVE_MS = 4_500L
private const val CATCHUP_SPEED = 1.15f
private const val CATCHUP_DONE_MS = 3_500L

/**
 * Drives the one HLS player as a scrubbable timeline. The window is MediaMTX's sliding DVR window;
 * its wall-clock origin is the playlist's `#EXT-X-PROGRAM-DATE-TIME`, surfaced by Media3 as
 * `Timeline.Window.windowStartTimeMs`, so `epoch = windowStartTimeMs + position`.
 *
 * In/out points are absolute epoch millis so a selection stays pinned to the same real moment as
 * the window slides. There is no default selection: it appears when IN is marked, and its OUT rides
 * the live edge (the selection visibly grows) until OUT is marked, which fixes it.
 */
class LanReviewController(val player: ExoPlayer) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val window = Timeline.Window()

    private var inPointEpochMs: Long? = null
    private var outPointEpochMs: Long? = null
    private var previewLoopActive = false
    private var followingLive = true
    private var didInitialSeek = false

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

        val hasSelection = inPointEpochMs != null
        val outMarked = outPointEpochMs != null
        val inEpoch = inPointEpochMs
        val outEpoch = outPointEpochMs ?: end
        val behindMs = duration - position

        if (!didInitialSeek) {
            didInitialSeek = true
            player.seekTo((duration - LIVE_CUSHION_MS).coerceAtLeast(0))
        }
        val wantSpeed =
            if (followingLive && player.playWhenReady && !previewLoopActive &&
                (behindMs > CATCHUP_ABOVE_MS || (player.playbackParameters.speed > 1f && behindMs > CATCHUP_DONE_MS))
            ) CATCHUP_SPEED else 1f
        if (player.playbackParameters.speed != wantSpeed) player.setPlaybackSpeed(wantSpeed)
        val isLive = followingLive && player.playWhenReady && behindMs < CATCHUP_ABOVE_MS + 1_500L

        val inMs = if (inEpoch != null) (inEpoch - start).coerceIn(0, duration) else 0L
        val outMs = (outEpoch - start).coerceIn(0, duration)
        _uiState.value = LanReviewUiState(
            durationMs = duration,
            positionMs = position,
            inPointMs = inMs,
            outPointMs = outMs,
            // Intent, not Player.isPlaying: isPlaying is false during every rebuffer, which flipped the
            // button to "Play" while playback was still wanted, so a tap paused instead of playing.
            isPlaying = player.playWhenReady,
            isPreviewing = previewLoopActive,
            isLive = isLive,
            hasContent = true,
            hasSelection = hasSelection,
            outMarked = outMarked,
        )

        if (previewLoopActive && outMarked && outMs > inMs && position >= outMs) seekTo(inMs)
    }

    /** [timelineMs] is in the current window's `0..durationMs` coordinate system. */
    fun seekTo(timelineMs: Long) {
        val d = windowOrNull()?.durationMs ?: return
        followingLive = false
        player.seekTo(timelineMs.coerceIn(0, d))
    }

    fun togglePlayPause() {
        if (player.playWhenReady) followingLive = false
        player.playWhenReady = !player.playWhenReady
    }

    fun jumpToLive() {
        previewLoopActive = false
        val d = windowOrNull()?.durationMs
        if (d != null) player.seekTo((d - LIVE_CUSHION_MS).coerceAtLeast(0)) else player.seekToDefaultPosition()
        followingLive = true
        player.playWhenReady = true
    }

    fun stepFrame(forward: Boolean) {
        player.playWhenReady = false
        val frameRate = player.videoFormat?.frameRate?.takeIf { it > 0f && it.isFinite() }
        val stepMs = frameRate?.let { (1000.0 / it).toLong().coerceAtLeast(1L) } ?: FRAME_STEP_MS
        seekTo(player.currentPosition + if (forward) stepMs else -stepMs)
    }

    private fun epochAt(timelineMs: Long): Long? = windowOrNull()?.let { windowStartEpoch(it) + timelineMs }

    private fun windowEndEpoch(): Long? = windowOrNull()?.let { windowStartEpoch(it) + it.durationMs }

    fun dragInPoint(timelineMs: Long) {
        val epoch = epochAt(timelineMs) ?: return
        val end = windowEndEpoch() ?: return
        inPointEpochMs = epoch.coerceAtMost((outPointEpochMs ?: end) - MIN_CLIP_SPAN_MS)
        refresh()
    }

    /** Dragging (or marking) OUT fixes it; while unmarked it rides the live edge. */
    fun dragOutPoint(timelineMs: Long) {
        val inEpoch = inPointEpochMs ?: return
        val epoch = epochAt(timelineMs) ?: return
        outPointEpochMs = epoch.coerceAtLeast(inEpoch + MIN_CLIP_SPAN_MS)
        refresh()
    }

    /** Every Mark IN starts a fresh selection: any fixed OUT is released back onto the live edge. */
    fun setInAtPlayhead() {
        val epoch = epochAt(player.currentPosition) ?: return
        val end = windowEndEpoch() ?: return
        outPointEpochMs = null
        inPointEpochMs = epoch.coerceAtMost(end - MIN_CLIP_SPAN_MS)
        refresh()
    }

    /** Fixes OUT at the playhead, at least [MIN_CLIP_SPAN_MS] after IN. No-op until IN is marked. */
    fun setOutAtPlayhead() = dragOutPoint(player.currentPosition)

    fun enterPreview() {
        if (inPointEpochMs == null || outPointEpochMs == null) return
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
        val i = inPointEpochMs ?: return null
        val o = outPointEpochMs ?: return null
        return i to o
    }

    /** Stops polling; the player itself is owned by [LanBufferSession]. */
    fun release() {
        scope.cancel()
    }
}
