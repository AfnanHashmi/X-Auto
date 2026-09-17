package com.clipcut.ui.cut

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.MediaSource
import com.clipcut.data.stream.PlaylistRepository
import com.clipcut.data.stream.SegmentFetcher
import com.clipcut.domain.ClipExporter
import com.clipcut.domain.ClipRange
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import javax.inject.Inject

private const val POSITION_POLL_MS = 200L
private const val PLAYLIST_REFRESH_MS = 8_000L
private const val LIVE_EDGE_EPSILON_MS = 1_500L
private const val FRAME_STEP_MS = 33L // ~1 frame at the stream's 30fps; approximate, not decoder-exact.
private const val DEFAULT_CLIP_SPAN_MS = 15_000L
private const val MIN_CLIP_SPAN_MS = 1_000L

/** Wall-clock span of the currently known playlist window; the stable coordinate system for in/out points. */
private data class PlaylistWindow(val startEpochMs: Long, val endEpochMs: Long)

@HiltViewModel
class CutViewModel @Inject constructor(
    @ApplicationContext context: Context,
    mediaSourceFactory: MediaSource.Factory,
    private val playlistRepository: PlaylistRepository,
    private val segmentFetcher: SegmentFetcher,
    private val clipExporter: ClipExporter,
) : ViewModel() {

    val player: ExoPlayer = ExoPlayer.Builder(context)
        .setMediaSourceFactory(mediaSourceFactory)
        .build()

    private val _uiState = MutableStateFlow(CutUiState())
    val uiState: StateFlow<CutUiState> = _uiState.asStateFlow()

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
            val durationMs = player.duration.takeIf { it != C.TIME_UNSET } ?: 0L
            val positionMs = player.currentPosition.coerceIn(0, durationMs.coerceAtLeast(0))
            val window = playlistWindow

            if (window != null && (inPointEpochMs == null || outPointEpochMs == null)) {
                // First window available: default to the last DEFAULT_CLIP_SPAN_MS, i.e. "what just happened".
                outPointEpochMs = window.endEpochMs
                inPointEpochMs = (window.endEpochMs - DEFAULT_CLIP_SPAN_MS).coerceAtLeast(window.startEpochMs)
            }

            _uiState.update {
                it.copy(
                    durationMs = durationMs,
                    positionMs = positionMs,
                    isPlaying = player.isPlaying,
                    isLive = durationMs > 0 && (durationMs - positionMs) < LIVE_EDGE_EPSILON_MS,
                )
            }
            updateInOutProjection()
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
                playlistWindow = PlaylistWindow(
                    startEpochMs = segments.first().programDateTimeEpochMs,
                    endEpochMs = segments.last().programDateTimeEpochMs + segments.last().durationMs,
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

    fun seekTo(timelineMs: Long) {
        player.seekTo(timelineMs.coerceIn(0, player.duration.takeIf { it != C.TIME_UNSET } ?: 0L))
    }

    fun seekToLive() {
        player.duration.takeIf { it != C.TIME_UNSET }?.let { player.seekTo(it) }
    }

    fun togglePlayPause() {
        player.playWhenReady = !player.playWhenReady
    }

    fun stepFrame(forward: Boolean) {
        val delta = if (forward) FRAME_STEP_MS else -FRAME_STEP_MS
        seekTo(player.currentPosition + delta)
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

    private var exportJob: Job? = null

    fun export() {
        exportJob = viewModelScope.launch {
            _uiState.update { it.copy(isExporting = true, statusMessage = "Exporting...") }
            refreshPlaylistWindow() // freshest possible window right before committing to epoch math.

            val result = runCatching {
                val start = requireNotNull(inPointEpochMs) { "No in point set yet" }
                val end = requireNotNull(outPointEpochMs) { "No out point set yet" }
                ClipRange(startEpochMs = start, endEpochMs = end)
            }.fold(
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
