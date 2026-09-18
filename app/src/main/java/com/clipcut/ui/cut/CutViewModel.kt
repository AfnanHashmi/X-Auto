package com.clipcut.ui.cut

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.MediaSource
import com.clipcut.data.db.ClipRecord
import com.clipcut.data.db.ClipRecordDao
import com.clipcut.data.stream.PlaylistRepository
import com.clipcut.data.stream.SegmentFetcher
import com.clipcut.domain.ClipExporter
import com.clipcut.domain.ClipRange
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import javax.inject.Inject

private const val POSITION_POLL_MS = 200L
private const val PLAYLIST_REFRESH_MS = 8_000L
private const val LIVE_EDGE_EPSILON_MS = 1_500L
private const val FRAME_STEP_MS = 33L // fallback (~30fps) when Format.frameRate isn't reported yet.
private const val DEFAULT_CLIP_SPAN_MS = 15_000L
private const val MIN_CLIP_SPAN_MS = 1_000L
private const val DVR_SCRUB_WINDOW_MS = 5 * 60 * 1000L // client-side cap: only the last 5 min is scrubbable/selectable.

/**
 * Wall-clock span of the currently known playlist window; the stable coordinate system for
 * in/out points. [rawStartEpochMs] is the earliest epoch actually buffered by the player (the
 * server's full DVR window) and is only used to map epoch <-> native player position. [startEpochMs]
 * is clamped to the last [DVR_SCRUB_WINDOW_MS] and is what in/out points and the scrubber are
 * projected against, so the app exposes at most a 5-minute scrubbable window client-side even
 * though the server buffers much more.
 */
private data class PlaylistWindow(val rawStartEpochMs: Long, val startEpochMs: Long, val endEpochMs: Long)

