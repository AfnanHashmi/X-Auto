package com.clipcut.domain

import android.content.Context
import android.net.Uri
import android.util.Log
import androidx.media3.common.MediaItem
import androidx.media3.transformer.Composition
import androidx.media3.transformer.ExportException
import androidx.media3.transformer.ExportResult
import androidx.media3.transformer.ProgressHolder
import androidx.media3.transformer.Transformer
import com.clipcut.data.stream.PlaylistRepository
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
        val selected = SegmentSelector.selectForRange(playlist.segments, range.startEpochMs, range.endEpochMs)
            ?: error(
                "Requested range (${range.startEpochMs}..${range.endEpochMs}) isn't fully covered " +
                    "by the current DVR window -- it has either aged out or hasn't finished recording yet.",
            )

        val rawFile = File(context.cacheDir, "clip_raw_${range.startEpochMs}.mp4")
        // Raw concatenated LLHLS segments have no seek index (no sidx, just sequential moof/mdat),
        // which makes Transformer's ClippingConfiguration silently produce empty output against
        // them. Fix: transmux to a normal indexed MP4 first (pass 1), then clip that (pass 2).
        val remuxedFile = File(context.cacheDir, "clip_remux_${range.startEpochMs}.mp4")
        val outputFile = File(context.cacheDir, "clip_${range.startEpochMs}.mp4")
        try {
            onProgress("Downloading ${selected.size} segments...")
            segmentFetcher.downloadConcatenated(playlistUrl, playlist.initSegmentUri, selected, rawFile)

            transcode(input = rawFile, clip = null, output = remuxedFile) { percent ->
                onProgress("Preparing (pass 1/2): $percent%")
            }

            val firstSegmentPdt = selected.first().programDateTimeEpochMs
            val startMs = range.startEpochMs - firstSegmentPdt
            val endMs = range.endEpochMs - firstSegmentPdt
            transcode(input = remuxedFile, clip = startMs to endMs, output = outputFile) { percent ->
                onProgress("Trimming (pass 2/2): $percent%")
            }

            outputFile
        } finally {
            rawFile.delete()
            remuxedFile.delete()
        }
    }

    /** Runs one Transformer pass (transmux, optionally clipped) on the Main thread, reporting progress. */
    private suspend fun transcode(input: File, clip: Pair<Long, Long>?, output: File, onProgress: (Int) -> Unit) {
        withContext(Dispatchers.Main) {
            val completion = CompletableDeferred<Unit>()
            val itemBuilder = MediaItem.Builder().setUri(Uri.fromFile(input))
            if (clip != null) {
                itemBuilder.setClippingConfiguration(
                    MediaItem.ClippingConfiguration.Builder()
                        .setStartPositionMs(clip.first)
                        .setEndPositionMs(clip.second)
                        .build(),
                )
            }

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

            transformer.start(itemBuilder.build(), output.absolutePath)

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
