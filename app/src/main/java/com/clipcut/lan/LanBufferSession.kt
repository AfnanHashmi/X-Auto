package com.clipcut.lan

import android.content.Context
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.hls.HlsMediaSource
import androidx.media3.datasource.DefaultHttpDataSource
import com.clipcut.data.stream.lan.MediaMtxConfig
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

private const val RETRY_MS = 3_000L

/**
 * Owns THE player: one ExoPlayer over MediaMTX's LL-HLS playlist. The server keeps the scrub-back
 * window, so live and review are the same player -- live is its default position, scrubbing back
 * is a plain seek. Nothing is recorded on the phone.
 */
object LanBufferSession {
    private var player: ExoPlayer? = null
    private var scope: CoroutineScope? = null

    fun currentPlayer(): ExoPlayer? = player

    fun start(context: Context, host: String): ExoPlayer {
        stop()
        val s = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
        val httpFactory = DefaultHttpDataSource.Factory().setConnectTimeoutMs(5_000).setReadTimeoutMs(8_000)
        val source = HlsMediaSource.Factory(httpFactory)
            .createMediaSource(MediaItem.fromUri(MediaMtxConfig.hlsUrl(host)))
        val p = ExoPlayer.Builder(context.applicationContext).build()
        p.addListener(
            object : Player.Listener {
                override fun onPlayerError(error: PlaybackException) {
                    // Playlist 404s until OBS publishes, and a long pause can fall behind the live
                    // window; ExoPlayer never self-heals from either, so retry from live.
                    s.launch {
                        delay(RETRY_MS)
                        if (player === p) {
                            p.seekToDefaultPosition()
                            p.prepare()
                        }
                    }
                }
            },
        )
        p.setMediaSource(source)
        p.prepare()
        p.playWhenReady = true
        player = p
        scope = s
        return p
    }

    fun stop() {
        scope?.cancel()
        scope = null
        player?.release()
        player = null
    }
}