@HiltViewModel
class CutViewModel @Inject constructor(
    @ApplicationContext context: Context,
    mediaSourceFactory: MediaSource.Factory,
    private val playlistRepository: PlaylistRepository,
    private val segmentFetcher: SegmentFetcher,
    private val clipExporter: ClipExporter,
    private val clipRecordDao: ClipRecordDao,
) : ViewModel() {

    val player: ExoPlayer = ExoPlayer.Builder(context)
        .setMediaSourceFactory(mediaSourceFactory)
        .build()

    private val _uiState = MutableStateFlow(CutUiState())
    val uiState: StateFlow<CutUiState> = _uiState.asStateFlow()

    private val _exportedClipPath = MutableSharedFlow<String>(extraBufferCapacity = 1)
    /** Emits once per successful export; the Cut screen navigates to Caption on each emission. */
    val exportedClipPath: SharedFlow<String> = _exportedClipPath

    // In/out points as wall-clock epoch ms -- stable across the DVR window sliding forward.
    // Null until the first playlist fetch establishes a window to project defaults from.
    private var inPointEpochMs: Long? = null
    private var outPointEpochMs: Long? = null
    private var playlistWindow: PlaylistWindow? = null

    init {
        viewModelScope.launch {
            val url = playlistRepository.currentPlaylistUrl()
            player.setMediaItem(MediaItem.fromUri(url))
            player.prepare()
            player.playWhenReady = true
        }
        viewModelScope.launch { pollPlayerState() }
        viewModelScope.launch { pollPlaylistWindow() }
    }

    private suspend fun pollPlayerState() {
        while (viewModelScope.isActive) {
            val rawDurationMs = player.duration.takeIf { it != C.TIME_UNSET } ?: 0L
            val rawPositionMs = player.currentPosition.coerceIn(0, rawDurationMs.coerceAtLeast(0))
            val window = playlistWindow

            if (window != null && (inPointEpochMs == null || outPointEpochMs == null)) {
                // First window available: default to the last DEFAULT_CLIP_SPAN_MS, i.e. "what just happened".
                outPointEpochMs = window.endEpochMs
                inPointEpochMs = (window.endEpochMs - DEFAULT_CLIP_SPAN_MS).coerceAtLeast(window.startEpochMs)
            }

            // Expose the clamped (<=5min) window span as "duration" for the scrubber, and project
            // the native player position into that same clamped coordinate system.
            val windowSpanMs = window?.let { (it.endEpochMs - it.startEpochMs).coerceAtLeast(0) } ?: 0L
            val positionMs = if (window != null) {
                val playheadEpochMs = window.rawStartEpochMs + rawPositionMs
                (playheadEpochMs - window.startEpochMs).coerceIn(0, windowSpanMs)
            } else {
                0L
            }

            _uiState.update {
                it.copy(
                    durationMs = windowSpanMs,
                    positionMs = positionMs,
                    isPlaying = player.isPlaying,
                    isLive = rawDurationMs > 0 && (rawDurationMs - rawPositionMs) < LIVE_EDGE_EPSILON_MS,
                )
            }
            updateInOutProjection()

            if (previewLoopActive) {
                val s = _uiState.value
                if (s.outPointMs > s.inPointMs && s.positionMs >= s.outPointMs) {
                    seekTo(s.inPointMs)
                }
            }

            delay(POSITION_POLL_MS)
        }
    }

    private suspend fun pollPlaylistWindow() {
        while (viewModelScope.isActive) {
            refreshPlaylistWindow()
            delay(PLAYLIST_REFRESH_MS)
        }
    }

    private suspend fun refreshPlaylistWindow() {
        runCatching {
            val segments = segmentFetcher.fetchMediaPlaylist(playlistRepository.currentPlaylistUrl()).segments
            if (segments.isNotEmpty()) {
                val rawStart = segments.first().programDateTimeEpochMs
                val rawEnd = segments.last().programDateTimeEpochMs + segments.last().durationMs
                playlistWindow = PlaylistWindow(
                    rawStartEpochMs = rawStart,
                    startEpochMs = (rawEnd - DVR_SCRUB_WINDOW_MS).coerceAtLeast(rawStart),
                    endEpochMs = rawEnd,
                )
            }
        }
    }

    /** Maps a stored epoch point onto the player's current `0..durationMs` timeline for rendering. */
    private fun projectToTimeline(epochMs: Long?, window: PlaylistWindow, durationMs: Long): Long {
        if (epochMs == null) return 0L
        return (epochMs - window.startEpochMs).coerceIn(0, durationMs)
    }

    /**
     * Pushes in/out epoch points into [_uiState] immediately. Called synchronously from the drag
     * handlers (not just the 200ms [pollPlayerState] tick) so the handle follows the user's finger
     * every frame instead of visibly lagging up to 200ms behind.
     */
    private fun updateInOutProjection() {
        val window = playlistWindow ?: return
        val durationMs = _uiState.value.durationMs
        _uiState.update {
            it.copy(
                inPointMs = projectToTimeline(inPointEpochMs, window, durationMs),
                outPointMs = projectToTimeline(outPointEpochMs, window, durationMs),
            )
        }
    }

    /** Inverse of [projectToTimeline]: a timeline position the user just interacted with -> epoch ms. */
    private fun timelineToEpoch(timelineMs: Long): Long? = playlistWindow?.let { it.startEpochMs + timelineMs }

    /** [timelineMs] is in the clamped (<=5min) window-local coordinate system used by the scrubber. */
    fun seekTo(timelineMs: Long) {
        val window = playlistWindow ?: return
        val rawDurationMs = player.duration.takeIf { it != C.TIME_UNSET } ?: return
        val windowSpanMs = (window.endEpochMs - window.startEpochMs).coerceAtLeast(0)
        val epoch = window.startEpochMs + timelineMs.coerceIn(0, windowSpanMs)
        player.seekTo((epoch - window.rawStartEpochMs).coerceIn(0, rawDurationMs))
    }

    fun seekToLive() {
        player.duration.takeIf { it != C.TIME_UNSET }?.let { player.seekTo(it) }
    }

    fun togglePlayPause() {
        player.playWhenReady = !player.playWhenReady
    }

    /**
     * Nudges the raw player position by one frame. Deliberately bypasses [seekTo] -- that method's
     * [timelineMs] argument is in the clamped (<=5min) window-local coordinate system, whereas
     * [player.currentPosition] is the native, unclamped position; mixing the two would clamp every
     * step to the window edge instead of nudging by a frame.
     */
    fun stepFrame(forward: Boolean) {
        val rawDurationMs = player.duration.takeIf { it != C.TIME_UNSET } ?: return
        // Prefer the stream's actual reported frame rate over the ~30fps approximation when available.
        val frameRate = player.videoFormat?.frameRate?.takeIf { it > 0f && it.isFinite() }
        val stepMs = frameRate?.let { (1000.0 / it).toLong().coerceAtLeast(1L) } ?: FRAME_STEP_MS
        val delta = if (forward) stepMs else -stepMs
        player.seekTo((player.currentPosition + delta).coerceIn(0, rawDurationMs))
    }

    fun dragInPoint(timelineMs: Long) {
        val epoch = timelineToEpoch(timelineMs) ?: return
        val maxEpoch = (outPointEpochMs ?: return) - MIN_CLIP_SPAN_MS
        inPointEpochMs = epoch.coerceAtMost(maxEpoch)
        updateInOutProjection()
    }

    fun dragOutPoint(timelineMs: Long) {
        val epoch = timelineToEpoch(timelineMs) ?: return
        val minEpoch = (inPointEpochMs ?: return) + MIN_CLIP_SPAN_MS
        outPointEpochMs = epoch.coerceAtLeast(minEpoch)
        updateInOutProjection()
    }

    private var previewLoopActive = false

    /** Seeks to the in point and starts looping playback within [in, out) until [exitPreview]. */
    fun enterPreview() {
        previewLoopActive = true
        seekTo(_uiState.value.inPointMs)
        player.playWhenReady = true
    }

    fun exitPreview() {
        previewLoopActive = false
    }

    /** Abandons the current selection (used when Preview is cancelled) and returns to defaults. */
    fun resetSelection() {
        exitPreview()
        inPointEpochMs = null
        outPointEpochMs = null
    }

    private var exportJob: Job? = null

    fun export() {
        exportJob = viewModelScope.launch {
            _uiState.update { it.copy(isExporting = true, statusMessage = "Exporting...") }
            refreshPlaylistWindow() // freshest possible window right before committing to epoch math.

            val rangeResult = runCatching {
                val start = requireNotNull(inPointEpochMs) { "No in point set yet" }
                val end = requireNotNull(outPointEpochMs) { "No out point set yet" }
                ClipRange(startEpochMs = start, endEpochMs = end)
            }
            val result = rangeResult.fold(
                onSuccess = { range ->
                    clipExporter.export(range) { status ->
                        _uiState.update { it.copy(statusMessage = status) }
                    }
                },
                onFailure = { Result.failure(it) },
            )

            // clipExporter.export() wraps its body in runCatching, which -- unlike a bare try/catch
            // -- absorbs CancellationException into Result.failure instead of propagating it. Without
            // this check, a cancelExport() during export would let this coroutine run to completion
            // anyway and stomp the "Export cancelled" message with a stale "Export failed" one.
            ensureActive()

            result.onSuccess { file ->
                rangeResult.getOrNull()?.let { range ->
                    clipRecordDao.insert(
                        ClipRecord(filePath = file.absolutePath, startEpochMs = range.startEpochMs, endEpochMs = range.endEpochMs),
                    )
                }
                _exportedClipPath.tryEmit(file.absolutePath)
            }

            _uiState.update {
                it.copy(
                    isExporting = false,
                    statusMessage = result.fold(
                        onSuccess = { file -> "Exported: ${file.name} (${file.length()} bytes)" },
                        onFailure = { e -> "Export failed: ${e.message}" },
                    ),
                )
            }
        }
    }

    fun cancelExport() {
        exportJob?.cancel()
        _uiState.update { it.copy(isExporting = false, statusMessage = "Export cancelled") }
    }

    override fun onCleared() {
        player.release()
    }
}
