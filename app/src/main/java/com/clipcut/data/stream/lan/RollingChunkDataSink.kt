package com.clipcut.data.stream.lan

import androidx.media3.datasource.DataSink
import androidx.media3.datasource.DataSpec
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.io.OutputStream

/** One recorded chunk of the raw UDP/TS feed: [file] covers wall-clock `[startEpochMs, endEpochMs)`. */
data class LanBufferChunk(val file: File, val startEpochMs: Long, val endEpochMs: Long)

/**
 * A Media3 [DataSink] that tees the live UDP feed to disk in rolling ~[chunkDurationMs] chunks.
 * Device wall-clock (receipt time) is stamped per chunk -- there's no server-side PDT here, and
 * "when the phone received it" is all a local scrubber needs.
 *
 * Cuts are time-based, not TS-packet-boundary-aware, so a chunk may not start exactly on a
 * keyframe. Acceptable because `Media3ClipExporter`'s existing two-pass transmux already snaps
 * the actual cut point to the nearest preceding sync sample -- the same mechanism that already
 * handles this for the LLHLS path.
 *
 * Retention/pruning is deliberately NOT this class's job: it only cuts and reports finished
 * chunks via [onChunkClosed], since the caller ([LanBuffer]) already owns the chunk index and is
 * a single, better place to decide what to keep.
 */
class RollingChunkDataSink(
    private val outputDir: File,
    private val chunkDurationMs: Long,
    private val onChunkClosed: (LanBufferChunk) -> Unit,
) : DataSink {
    private var currentStream: OutputStream? = null
    private var currentFile: File? = null
    private var currentStartMs: Long = 0L

    init {
        outputDir.mkdirs()
    }

    @Throws(IOException::class)
    override fun open(dataSpec: DataSpec) {
        startNewChunk()
    }

    @Throws(IOException::class)
    override fun write(buffer: ByteArray, offset: Int, length: Int) {
        val now = System.currentTimeMillis()
        if (now - currentStartMs >= chunkDurationMs) {
            closeCurrentChunk(now)
            startNewChunk()
        }
        currentStream?.write(buffer, offset, length)
    }

    @Throws(IOException::class)
    override fun close() {
        closeCurrentChunk(System.currentTimeMillis())
    }

    private fun startNewChunk() {
        currentStartMs = System.currentTimeMillis()
        val file = File(outputDir, "chunk_$currentStartMs.ts")
        currentFile = file
        currentStream = FileOutputStream(file)
    }

    private fun closeCurrentChunk(endMs: Long) {
        currentStream?.close()
        currentStream = null
        val file = currentFile ?: return
        currentFile = null
        if (file.length() > 0) {
            onChunkClosed(LanBufferChunk(file, currentStartMs, endMs))
        } else {
            file.delete()
        }
    }
}
