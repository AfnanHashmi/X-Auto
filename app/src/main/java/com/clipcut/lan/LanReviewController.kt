package com.clipcut.lan

import android.content.Context
import android.net.Uri
import androidx.media3.common.MediaItem
import androidx.media3.datasource.FileDataSource
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.SeekParameters
import androidx.media3.exoplayer.source.ConcatenatingMediaSource2
import androidx.media3.exoplayer.source.ProgressiveMediaSource
import androidx.media3.extractor.ExtractorsFactory
import androidx.media3.extractor.ts.TsExtractor
import com.clipcut.data.stream.lan.LanBuffer
import com.clipcut.data.stream.lan.LanBufferChunk
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

/**
 * Presents a frozen snapshot of buffered chunks as ONE continuous, seekable timeline -- the fix
 * for the old per-chunk scrub player, which looped a single ~2s file and rebuilt the whole player
 * on every chunk-boundary crossing. Built from [ConcatenatingMediaSource2], whose per-source
 * duration is supplied as each chunk's wall-clock span (`endEpochMs - startEpochMs`); because the
 * chunks are recorded back-to-back with no gaps, the merged timeline's `0..duration` IS the epoch
 * timeline shifted by [windowStartEpochMs] -- no separate epoch-origin lookup is needed the way
 * the old LLHLS `CutViewModel` needed one from `#EXT-X-PROGRAM-DATE-TIME`.
 *
 * Rejected: a playlist of per-chunk [MediaItem]s via `setMediaSources`. Each item is its own
 * timeline window there -- `currentPosition` resets at every transition and per-item `duration` is
 * often `TIME_UNSET` for headerless raw TS -- reintroducing the same class of bug this replaces.
 *
 * In/out points are tracked as absolute wall-clock epoch millis, exactly like the old
 * `CutViewModel` -- see its class doc (recovered from git history) for why: it's what keeps a
 * selection pinned to the same real moment independent of how position is displayed.
 *
 * The chunk list is a **frozen snapshot** taken at construction, not live-appended -- appending
 * would shift the merged timeline under the user mid-review. [LanBuffer.holdRetention] is engaged
 * for the lifetime of this controller so normal pruning can't delete a chunk out from under
 * playback; [release] lets it resume.
 */
