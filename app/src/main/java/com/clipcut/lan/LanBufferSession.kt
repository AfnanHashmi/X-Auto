package com.clipcut.lan

import android.content.Context
import android.util.Log
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.hls.HlsMediaSource
import androidx.media3.exoplayer.analytics.AnalyticsListener
import androidx.media3.exoplayer.source.LoadEventInfo
import androidx.media3.exoplayer.source.MediaLoadData
import java.io.IOException
import androidx.media3.datasource.DefaultHttpDataSource
import com.clipcut.data.stream.lan.MediaMtxConfig
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

private const val RETRY_MS = 3_000L
private const val TAG = "LanSession"

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
        val httpFactory = DefaultHttpDataSource.Factory()
            .setConnectTimeoutMs(5_000)
            .setReadTimeoutMs(15_000)
        val source = HlsMediaSource.Factory(httpFactory)
            .createMediaSource(
                // min == max == 1x turns off ExoPlayer's clock-based live speed control; the
                // controller does its own clock-free catch-up (see LanReviewController).
                MediaItem.Builder()
                    .setUri(MediaMtxConfig.hlsUrl(host))
                    .setLiveConfiguration(
                        MediaItem.LiveConfiguration.Builder().setMinPlaybackSpeed(1f).setMaxPlaybackSpeed(1f).build(),
                    )
                    .build(),
            )
        val p = ExoPlayer.Builder(context.applicationContext).build()
        p.addListener(
            object : Player.Listener {
                // Debug trace: WHY playback started/stopped (reason codes: 1=user, 2=audio focus lost,
                // 3=becoming noisy, 4=remote, 5=end of item, 6=suppressed too long).
                override fun onPlayWhenReadyChanged(playWhenReady: Boolean, reason: Int) {
                    Log.d(TAG, "playWhenReady=$playWhenReady reason=$reason")
                }

                override fun onPlaybackStateChanged(state: Int) {
                    Log.d(TAG, "state=$state (1 idle,2 buffering,3 ready,4 ended) liveOffset=${p.currentLiveOffset}")
                }

                override fun onPlaybackSuppressionReasonChanged(reason: Int) {
                    Log.d(TAG, "suppression=$reason")
                }

                override fun onPlayerError(error: PlaybackException) {
                    Log.e(TAG, "player error ${error.errorCodeName}", error)
                }
            },
        )
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
        p.addAnalyticsListener(
            object : AnalyticsListener {
                override fun onLoadError(
                    eventTime: AnalyticsListener.EventTime,
                    loadEventInfo: LoadEventInfo,
                    mediaLoadData: MediaLoadData,
                    error: IOException,
                    wasCanceled: Boolean,
                ) {
                    Log.w(TAG, "loadError ${loadEventInfo.uri.lastPathSegment} canceled=$wasCanceled: $error")
                }

                override fun onDroppedVideoFrames(eventTime: AnalyticsListener.EventTime, droppedFrames: Int, elapsedMs: Long) {
                    Log.w(TAG, "droppedFrames=$droppedFrames in ${elapsedMs}ms")
                }

                override fun onAudioUnderrun(eventTime: AnalyticsListener.EventTime, bufferSize: Int, bufferSizeMs: Long, elapsedSinceLastFeedMs: Long) {
                    Log.w(TAG, "audioUnderrun elapsedSinceLastFeed=${elapsedSinceLastFeedMs}ms")
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
