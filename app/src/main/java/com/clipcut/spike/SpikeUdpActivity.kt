package com.clipcut.spike

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.ProgressiveMediaSource
import androidx.media3.extractor.ExtractorsFactory
import androidx.media3.extractor.ts.TsExtractor
import androidx.media3.ui.PlayerView
import com.clipcut.data.stream.lan.LanBuffer
import kotlinx.coroutines.delay
import java.net.Inet4Address
import java.net.NetworkInterface

private const val PORT = 5000

/**
 * Phase 1 spike (throwaway): can ExoPlayer play a live, unbounded MPEG-TS feed pushed over UDP
 * straight from OBS? Everything in the LAN low-latency plan depends on this working, so it's proven
 * in isolation before any buffer/export work. Deliberately standalone -- no DI, no nav, no shared
 * state with the real app -- so it can be deleted in one commit.
 *
 * Launch: adb shell am start -n com.clipcut/.spike.SpikeUdpActivity
 */
class SpikeUdpActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent { SpikeScreen() }
    }
}

@Composable
private fun SpikeScreen() {
    val context = androidx.compose.ui.platform.LocalContext.current
    var status by remember { mutableStateOf("idle") }
    var error by remember { mutableStateOf<String?>(null) }
    val localIp = remember { findLocalIpv4() ?: "<no wifi address>" }

    // Phase 2 spike: same LanBuffer the real app will use, wired in here first so buffering and
    // retention can be visually confirmed before touching CutViewModel/PlayerModule.
    val lanBuffer = remember { LanBuffer(context.applicationContext) }
    var bufferInfo by remember { mutableStateOf("buffer: (none yet)") }

    LaunchedEffect(lanBuffer) {
        while (true) {
            val range = lanBuffer.bufferedRange()
            bufferInfo = if (range == null) {
                "buffer: (none yet)"
            } else {
                val spanSec = (range.second - range.first) / 1000
                "buffer: ${lanBuffer.chunkCount()} chunks, ${spanSec}s span"
            }
            delay(1000)
        }
    }

    val player = remember {
        ExoPlayer.Builder(context).build().apply {
            // Explicit TsExtractor rather than DefaultExtractorsFactory: sniffing on a live socket
            // is unreliable since it needs to peek at a stream that's already mid-flight.
            val extractorsFactory = ExtractorsFactory { arrayOf(TsExtractor()) }
            val source = ProgressiveMediaSource.Factory(lanBuffer.dataSourceFactory, extractorsFactory)
                .createMediaSource(MediaItem.fromUri("udp://0.0.0.0:$PORT"))
            setMediaSource(source)
            prepare()
            playWhenReady = true
        }
    }

    DisposableEffect(player) {
        val listener = object : Player.Listener {
            override fun onPlaybackStateChanged(state: Int) {
                status = when (state) {
                    Player.STATE_IDLE -> "IDLE"
                    Player.STATE_BUFFERING -> "BUFFERING"
                    Player.STATE_READY -> "READY (playing)"
                    Player.STATE_ENDED -> "ENDED"
                    else -> "unknown($state)"
                }
            }

            override fun onPlayerError(e: PlaybackException) {
                error = "${e.errorCodeName}: ${e.message}"
            }
        }
        player.addListener(listener)
        onDispose {
            player.removeListener(listener)
            player.release()
        }
    }

    Column(modifier = Modifier.fillMaxSize().background(Color.Black)) {
        Box(modifier = Modifier.weight(1f).fillMaxWidth()) {
            AndroidView(
                modifier = Modifier.fillMaxSize(),
                factory = { ctx -> PlayerView(ctx).apply { this.player = player; useController = false } },
            )
        }
        Column(
            modifier = Modifier.fillMaxWidth().padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Text("Point OBS here:", color = Color.White, style = MaterialTheme.typography.titleSmall)
            Text("udp://$localIp:$PORT?pkt_size=1316", color = Color(0xFF80CBC4))
            Text("state: $status", color = Color.White, style = MaterialTheme.typography.bodySmall)
            Text(bufferInfo, color = Color(0xFFAED581), style = MaterialTheme.typography.bodySmall)
            error?.let {
                Text("error: $it", color = Color(0xFFEF5350), style = MaterialTheme.typography.bodySmall)
            }
        }
    }
}

/** The address OBS needs to target; the phone's LAN IP changes, so show it rather than hardcode it. */
private fun findLocalIpv4(): String? =
    NetworkInterface.getNetworkInterfaces().toList()
        .filter { it.isUp && !it.isLoopback }
        .flatMap { it.inetAddresses.toList() }
        .filterIsInstance<Inet4Address>()
        .firstOrNull()
        ?.hostAddress