class LanReviewController(
    context: Context,
    private val lanBuffer: LanBuffer,
    chunks: List<LanBufferChunk>,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    val windowStartEpochMs: Long = chunks.first().startEpochMs
    val windowEndEpochMs: Long = chunks.last().endEpochMs
    private val windowSpanMs = (windowEndEpochMs - windowStartEpochMs).coerceAtLeast(0)

    val player: ExoPlayer = ExoPlayer.Builder(context).build().apply {
        // EXACT (the default) can land before a chunk's first keyframe, since chunks are cut on
        // wall-clock rather than at sync-sample boundaries; CLOSEST_SYNC trades a little precision
        // for guaranteed-valid frames instead of a decode artifact at the seek target.
        setSeekParameters(SeekParameters.CLOSEST_SYNC)

        val extractorsFactory = ExtractorsFactory { arrayOf(TsExtractor()) }
        val chunkSourceFactory = ProgressiveMediaSource.Factory({ FileDataSource() }, extractorsFactory)
        val playlist = ConcatenatingMediaSource2.Builder().apply {
            chunks.forEach { chunk ->
                val source = chunkSourceFactory.createMediaSource(MediaItem.fromUri(Uri.fromFile(chunk.file)))
                // NOTE: this parameter is milliseconds (initialPlaceholderDurationMs), not micros.
                add(source, chunk.endEpochMs - chunk.startEpochMs)
            }
        }.build()

        setMediaSource(playlist)
        prepare()
        playWhenReady = false
    }

    private val _uiState = MutableStateFlow(
        LanReviewUiState(durationMs = windowSpanMs, positionMs = windowSpanMs, outPointMs = windowSpanMs),
    )
    val uiState: StateFlow<LanReviewUiState> = _uiState.asStateFlow()

    // Default selection: the last DEFAULT_CLIP_SPAN_MS of the frozen window, i.e. "what just
    // happened" -- same default the old CutViewModel used.
    private var inPointEpochMs: Long = (windowEndEpochMs - DEFAULT_CLIP_SPAN_MS).coerceAtLeast(windowStartEpochMs)
    private var outPointEpochMs: Long = windowEndEpochMs
    private var previewLoopActive = false

    init {
        lanBuffer.holdRetention(windowStartEpochMs)
        // Start parked at the live edge of the frozen window, paused, matching how the old screen
        // opened onto the current live position rather than the start of the DVR window.
        player.seekTo(windowSpanMs)
        updateInOutProjection()
        scope.launch { pollPlayerState() }
    }

    private suspend fun pollPlayerState() {
        while (scope.isActive) {
            val positionMs = player.currentPosition.coerceIn(0, windowSpanMs)
            _uiState.update { it.copy(positionMs = positionMs, isPlaying = player.isPlaying) }

            if (previewLoopActive) {
                val s = _uiState.value
                if (s.outPointMs > s.inPointMs && s.positionMs >= s.outPointMs) {
                    seekTo(s.inPointMs)
                }
            }

            delay(POSITION_POLL_MS)
        }
    }

    private fun projectToTimeline(epochMs: Long): Long = (epochMs - windowStartEpochMs).coerceIn(0, windowSpanMs)

    /**
     * Pushes in/out epoch points into [_uiState] immediately. Called synchronously from the drag/
     * mark handlers (not just the 200ms [pollPlayerState] tick) so a handle follows the user's
     * finger every frame instead of visibly lagging behind.
     */
    private fun updateInOutProjection() {
        _uiState.update {
            it.copy(
                inPointMs = projectToTimeline(inPointEpochMs),
                outPointMs = projectToTimeline(outPointEpochMs),
            )
        }
    }

    /** [timelineMs] is in the frozen window's `0..durationMs` coordinate system. */
    fun seekTo(timelineMs: Long) {
        player.seekTo(timelineMs.coerceIn(0, windowSpanMs))
    }

    fun togglePlayPause() {
        player.playWhenReady = !player.playWhenReady
    }

    /**
     * Nudges the raw player position by one frame. Works across a chunk boundary for free: on the
     * merged [ConcatenatingMediaSource2] timeline there is only one continuous `currentPosition`,
     * so stepping past 0 or the end of one chunk's span just lands inside the next/previous one --
     * no special-casing needed, unlike a per-item playlist would require.
     */
    fun stepFrame(forward: Boolean) {
        val frameRate = player.videoFormat?.frameRate?.takeIf { it > 0f && it.isFinite() }
        val stepMs = frameRate?.let { (1000.0 / it).toLong().coerceAtLeast(1L) } ?: FRAME_STEP_MS
        val delta = if (forward) stepMs else -stepMs
        player.seekTo((player.currentPosition + delta).coerceIn(0, windowSpanMs))
    }

    fun dragInPoint(timelineMs: Long) {
        val epoch = windowStartEpochMs + timelineMs
        val maxEpoch = outPointEpochMs - MIN_CLIP_SPAN_MS
        inPointEpochMs = epoch.coerceAtMost(maxEpoch)
        updateInOutProjection()
    }

    fun dragOutPoint(timelineMs: Long) {
        val epoch = windowStartEpochMs + timelineMs
        val minEpoch = inPointEpochMs + MIN_CLIP_SPAN_MS
        outPointEpochMs = epoch.coerceAtLeast(minEpoch)
        updateInOutProjection()
    }

    /** Stamps the IN marker at wherever the playhead is paused right now. */
    fun setInAtPlayhead() {
        val epoch = windowStartEpochMs + _uiState.value.positionMs
        val maxEpoch = outPointEpochMs - MIN_CLIP_SPAN_MS
        inPointEpochMs = epoch.coerceAtMost(maxEpoch)
        updateInOutProjection()
    }

    /** Stamps the OUT marker at wherever the playhead is paused right now. */
    fun setOutAtPlayhead() {
        val epoch = windowStartEpochMs + _uiState.value.positionMs
        val minEpoch = inPointEpochMs + MIN_CLIP_SPAN_MS
        outPointEpochMs = epoch.coerceAtLeast(minEpoch)
        updateInOutProjection()
    }

    /** Seeks to the in point and starts looping playback within [in, out) until [exitPreview]. */
    fun enterPreview() {
        previewLoopActive = true
        _uiState.update { it.copy(isPreviewing = true) }
        seekTo(_uiState.value.inPointMs)
        player.playWhenReady = true
    }

    fun exitPreview() {
        previewLoopActive = false
        player.playWhenReady = false
        _uiState.update { it.copy(isPreviewing = false) }
    }

    /** The current in/out selection as absolute epoch millis, for [LanClipExporter]. */
    fun inOutEpochRange(): Pair<Long, Long> = inPointEpochMs to outPointEpochMs

    fun release() {
        scope.cancel()
        player.release()
        lanBuffer.releaseRetentionHold()
    }
}
