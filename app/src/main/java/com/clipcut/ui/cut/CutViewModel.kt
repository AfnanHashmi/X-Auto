package com.clipcut.ui.cut

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.Timeline
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.MediaSource
import com.clipcut.data.db.ClipRecord
import com.clipcut.data.db.ClipRecordDao
import com.clipcut.data.stream.PlaylistRepository
import com.clipcut.data.stream.SignedPolicyProvider
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
private const val STREAM_WARNING_POLL_MS = 8_000L
private const val LIVE_EDGE_EPSILON_MS = 1_500L
private const val FRAME_STEP_MS = 33L // fallback (~30fps) when Format.frameRate isn't reported yet.
private const val DEFAULT_CLIP_SPAN_MS = 15_000L
private const val MIN_CLIP_SPAN_MS = 1_000L
private const val DVR_SCRUB_WINDOW_MS = 5 * 60 * 1000L // client-side cap: only the last 5 min is scrubbable/selectable.
private const val PLAYER_ERROR_RETRY_MS = 5_000L

/**
 * Wall-clock span of the currently known DVR window, derived from the player's own timeline (see
 * [CutViewModel.playerEpochOrigin]) -- NOT from a separately-fetched playlist copy. Two independent
 * fetches of a sliding live playlist diverge (segments age out every ~6s), which was the root cause
 * of exported clips landing seconds earlier than the selected range while preview looked correct
 * (preview's epoch<->position round-trip cancelled the error; export's absolute-epoch comparison
 * against real segment timestamps did not). [startEpochMs] is clamped to the last
 * [DVR_SCRUB_WINDOW_MS] and is what in/out points and the scrubber are projected against.
 */
private data class PlaylistWindow(val startEpochMs: Long, val endEpochMs: Long)

@HiltViewModel
class CutViewModel @Inject constructor(
    @ApplicationContext context: Context,
    mediaSourceFactory: MediaSource.Factory,
    private val playlistRepository: PlaylistRepository,
    private val signedPolicyProvider: SignedPolicyProvider,
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
        // A single transient error (e.g. a stream credential that was momentarily invalid) leaves
        // ExoPlayer permanently stopped otherwise -- it does not retry on its own after a fatal
        // playback error, and it stops issuing requests entirely, which meant this app's own
        // credential-refresh logic never got another chance to run either (nothing was calling
        // it). Re-preparing after a short delay lets both recover once the underlying cause clears.
        player.addListener(
            object : Player.Listener {
                override fun onPlayerError(error: PlaybackException) {
                    viewModelScope.launch {
                        delay(PLAYER_ERROR_RETRY_MS)
                        player.prepare()
                    }
                }
            },
        )
        viewModelScope.launch {
            val url = playlistRepository.currentPlaylistUrl()
            player.setMediaItem(MediaItem.fromUri(url))
            player.prepare()
            player.playWhenReady = true
        }
        viewModelScope.launch { pollPlayerState() }
        viewModelScope.launch { pollStreamWarning() }
    }

    /**
     * The player's own live-window origin: the epoch that `player.currentPosition == 0`
     * corresponds to. Populated from the HLS playlist's `#EXT-X-PROGRAM-DATE-TIME` (ours has it) --
     * see `Timeline.Window.windowStartTimeMs`. This must be the ONLY source used to bridge player
     * position <-> wall clock; a second, independently-fetched copy of the playlist is exactly what
     * caused the original bug (two snapshots of a sliding window disagree). Read fresh on every
     * call, never cached, since the window shifts forward continuously.
     */
    private fun playerEpochOrigin(): Long? {
        if (player.currentTimeline.isEmpty) return null
        val window = player.currentTimeline.getWindow(player.currentMediaItemIndex, Timeline.Window())
        if (window.isPlaceholder) return null
        return window.windowStartTimeMs.takeIf { it != C.TIME_UNSET }
    }

    private suspend fun pollPlayerState() {
        while (viewModelScope.isActive) {
            val rawDurationMs = player.duration.takeIf { it != C.TIME_UNSET } ?: 0L
            val rawPositionMs = player.currentPosition.coerceIn(0, rawDurationMs.coerceAtLeast(0))
            val origin = playerEpochOrigin()

            if (origin != null) {
                val endEpochMs = origin + rawDurationMs
                playlistWindow = PlaylistWindow(
                    startEpochMs = (endEpochMs - DVR_SCRUB_WINDOW_MS).coerceAtLeast(origin),
                    endEpochMs = endEpochMs,
                )
            }
            val window = playlistWindow

            if (window != null && (inPointEpochMs == null || outPointEpochMs == null)) {
                // First window available: default to the last DEFAULT_CLIP_SPAN_MS, i.e. "what just happened".
                outPointEpochMs = window.endEpochMs
                inPointEpochMs = (window.endEpochMs - DEFAULT_CLIP_SPAN_MS).coerceAtLeast(window.startEpochMs)
            }

            // Expose the clamped (<=5min) window span as "duration" for the scrubber, and project
            // the native player position into that same clamped coordinate system.
            val windowSpanMs = window?.let { (it.endEpochMs - it.startEpochMs).coerceAtLeast(0) } ?: 0L
            val positionMs = if (window != null && origin != null) {
                val playheadEpochMs = origin + rawPositionMs
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

    private suspend fun pollStreamWarning() {
        while (viewModelScope.isActive) {
            _uiState.update { it.copy(streamWarning = signedPolicyProvider.warningOrNull()) }
            delay(STREAM_WARNING_POLL_MS)
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
        val origin = playerEpochOrigin() ?: return
        val windowSpanMs = (window.endEpochMs - window.startEpochMs).coerceAtLeast(0)
        val epoch = window.startEpochMs + timelineMs.coerceIn(0, windowSpanMs)
        player.seekTo((epoch - origin).coerceIn(0, rawDurationMs))
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
