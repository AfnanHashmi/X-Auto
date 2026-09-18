package com.clipcut.data.stream.lan

import android.content.Context
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.TeeDataSource
import androidx.media3.datasource.UdpDataSource
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import java.util.concurrent.CopyOnWriteArrayList
import javax.inject.Inject
import javax.inject.Singleton

private const val CHUNK_DURATION_MS = 2_000L
private const val RETENTION_MS = 5 * 60 * 1000L

/**
 * Owns the rolling local recording of the LAN low-latency UDP feed: a [DataSource.Factory] that
 * tees playback bytes to disk (see [RollingChunkDataSink]), plus the chunk index and retention.
 *
 * Recording only happens while something is actually reading from the produced DataSource (i.e.
 * while the LAN player is active) -- there is no separate background recording path. This mirrors
 * the plan's explicit choice not to record while backgrounded: measured cost of a true background
 * recorder (~1.83 GB/h, ~40-80% of a battery over a 15h window, a persistent notification, and a
 * Doze exemption) wasn't judged worth it, especially since the user watches in-app anyway.
 */
@Singleton
class LanBuffer @Inject constructor(
    @ApplicationContext context: Context,
) {
    private val outputDir = File(context.cacheDir, "lan_buffer")
    private val chunks = CopyOnWriteArrayList<LanBufferChunk>()

    // Set while a review session is scrubbing over a frozen snapshot of these chunks. Retention
    // would otherwise happily delete a file out from under the player mid-playback, since it only
    // knows about elapsed time, not about what's currently on screen.
    @Volatile
    private var pinnedFromEpochMs: Long? = null

    /** Wraps a fresh [UdpDataSource] per `create()` with a tee to the rolling chunk sink. */
    val dataSourceFactory: DataSource.Factory = DataSource.Factory {
        TeeDataSource(
            UdpDataSource(),
            RollingChunkDataSink(outputDir, CHUNK_DURATION_MS, ::onChunkClosed),
        )
    }

    private fun onChunkClosed(chunk: LanBufferChunk) {
        chunks.add(chunk)
        val cutoff = minOf(chunk.endEpochMs - RETENTION_MS, pinnedFromEpochMs ?: Long.MAX_VALUE)
        val expired = chunks.filter { it.endEpochMs < cutoff }
        if (expired.isNotEmpty()) {
            chunks.removeAll(expired.toSet())
            expired.forEach { it.file.delete() }
        }
    }

    /**
     * Pins retention so nothing at or after [fromEpochMs] is pruned, however long ago it fell
     * outside the normal 5-minute window -- call when a review session starts scrubbing over a
     * snapshot of the buffer. [releaseRetentionHold] lets normal pruning resume; anything that
     * should already have expired is swept on the next chunk close.
     */
    fun holdRetention(fromEpochMs: Long) {
        pinnedFromEpochMs = fromEpochMs
    }

    fun releaseRetentionHold() {
        pinnedFromEpochMs = null
    }

    /** The currently buffered wall-clock span, or null if nothing has been recorded yet. */
    fun bufferedRange(): Pair<Long, Long>? {
        val first = chunks.firstOrNull() ?: return null
        val last = chunks.lastOrNull() ?: return null
        return first.startEpochMs to last.endEpochMs
    }

    /**
     * Chunks covering `[startEpochMs, endEpochMs)`, in order, or null if the range isn't fully
     * buffered -- same contract as `SegmentSelector.selectForRange` for the LLHLS path.
     */
    fun chunksCovering(startEpochMs: Long, endEpochMs: Long): List<LanBufferChunk>? {
        val covering = chunks.filter { it.endEpochMs > startEpochMs && it.startEpochMs < endEpochMs }
        if (covering.isEmpty()) return null
        if (covering.first().startEpochMs > startEpochMs) return null
        if (covering.last().endEpochMs < endEpochMs) return null
        return covering
    }

    fun chunkCount(): Int = chunks.size

    /**
     * Deletes every recorded chunk and clears the index -- call once the source has stopped.
     * Sweeps [outputDir] directly rather than only deleting what [chunks] currently tracks: a
     * leftover session (e.g. the app process was killed outright, bypassing any cleanup path) can
     * leave files on disk that were never in this instance's index, and a directory sweep cleans
     * those up too instead of leaking them indefinitely.
     */
    fun clear() {
        chunks.clear()
        outputDir.listFiles()?.forEach { it.delete() }
    }
}
