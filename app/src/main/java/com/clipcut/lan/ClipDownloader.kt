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
import org.json.JSONArray
import java.io.File
import java.time.Instant
import java.time.OffsetDateTime
import java.util.concurrent.TimeUnit

// MediaMTX starts a clip at the keyframe before the requested time (and hides the overshoot with an
// edit list that many gallery players ignore), so the download is padded and then trimmed exactly.
private const val PAD_BEFORE_MS = 2_000L
private const val PAD_AFTER_MS = 500L

// Measured against MediaMTX v1.21 by matching identical frames (three files, three OBS sessions):
// the HLS playlist's EXT-X-PROGRAM-DATE-TIME (which is what the player's epoch comes from) and the
// recording clock the playback API uses AGREE EXACTLY, except in the FIRST recording file of each
// publish session, whose name/start time is 2000 ms too late. Every hourly roll after that is exact.
// So an epoch read off the player only needs shifting by this when it lands in that first file --
// shifting always (as this once did) made every clip past the first hour ~2 s late.
private const val FIRST_FILE_LATE_MS = 2_000L

// Must match `recordSegmentDuration` in relay/mediamtx.yml. Rolls happen every this long, counted
// from the true start of the session (the first file's listed start minus FIRST_FILE_LATE_MS).
private const val RECORD_SEGMENT_MS = 600_000L

/**
 * Export = one HTTP GET, then an exact trim ([prepare], started as soon as the preview opens), then the gallery save. MediaMTX cuts [startEpochMs, endEpochMs) out of its own recording and
 * returns a finished, muxed MP4; the phone just saves it and copies it to the gallery.
 */
class ClipDownloader(private val context: Context) {
    private val client = OkHttpClient.Builder()
        .connectTimeout(5, TimeUnit.SECONDS)
        .readTimeout(120, TimeUnit.SECONDS)
        .build()

    /**
     * Everything slow, ahead of the Export tap: the PC cuts the clip, the phone downloads it and trims it
     * exactly. Returns the trimmed file in the cache; nothing is saved to the gallery yet.
     */
    suspend fun prepare(startEpochMs: Long, endEpochMs: Long, onProgress: (String) -> Unit): Result<File> = withContext(Dispatchers.IO) {
        runCatching {
            val host = MediaMtxConfig.host(context)
            val spanMs = endEpochMs - startEpochMs
            val offsetMs = recordingOffsetMs(host, startEpochMs)
            val url = MediaMtxConfig.clipUrl(
                host,
                Instant.ofEpochMilli(startEpochMs + offsetMs - PAD_BEFORE_MS).toString(),
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
                out
            } catch (e: Throwable) {
                out.delete()
                throw e
            } finally {
                raw.delete()
            }
        }
    }

    /** Copies a [prepare]d clip to Movies/ClipCut. */
    suspend fun saveToGallery(prepared: File): Result<File> = withContext(Dispatchers.IO) {
        runCatching {
            GalleryStore.copyToGallery(context, prepared)
            prepared
        }
    }

    /**
     * How far to shift a player epoch to address the same frame in the PC's recording: 2000 ms while
     * [startEpochMs] is inside the first recording file of its session, 0 afterwards (see
     * [FIRST_FILE_LATE_MS]). Fails loudly rather than guessing: a wrong offset silently ships a clip
     * that starts ~2 s off, and nothing downstream re-checks it.
     */
    private fun recordingOffsetMs(host: String, startEpochMs: Long): Long {
        val body = client.newCall(Request.Builder().url(MediaMtxConfig.listUrl(host)).build()).execute().use { resp ->
            check(resp.isSuccessful) { "PC recording list returned ${resp.code}" }
            resp.body!!.string()
        }
        val runs = JSONArray(body)
        // Each entry is one unbroken run of recording; its `start` is the first file's (late) name.
        val runStart = (0 until runs.length())
            .map { OffsetDateTime.parse(runs.getJSONObject(it).getString("start")).toInstant().toEpochMilli() }
            .filter { it <= startEpochMs + FIRST_FILE_LATE_MS }
            .maxOrNull() ?: error("That moment is not in any recording on the PC")
        val firstRollAt = runStart - FIRST_FILE_LATE_MS + RECORD_SEGMENT_MS
        return if (startEpochMs < firstRollAt) FIRST_FILE_LATE_MS else 0L
    }

    /** One Transformer pass clipping [startMs, endMs) of [input] into [output], on the Main thread. */
    private suspend fun trim(input: File, startMs: Long, endMs: Long, output: File, onProgress: (Int) -> Unit) {
        withContext(Dispatchers.Main) {
            val completion = CompletableDeferred<Unit>()
            val transformer = Transformer.Builder(context)
                // Re-encode only the first GOP up to the exact start and copy the rest, instead of transcoding the whole clip.
                .experimentalSetTrimOptimizationEnabled(true)
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
