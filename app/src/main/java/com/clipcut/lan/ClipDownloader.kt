package com.clipcut.lan

import android.content.Context
import android.net.Uri
import androidx.media3.common.MediaItem
import androidx.media3.transformer.Composition
import androidx.media3.transformer.EditedMediaItem
import androidx.media3.transformer.ExportException
import androidx.media3.transformer.ExportResult
import androidx.media3.transformer.ProgressHolder
import androidx.media3.transformer.Transformer
import com.clipcut.data.stream.lan.MediaMtxConfig
import com.clipcut.domain.GalleryStore
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.time.Instant
import java.util.concurrent.TimeUnit

// MediaMTX starts a clip at the keyframe before the requested time (and hides the overshoot with an
// edit list that many gallery players ignore), so the download is padded and then trimmed exactly.
private const val PAD_BEFORE_MS = 2_000L
private const val PAD_AFTER_MS = 500L

// Measured against MediaMTX v1.21 by matching identical frames: the HLS playlist's
// EXT-X-PROGRAM-DATE-TIME runs exactly 2000 ms BEHIND the recording clock the playback API uses,
// so an epoch read off the HLS player must be shifted by this to address the same frame.
private const val HLS_TO_RECORDING_OFFSET_MS = 2_000L

/**
 * Export = one HTTP GET, then an exact trim. MediaMTX cuts [startEpochMs, endEpochMs) out of its own recording and
 * returns a finished, muxed MP4; the phone just saves it and copies it to the gallery.
 */
class ClipDownloader(private val context: Context) {
    private val client = OkHttpClient.Builder()
        .connectTimeout(5, TimeUnit.SECONDS)
        .readTimeout(120, TimeUnit.SECONDS)
        .build()

    suspend fun export(startEpochMs: Long, endEpochMs: Long, onProgress: (String) -> Unit): Result<File> = withContext(Dispatchers.IO) {
        runCatching {
            val host = MediaMtxConfig.host(context)
            val spanMs = endEpochMs - startEpochMs
            val url = MediaMtxConfig.clipUrl(
                host,
                Instant.ofEpochMilli(startEpochMs + HLS_TO_RECORDING_OFFSET_MS - PAD_BEFORE_MS).toString(),
                (spanMs + PAD_BEFORE_MS + PAD_AFTER_MS) / 1000.0,
            )
            val raw = File(context.cacheDir, "lan_clip_raw_$startEpochMs.mp4")
            val out = File(context.cacheDir, "lan_clip_$startEpochMs.mp4")
            try {
                onProgress("Downloading clip from PC...")
                client.newCall(Request.Builder().url(url).build()).execute().use { resp ->
                    check(resp.isSuccessful) { "PC returned ${resp.code}: ${resp.body?.string()?.take(120)}" }
                    resp.body!!.byteStream().use { input -> raw.outputStream().use { input.copyTo(it) } }
                }
                check(raw.length() > 0) { "PC returned an empty clip" }
                trim(raw, PAD_BEFORE_MS, PAD_BEFORE_MS + spanMs, out) { onProgress("Trimming: $it%") }
                onProgress("Saving to gallery...")
                GalleryStore.copyToGallery(context, out)
                out
            } finally {
                raw.delete()
            }
        }
    }

    /** One Transformer pass clipping [startMs, endMs) of [input] into [output], on the Main thread. */
    private suspend fun trim(input: File, startMs: Long, endMs: Long, output: File, onProgress: (Int) -> Unit) {
        withContext(Dispatchers.Main) {
            val completion = CompletableDeferred<Unit>()
            val transformer = Transformer.Builder(context)
                .addListener(
                    object : Transformer.Listener {
                        override fun onCompleted(composition: Composition, exportResult: ExportResult) {
                            completion.complete(Unit)
                        }

                        override fun onError(
                            composition: Composition,
                            exportResult: ExportResult,
                            exportException: ExportException,
                        ) {
                            completion.completeExceptionally(exportException)
                        }
                    },
                )
                .build()
            val item = MediaItem.Builder().setUri(Uri.fromFile(input))
                .setClippingConfiguration(
                    MediaItem.ClippingConfiguration.Builder().setStartPositionMs(startMs).setEndPositionMs(endMs).build(),
                )
                .build()
            transformer.start(EditedMediaItem.Builder(item).build(), output.absolutePath)
            try {
                val holder = ProgressHolder()
                while (!completion.isCompleted) {
                    if (transformer.getProgress(holder) == Transformer.PROGRESS_STATE_AVAILABLE) onProgress(holder.progress)
                    delay(300)
                }
                completion.await()
            } catch (e: CancellationException) {
                transformer.cancel()
                throw e
            }
        }
    }
}
