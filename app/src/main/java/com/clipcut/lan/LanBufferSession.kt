package com.clipcut.lan

import android.content.Context
import androidx.media3.common.MediaItem
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.ProgressiveMediaSource
import androidx.media3.extractor.ExtractorsFactory
import androidx.media3.extractor.ts.TsExtractor
import com.clipcut.data.stream.lan.LanBuffer

private const val PORT = 5000

/**
 * Process-wide singleton owning the LAN low-latency player + rolling buffer, so "Start Buffer" and
 * actually watching are independent: starting begins receiving/decoding/recording immediately, and
 * opening the viewing screen later just attaches to whatever's already running -- no re-connect, no
 * second socket.
 *
 * Deliberately a plain singleton, not a foreground Service: this keeps the buffer alive only while
 * the app process is alive (open, foreground or recent), not through a full OS background kill /
 * Doze. That's a real limitation -- a true foreground service would survive more aggressive
 * backgrounding -- but it's simpler and sufficient for "I open the app, start buffering, then start
 * clipping" within one session. Worth hardening into a real Service if the buffer turns out to get
 * killed while the app is backgrounded in practice.
 */
object LanBufferSession {
    private var player: ExoPlayer? = null
    private var lanBuffer: LanBuffer? = null

    val isActive: Boolean get() = player != null

    fun start(context: Context) {
        if (player != null) return // already running.

        val buffer = LanBuffer(context.applicationContext)
        val extractorsFactory = ExtractorsFactory { arrayOf(TsExtractor()) }
        val source = ProgressiveMediaSource.Factory(buffer.dataSourceFactory, extractorsFactory)
            .createMediaSource(MediaItem.fromUri("udp://0.0.0.0:$PORT"))

        val newPlayer = ExoPlayer.Builder(context.applicationContext).build().apply {
            setMediaSource(source)
            prepare()
            playWhenReady = true
        }

        lanBuffer = buffer
        player = newPlayer
    }

    /** Releases the player (closes the UDP socket), and deletes all recorded chunk files. */
    fun stop() {
        player?.release()
        player = null
        lanBuffer?.clear()
        lanBuffer = null
    }

    fun currentPlayer(): ExoPlayer? = player

    fun bufferedRange(): Pair<Long, Long>? = lanBuffer?.bufferedRange()

    fun chunkCount(): Int = lanBuffer?.chunkCount() ?: 0
}
