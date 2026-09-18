package com.clipcut.data.stream.lan

import android.net.Uri
import androidx.media3.common.C
import androidx.media3.datasource.BaseDataSource
import androidx.media3.datasource.DataSourceException
import androidx.media3.datasource.DataSpec
import java.io.RandomAccessFile

/**
 * Presents a frozen list of [LanBufferChunk] files as ONE contiguous byte stream, so a single
 * `ProgressiveMediaSource` + `TsExtractor` can read/seek across the whole recorded window as one
 * continuous MPEG-TS stream -- exactly what the format is designed to support (TS is concatenable
 * by construction, with no per-file container header to reconcile, unlike fMP4).
 *
 * Replaces an earlier `ConcatenatingMediaSource2`-per-chunk approach, which stalled
 * (`STATE_BUFFERING`) at every ~2s chunk boundary while the player opened and re-synced a fresh
 * extractor per file. With one extractor over one spanning source, there are no boundaries to
 * re-sync at all -- `TsExtractor` builds its normal PCR-based seek map across the entire span, so
 * seeking anywhere in the buffer is a real seek, not a per-chunk player swap.
 *
 * The chunk list is a snapshot taken at construction (by the caller); every file in it is already
 * closed/finalized by [RollingChunkDataSink] before it's ever added to the index, so file lengths
 * read here are stable for the lifetime of this source.
 */
class ChunkSpanningDataSource(private val chunks: List<LanBufferChunk>) :
    BaseDataSource(/* isNetwork= */ false) {

    // offsets[i] = the starting byte offset of chunks[i] within the spanned stream.
    private val offsets: LongArray = LongArray(chunks.size)
    private val totalLength: Long

    private var uri: Uri = Uri.EMPTY
    private var currentIndex = -1
    private var currentFile: RandomAccessFile? = null
    private var bytesRemainingInSpan: Long = 0L

    init {
        var cumulative = 0L
        for (i in chunks.indices) {
            offsets[i] = cumulative
            cumulative += chunks[i].file.length()
        }
        totalLength = cumulative
    }

    override fun open(dataSpec: DataSpec): Long {
        uri = dataSpec.uri
        transferInitializing(dataSpec)

        if (chunks.isEmpty() || dataSpec.position < 0 || dataSpec.position > totalLength) {
            throw DataSourceException(DataSourceException.POSITION_OUT_OF_RANGE)
        }

        openFileAt(dataSpec.position)
        bytesRemainingInSpan = if (dataSpec.length != C.LENGTH_UNSET.toLong()) {
            dataSpec.length
        } else {
            totalLength - dataSpec.position
        }

        transferStarted(dataSpec)
        return bytesRemainingInSpan
    }

    override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
        if (length == 0) return 0
        if (bytesRemainingInSpan <= 0L) return C.RESULT_END_OF_INPUT

        val toRead = minOf(length.toLong(), bytesRemainingInSpan).toInt()
        while (true) {
            val file = currentFile ?: return C.RESULT_END_OF_INPUT
            val read = file.read(buffer, offset, toRead)
            if (read == -1) {
                // This chunk file is exhausted -- roll to the next one and keep going, completely
                // transparently to the extractor reading from this DataSource.
                if (!advanceToNextFile()) return C.RESULT_END_OF_INPUT
                continue
            }
            bytesRemainingInSpan -= read
            bytesTransferred(read)
            return read
        }
    }

    override fun getUri(): Uri = uri

    override fun close() {
        currentFile?.close()
        currentFile = null
        currentIndex = -1
        transferEnded()
    }

    /** Binary-searches [offsets] for the chunk covering [position], opens it, seeks within it. */
    private fun openFileAt(position: Long) {
        var lo = 0
        var hi = chunks.size - 1
        var index = 0
        while (lo <= hi) {
            val mid = (lo + hi) / 2
            if (offsets[mid] <= position) {
                index = mid
                lo = mid + 1
            } else {
                hi = mid - 1
            }
        }
        currentFile?.close()
        val raf = RandomAccessFile(chunks[index].file, "r")
        raf.seek(position - offsets[index])
        currentFile = raf
        currentIndex = index
    }

    private fun advanceToNextFile(): Boolean {
        val next = currentIndex + 1
        if (next >= chunks.size) return false
        currentFile?.close()
        currentFile = RandomAccessFile(chunks[next].file, "r")
        currentIndex = next
        return true
    }
}
