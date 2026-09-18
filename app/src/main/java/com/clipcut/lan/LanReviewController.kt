package com.clipcut.lan

import android.content.Context
import android.net.Uri
import androidx.media3.common.MediaItem
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.SeekParameters
import androidx.media3.exoplayer.source.ProgressiveMediaSource
import androidx.media3.extractor.ExtractorsFactory
import androidx.media3.extractor.ts.TsExtractor
import com.clipcut.data.stream.lan.ChunkSpanningDataSource
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
 * Presents a frozen snapshot of buffered chunks as ONE continuous, seekable timeline, via
 * [ChunkSpanningDataSource] feeding a single [ProgressiveMediaSource] + single [TsExtractor] --
 * i.e. one real MPEG-TS stream, not N separate files stitched at the player level. This replaced
 * an earlier `ConcatenatingMediaSource2`-per-chunk design that stalled (`STATE_BUFFERING`) at
 * every ~2s chunk boundary, since the player had to open and re-sync a fresh extractor per file;
 * with one extractor over one spanning source there is nothing to re-sync at all, and seeking
 * anywhere in the buffer is a real seek against a real PCR-based seek map, not a per-chunk swap.
 *
 * Because the spanning source's bytes are exactly the chunks' bytes back-to-back with no gaps, the
 * timeline's `0..duration` IS the epoch timeline shifted by [windowStartEpochMs] -- no separate
 * epoch-origin lookup is needed the way the old LLHLS `CutViewModel` needed one from
 * `#EXT-X-PROGRAM-DATE-TIME`.
 *
 * In/out points are tracked as absolute wall-clock epoch millis, exactly like the old
 * `CutViewModel` -- see its class doc (recovered from git history) for why: it's what keeps a
 * selection pinned to the same real moment independent of how position is displayed.
 *
 * The chunk list is a **frozen snapshot** taken at construction, not live-appended -- appending
 * would shift the timeline under the user mid-review. [LanBuffer.holdRetention] is engaged for the
 * lifetime of this controller so normal pruning can't delete a chunk out from under playback;
 * [release] lets it resume.
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
        val spanningSource = ProgressiveMediaSource.Factory({ ChunkSpanningDataSource(chunks) }, extractorsFactory)
            .createMediaSource(MediaItem.fromUri(Uri.parse("lanbuffer://window")))

        setMediaSource(spanningSource)
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
     * Nudges the raw player position by one frame. Works across a chunk boundary for free: with
     * one continuous stream there is only one `currentPosition`, so stepping past what was a chunk
     * boundary just lands on the next/previous frame -- no special-casing needed.
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
