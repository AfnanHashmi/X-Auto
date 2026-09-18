package com.clipcut.lan

import android.content.Context
import android.net.Uri
import android.util.Log
import androidx.media3.common.MediaItem
import androidx.media3.transformer.Composition
import androidx.media3.transformer.EditedMediaItem
import androidx.media3.transformer.ExportException
import androidx.media3.transformer.ExportResult
import androidx.media3.transformer.ProgressHolder
import androidx.media3.transformer.Transformer
import com.clipcut.data.stream.lan.LanBuffer
import com.clipcut.data.stream.lan.LanBufferChunk
import com.clipcut.domain.GalleryStore
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import java.io.File

private const val PROGRESS_POLL_MS = 300L

/**
 * Exports a clip from the LAN local buffer for `[startEpochMs, endEpochMs)`.
 *
 * Deliberately a standalone duplicate of `Media3ClipExporter`'s two-pass transmux-then-clip
 * pattern rather than a shared refactor -- that class is the export path the user's live broadcast
 * clipping depends on, and this keeps LAN-mode export changes from ever being able to affect it.
 * Simpler than the LLHLS path: LAN chunks are already one muxed audio+video TS stream (recorded
 * straight off the wire in RollingChunkDataSink), so there's no separate audio rendition to fetch
 * and mux back in -- just concatenate, transmux, clip.
 */
class LanClipExporter(private val context: Context) {
    suspend fun export(
        lanBuffer: LanBuffer,
        startEpochMs: Long,
        endEpochMs: Long,
        onProgress: (String) -> Unit,
    ): Result<File> = runCatching {
        val chunks = lanBuffer.chunksCovering(startEpochMs, endEpochMs)
            ?: error(
                "Requested range ($startEpochMs..$endEpochMs) isn't fully covered by the local " +
                    "buffer -- it has either aged out or hasn't been recorded yet.",
            )

        val rawFile = File(context.cacheDir, "lan_clip_raw_$startEpochMs.ts")
        val remuxedFile = File(context.cacheDir, "lan_clip_remux_$startEpochMs.mp4")
        val outputFile = File(context.cacheDir, "lan_clip_$startEpochMs.mp4")
        try {
            onProgress("Concatenating ${chunks.size} chunks...")
            concatenate(chunks, rawFile)

            // Raw concatenated TS has no seek index, same reason Media3ClipExporter transmuxes
            // first: Transformer's ClippingConfiguration silently produces empty output otherwise.
            transcode(input = rawFile, clip = null, output = remuxedFile) { percent ->
                onProgress("Preparing (pass 1/2): $percent%")
            }

            val firstChunkStart = chunks.first().startEpochMs
            val clip = (startEpochMs - firstChunkStart) to (endEpochMs - firstChunkStart)
            transcode(input = remuxedFile, clip = clip, output = outputFile) { percent ->
                onProgress("Trimming (pass 2/2): $percent%")
            }

            onProgress("Saving to gallery...")
            GalleryStore.copyToGallery(context, outputFile)

            outputFile
        } finally {
            rawFile.delete()
            remuxedFile.delete()
        }
    }

    private fun concatenate(chunks: List<LanBufferChunk>, outputFile: File) {
        outputFile.outputStream().use { out ->
            chunks.forEach { chunk -> chunk.file.inputStream().use { it.copyTo(out) } }
        }
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

            transformer.start(EditedMediaItem.Builder(clippedItem(input, clip)).build(), output.absolutePath)

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
        const val TAG = "LanClipExporter"
    }
}
