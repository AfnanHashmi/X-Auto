package com.clipcut.lan

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.media3.ui.PlayerView
import kotlinx.coroutines.delay
import java.net.Inet4Address
import java.net.NetworkInterface

/**
 * Standalone LAN low-latency buffer control: "Start Buffer" begins receiving/decoding/recording
 * immediately via [LanBufferSession], independent of whether this screen is open; reopening this
 * screen later just attaches to whatever's already running. "Kill" releases the player, closes the
 * socket, and deletes recorded chunks.
 *
 * Deliberately separate from [com.clipcut.ui.cut.CutScreen]/CutViewModel -- the real clipping tool
 * the user depends on for live broadcasts. This lets the LAN path be exercised and hardened without
 * any risk to that working, tested flow.
 */
class LanBufferActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent { LanBufferScreen() }
    }
}

@Composable
private fun LanBufferScreen() {
    val context = LocalContext.current
    var isActive by remember { mutableStateOf(LanBufferSession.isActive) }
    var bufferInfo by remember { mutableStateOf("buffer: (none yet)") }
    val localIp = remember { findLocalIpv4() ?: "<no wifi address>" }

    LaunchedEffect(Unit) {
        while (true) {
            isActive = LanBufferSession.isActive
            val range = LanBufferSession.bufferedRange()
            bufferInfo = if (range == null) {
                "buffer: (none yet)"
            } else {
                val spanSec = (range.second - range.first) / 1000
                "buffer: ${LanBufferSession.chunkCount()} chunks, ${spanSec}s span"
            }
            delay(1000)
        }
    }

    Column(modifier = Modifier.fillMaxSize().background(Color.Black)) {
        Box(modifier = Modifier.weight(1f).fillMaxWidth()) {
            if (isActive) {
                AndroidView(
                    modifier = Modifier.fillMaxSize(),
                    factory = { ctx -> PlayerView(ctx).apply { useController = false } },
                    update = { view -> view.player = LanBufferSession.currentPlayer() },
                )
            } else {
                Box(modifier = Modifier.fillMaxSize()) {
                    Text(
                        "Buffer not running",
                        color = Color.Gray,
                        modifier = Modifier.padding(24.dp),
                    )
                }
            }
        }
        Column(
            modifier = Modifier.fillMaxWidth().padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Text("Point OBS here:", color = Color.White, style = MaterialTheme.typography.titleSmall)
            Text("udp://$localIp:5000?pkt_size=1316", color = Color(0xFF80CBC4))
            Text(bufferInfo, color = Color(0xFFAED581), style = MaterialTheme.typography.bodySmall)

            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(
                    onClick = { LanBufferSession.start(context); isActive = true },
                    enabled = !isActive,
                ) { Text("Start Buffer") }

                OutlinedButton(
                    onClick = { LanBufferSession.stop(); isActive = false; bufferInfo = "buffer: (none yet)" },
                    enabled = isActive,
                ) { Text("Kill background processes") }
            }
        }
    }
}

private fun findLocalIpv4(): String? =
    NetworkInterface.getNetworkInterfaces().toList()
        .filter { it.isUp && !it.isLoopback }
        .flatMap { it.inetAddresses.toList() }
        .filterIsInstance<Inet4Address>()
        .firstOrNull()
        ?.hostAddress
