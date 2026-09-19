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
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.media3.ui.PlayerView
import com.clipcut.data.stream.lan.MediaMtxConfig
import com.clipcut.ui.cut.TimelineScrubber
import kotlinx.coroutines.launch

/**
 * The app's only screen. One player over MediaMTX's LL-HLS stream ([LanBufferSession]); the PC
 * keeps the scrub-back window, so opening the app is all it takes -- no Start Buffer, no phone-side
 * recording, no background service. Live is the player's default position, "Jump to live" returns
 * to it, and export downloads a clip MediaMTX cuts from its own recording ([ClipDownloader]).
 */
class LanBufferActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent { LanBufferScreen() }
    }

    override fun onDestroy() {
        if (isFinishing) LanBufferSession.stop()
        super.onDestroy()
    }
}

@Composable
private fun LanBufferScreen() {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var host by remember { mutableStateOf(MediaMtxConfig.host(context)) }
    var hostDraft by remember { mutableStateOf(host) }
    var isExporting by remember { mutableStateOf(false) }
    var statusMessage by remember { mutableStateOf<String?>(null) }

    val player = remember(host) { LanBufferSession.start(context, host) }
    val controller = remember(player) { LanReviewController(player) }
    DisposableEffect(controller) { onDispose { controller.release() } }

    // Never make noise behind other apps: pause when backgrounded, resume only if it was playing.
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner, player) {
        var wasPlaying = true
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_STOP -> {
                    wasPlaying = player.playWhenReady
                    player.playWhenReady = false
                }
                Lifecycle.Event.ON_START -> if (wasPlaying) player.playWhenReady = true
                else -> Unit
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    val state by controller.uiState.collectAsState()

    Column(modifier = Modifier.fillMaxSize().background(Color.Black)) {
        Box(modifier = Modifier.weight(1f).fillMaxWidth()) {
            AndroidView(
                modifier = Modifier.fillMaxSize(),
                factory = { ctx -> PlayerView(ctx).apply { useController = false } },
                update = { view -> view.player = player },
                onRelease = { view -> view.player = null },
            )
            if (!state.hasContent) {
                Text(
                    "Waiting for OBS via ${MediaMtxConfig.hlsUrl(host)}",
                    color = Color.Gray,
                    modifier = Modifier.padding(24.dp),
                )
            }
        }
        Column(
            modifier = Modifier.fillMaxWidth().padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                    value = hostDraft,
                    onValueChange = { hostDraft = it },
                    label = { Text("PC address") },
                    singleLine = true,
                    modifier = Modifier.weight(1f),
                )
                OutlinedButton(
                    onClick = {
                        MediaMtxConfig.setHost(context, hostDraft)
                        host = hostDraft.trim()
                    },
                    enabled = hostDraft.trim() != host,
                ) { Text("Connect") }
            }

            TimelineScrubber(
                durationMs = state.durationMs,
                positionMs = state.positionMs,
                inPointMs = state.inPointMs,
                outPointMs = state.outPointMs,
                onSeek = controller::seekTo,
                onDragInPoint = controller::dragInPoint,
                onDragOutPoint = controller::dragOutPoint,
                modifier = Modifier.padding(horizontal = 20.dp),
            )

            Text(
                "Selected: ${state.selectedDurationMs / 1000}s" + if (state.isLive) "  -  LIVE" else "",
                color = Color.White,
                style = MaterialTheme.typography.bodySmall,
            )

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Row {
                    TextButton(onClick = { controller.stepFrame(forward = false) }, enabled = state.hasContent) {
                        Text("|<", color = Color.White)
                    }
                    TextButton(onClick = { controller.togglePlayPause() }, enabled = state.hasContent) {
                        Text(if (state.isPlaying) "Pause" else "Play", color = Color.White)
                    }
                    TextButton(onClick = { controller.stepFrame(forward = true) }, enabled = state.hasContent) {
                        Text(">|", color = Color.White)
                    }
                }
                OutlinedButton(onClick = { controller.jumpToLive() }, enabled = state.hasContent && !state.isLive) {
                    Text("Jump to live")
                }
            }

            if (state.isPreviewing) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    OutlinedButton(onClick = { controller.exitPreview() }) { Text("Cancel") }
                    Button(
                        onClick = {
                            val (inEpoch, outEpoch) = controller.inOutEpochRange() ?: return@Button
                            scope.launch {
                                isExporting = true
                                ClipDownloader(context).export(inEpoch, outEpoch) { statusMessage = it }.fold(
                                    onSuccess = { file ->
                                        statusMessage = "Exported: ${file.name} -- check Movies/ClipCut"
                                        CaptionPostActivity.start(context, file.absolutePath)
                                    },
                                    onFailure = { e -> statusMessage = "Export failed: ${e.message}" },
                                )
                                isExporting = false
                            }
                        },
                        enabled = !isExporting,
                        modifier = Modifier.weight(1f),
                    ) {
                        if (isExporting) CircularProgressIndicator(modifier = Modifier.padding(end = 8.dp))
                        Text("Export clip")
                    }
                }
            } else {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    OutlinedButton(
                        onClick = {
                            controller.setInAtPlayhead()
                            statusMessage = "IN marked"
                        },
                        enabled = state.hasContent,
                        modifier = Modifier.weight(1f),
                    ) { Text("Set IN") }
                    OutlinedButton(
                        onClick = {
                            controller.setOutAtPlayhead()
                            statusMessage = "OUT marked"
                        },
                        enabled = state.hasContent,
                        modifier = Modifier.weight(1f),
                    ) { Text("Set OUT") }
                }
                Button(
                    onClick = { controller.enterPreview() },
                    enabled = state.hasContent && state.selectedDurationMs > 0,
                    modifier = Modifier.fillMaxWidth(),
                ) { Text("Preview") }
            }

            statusMessage?.let { Text(it, color = Color.White, style = MaterialTheme.typography.bodySmall) }
        }
    }
}
