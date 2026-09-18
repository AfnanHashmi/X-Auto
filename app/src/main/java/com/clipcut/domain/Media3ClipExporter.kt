package com.clipcut.domain

import android.content.Context
import android.net.Uri
import android.util.Log
import androidx.media3.common.MediaItem
import androidx.media3.transformer.Composition
import androidx.media3.transformer.EditedMediaItem
import androidx.media3.transformer.EditedMediaItemSequence
import androidx.media3.transformer.ExportException
import androidx.media3.transformer.ExportResult
import androidx.media3.transformer.ProgressHolder
import androidx.media3.transformer.Transformer
import com.clipcut.data.stream.PlaylistRepository
import com.clipcut.data.stream.PlaylistSegment
import com.clipcut.data.stream.SegmentFetcher
import com.clipcut.data.stream.SegmentSelector
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import java.io.File
import javax.inject.Inject

private const val PROGRESS_POLL_MS = 300L

class Media3ClipExporter @Inject constructor(
    @ApplicationContext private val context: Context,
    private val playlistRepository: PlaylistRepository,
    private val segmentFetcher: SegmentFetcher,
) : ClipExporter {

    override suspend fun export(range: ClipRange, onProgress: (String) -> Unit): Result<File> = runCatching {
        onProgress("Fetching playlist...")
        val playlistUrl = playlistRepository.currentPlaylistUrl()
        val playlist = segmentFetcher.fetchMediaPlaylist(playlistUrl)
        val selectedVideo = SegmentSelector.selectForRange(playlist.segments, range.startEpochMs, range.endEpochMs)
            ?: error(
                "Requested range (${range.startEpochMs}..${range.endEpochMs}) isn't fully covered " +
                    "by the current DVR window -- it has either aged out or hasn't finished recording yet.",
            )

        // OvenMediaEngine LLHLS publishes audio as a separate rendition from the video variant,
        // so the video segments alone carry no audio track. Select its segments for the same
        // wall-clock range too, if the range happens to not be covered fall back to video-only
        // rather than failing the whole export over a missing soundtrack.
        val audioPlaylist = runCatching { segmentFetcher.fetchAudioMediaPlaylist(playlistUrl) }.getOrNull()
        val selectedAudio = audioPlaylist?.segments?.let {
            SegmentSelector.selectForRange(it, range.startEpochMs, range.endEpochMs)
        }

        val rawVideoFile = File(context.cacheDir, "clip_raw_video_${range.startEpochMs}.mp4")
        val remuxedVideoFile = File(context.cacheDir, "clip_remux_video_${range.startEpochMs}.mp4")
        val rawAudioFile = File(context.cacheDir, "clip_raw_audio_${range.startEpochMs}.mp4")
        val remuxedAudioFile = File(context.cacheDir, "clip_remux_audio_${range.startEpochMs}.mp4")
        val outputFile = File(context.cacheDir, "clip_${range.startEpochMs}.mp4")
        try {
            onProgress("Downloading ${selectedVideo.size} video segments...")
            segmentFetcher.downloadConcatenated(playlistUrl, playlist.initSegmentUri, selectedVideo, rawVideoFile)
            // Raw concatenated LLHLS segments have no seek index (no sidx, just sequential
            // moof/mdat), which makes Transformer's ClippingConfiguration silently produce empty
            // output against them. Fix: transmux to a normal indexed MP4 first (pass 1), then
            // clip (and mux with audio, if any) in pass 2.
            transcode(input = rawVideoFile, clip = null, output = remuxedVideoFile) { percent ->
                onProgress("Preparing video (pass 1/2): $percent%")
            }
            val videoClipMs = clipMsWithin(selectedVideo, range)

            val audioClipMs = if (selectedAudio != null) {
                onProgress("Downloading ${selectedAudio.size} audio segments...")
                segmentFetcher.downloadConcatenated(playlistUrl, audioPlaylist!!.initSegmentUri, selectedAudio, rawAudioFile)
                transcode(input = rawAudioFile, clip = null, output = remuxedAudioFile) { percent ->
                    onProgress("Preparing audio (pass 1/2): $percent%")
                }
                clipMsWithin(selectedAudio, range)
            } else {
                null
            }

            if (audioClipMs != null) {
                muxAndTrim(
                    videoFile = remuxedVideoFile,
                    videoClip = videoClipMs,
                    audioFile = remuxedAudioFile,
                    audioClip = audioClipMs,
                    output = outputFile,
                ) { percent -> onProgress("Trimming (pass 2/2): $percent%") }
            } else {
                transcode(input = remuxedVideoFile, clip = videoClipMs, output = outputFile) { percent ->
                    onProgress("Trimming (pass 2/2): $percent%")
                }
            }

            onProgress("Saving to gallery...")
            GalleryStore.copyToGallery(context, outputFile)

            outputFile
        } finally {
            rawVideoFile.delete()
            remuxedVideoFile.delete()
            rawAudioFile.delete()
            remuxedAudioFile.delete()
        }
    }

    /** [range] mapped onto [selected]'s own remuxed-file-local timeline (starting at 0 at its first segment). */
    private fun clipMsWithin(selected: List<PlaylistSegment>, range: ClipRange): Pair<Long, Long> {
        val firstSegmentPdt = selected.first().programDateTimeEpochMs
        return (range.startEpochMs - firstSegmentPdt) to (range.endEpochMs - firstSegmentPdt)
    }

    private fun clippedItem(file: File, clip: Pair<Long, Long>?): MediaItem {
        val builder = MediaItem.Builder().setUri(Uri.fromFile(file))
        if (clip != null) {
            builder.setClippingConfiguration(
                MediaItem.ClippingConfiguration.Builder()
                    .setStartPositionMs(clip.first)
                    .setEndPositionMs(clip.second)
                    .build(),
            )
        }
        return builder.build()
    }

    /** Runs one Transformer pass (transmux, optionally clipped) on the Main thread, reporting progress. */
    private suspend fun transcode(input: File, clip: Pair<Long, Long>?, output: File, onProgress: (Int) -> Unit) {
        runTransformer(EditedMediaItem.Builder(clippedItem(input, clip)).build(), output, onProgress)
    }

    /**
     * Muxes a video-only file and an audio-only file into one output, trimmed to each file's own
     * (independently computed) clip range. Two parallel sequences -- one video-only, one
     * audio-only -- combine into a single track pair; this is the same mechanism used for adding
     * a music track to a silent video.
     */
    private suspend fun muxAndTrim(
        videoFile: File,
        videoClip: Pair<Long, Long>,
        audioFile: File,
        audioClip: Pair<Long, Long>,
        output: File,
        onProgress: (Int) -> Unit,
    ) {
        val videoSequence = EditedMediaItemSequence.Builder(
            EditedMediaItem.Builder(clippedItem(videoFile, videoClip)).setRemoveAudio(true).build(),
        ).build()
        val audioSequence = EditedMediaItemSequence.Builder(
            EditedMediaItem.Builder(clippedItem(audioFile, audioClip)).setRemoveVideo(true).build(),
        ).build()
        val composition = Composition.Builder(videoSequence, audioSequence).build()
        runTransformer(composition, output, onProgress)
    }

    /** Runs [input] (a single item or a full [Composition]) through Transformer, polling progress until done. */
    private suspend fun runTransformer(input: Any, output: File, onProgress: (Int) -> Unit) {
        withContext(Dispatchers.Main) {
            val completion = CompletableDeferred<Unit>()
            val transformer = Transformer.Builder(context)
                .addListener(
                    object : Transformer.Listener {
                        override fun onCompleted(composition: Composition, exportResult: ExportResult) {
                            Log.d(TAG, "onCompleted $output durationMs=${exportResult.durationMs}")
                            completion.complete(Unit)
                        }

                        override fun onError(
                            composition: Composition,
                            exportResult: ExportResult,
                            exportException: ExportException,
                        ) {
                            Log.e(TAG, "onError $output", exportException)
                            completion.completeExceptionally(exportException)
                        }
                    },
                )
                .build()

            when (input) {
                is Composition -> transformer.start(input, output.absolutePath)
                is EditedMediaItem -> transformer.start(input, output.absolutePath)
                else -> error("Unsupported Transformer input type: ${input::class}")
            }

            try {
                val progressHolder = ProgressHolder()
                while (!completion.isCompleted) {
                    if (transformer.getProgress(progressHolder) == Transformer.PROGRESS_STATE_AVAILABLE) {
                        onProgress(progressHolder.progress)
                    }
                    delay(PROGRESS_POLL_MS)
                }
                completion.await()
            } catch (e: CancellationException) {
                transformer.cancel()
                throw e
            }
        }
    }

    private companion object {
        const val TAG = "ClipExporter"
    }
}
