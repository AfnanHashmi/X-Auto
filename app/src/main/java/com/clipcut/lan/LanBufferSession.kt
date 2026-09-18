package com.clipcut.lan

import android.content.Context
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.ProgressiveMediaSource
import androidx.media3.extractor.ExtractorsFactory
import androidx.media3.extractor.ts.TsExtractor
import com.clipcut.data.stream.lan.LanBuffer
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

private const val PORT = 5000
private const val PLAYER_ERROR_RETRY_MS = 5_000L

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
    private var sessionScope: CoroutineScope? = null

    // Two independent reasons the live player can be silenced: the in-app Preview player taking
    // over audio, and the whole app being backgrounded (see LanBufferForegroundService, which
    // drives the latter off ProcessLifecycleOwner so the stream never makes noise behind other
    // apps). Either one alone is enough to mute; both must clear to unmute.
    private var isPreviewMuted = false
    private var isBackgroundMuted = false

    val isActive: Boolean get() = player != null

    fun start(context: Context) {
        if (player != null) return // already running.

        val buffer = LanBuffer(context.applicationContext)
        val extractorsFactory = ExtractorsFactory { arrayOf(TsExtractor()) }
        val source = ProgressiveMediaSource.Factory(buffer.dataSourceFactory, extractorsFactory)
            .createMediaSource(MediaItem.fromUri("udp://0.0.0.0:$PORT"))

        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
        val newPlayer = ExoPlayer.Builder(context.applicationContext).build().apply {
            // UdpDataSource has an 8s socket timeout; if the OBS relay drops for longer than that
            // (it did, more than once, in real testing), the read throws and ExoPlayer moves to a
            // terminal error state -- it does NOT retry on its own, ever, even once the feed comes
            // back. Without this, the live view goes black permanently on the first hiccup. Ported
            // from the old (deleted) LLHLS CutViewModel, which had the identical problem/fix.
            addListener(
                object : Player.Listener {
                    override fun onPlayerError(error: PlaybackException) {
                        scope.launch {
                            delay(PLAYER_ERROR_RETRY_MS)
                            if (player === this@apply) prepare()
                        }
                    }
                },
            )
            setMediaSource(source)
            prepare()
            playWhenReady = true
        }

        lanBuffer = buffer
        player = newPlayer
        sessionScope = scope
        applyVolume()
    }

    /** Releases the player (closes the UDP socket), and deletes all recorded chunk files. */
    fun stop() {
        sessionScope?.cancel()
        sessionScope = null
        player?.release()
        player = null
        lanBuffer?.clear()
        lanBuffer = null
        isPreviewMuted = false
        isBackgroundMuted = false
    }

    fun currentPlayer(): ExoPlayer? = player

    fun currentLanBuffer(): LanBuffer? = lanBuffer

    fun bufferedRange(): Pair<Long, Long>? = lanBuffer?.bufferedRange()

    fun chunkCount(): Int = lanBuffer?.chunkCount() ?: 0

    fun setPreviewMuted(muted: Boolean) {
        isPreviewMuted = muted
        applyVolume()
    }

    fun setBackgroundMuted(muted: Boolean) {
        isBackgroundMuted = muted
        applyVolume()
    }

    private fun applyVolume() {
        player?.volume = if (isPreviewMuted || isBackgroundMuted) 0f else 1f
    }

    /**
     * Disables/re-enables the live player's VIDEO renderer specifically, not the whole player --
     * while a [LanReviewController] is active it has its own separate ExoPlayer decoding the same
     * broadcast, and this live player's video was never actually visible during review (the
     * PlayerView is pointed at the review player instead), just silently still decoding and
     * fighting the review player for the phone's hardware video decoder. That contention is what
     * caused real, reported lag. Disabling the track leaves the underlying chunk loading --
     * `RollingChunkDataSink` taps raw bytes as they're read, upstream of decoding -- and audio
     * completely unaffected, so recording keeps working exactly as before.
     */
    fun setLiveVideoDecodingEnabled(enabled: Boolean) {
        val p = player ?: return
        p.trackSelectionParameters = p.trackSelectionParameters.buildUpon()
            .setTrackTypeDisabled(C.TRACK_TYPE_VIDEO, !enabled)
            .build()
    }
}
