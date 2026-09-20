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
import androidx.compose.ui.Alignment
import androidx.compose.foundation.layout.height
import androidx.compose.material3.Surface
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
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
import com.clipcut.ui.theme.ClipCutTheme
import kotlinx.coroutines.async
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
        // Themed like the rest of the app; the screen still paints its own black background.
        setContent { ClipCutTheme { LanBufferScreen() } }
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
    var showConnection by remember { mutableStateOf(false) }
    var statusMessage by remember { mutableStateOf<String?>(null) }
    // The slow part of an export (PC cut + download + trim) starts as soon as the preview opens, so by the
    // time Export is tapped it is usually done. Keyed by the marked range; dropped if the preview is left.
    var prepared by remember { mutableStateOf<Pair<Pair<Long, Long>, kotlinx.coroutines.Deferred<Result<java.io.File>>>?>(null) }

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

    // Re-keyed on the markers: dragging IN/OUT during the preview restarts it for the new range, once the
    // handle has stopped moving.
    LaunchedEffect(state.isPreviewing, state.inPointMs, state.outPointMs) {
        if (state.isPreviewing) {
            if (prepared != null) kotlinx.coroutines.delay(700)
            val range = controller.inOutEpochRange() ?: return@LaunchedEffect
            if (prepared?.first != range) {
                prepared?.second?.cancel()
                prepared = range to scope.async { ClipDownloader(context).prepare(range.first, range.second) { } }
            }
        } else {
            prepared?.second?.cancel()
            prepared = null
        }
    }

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
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Text(
                    "PC  $host",
                    style = MaterialTheme.typography.labelMedium,
                    color = if (state.hasContent) Color(0xFF5BD6A2) else Color(0xFFFF9A9A),
                )
                TextButton(onClick = { showConnection = !showConnection }) {
                    Text(if (showConnection) "Hide" else "Change", color = Color(0xFF9AA4B2))
                }
            }
            if (showConnection) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                    OutlinedTextField(
                        value = hostDraft,
                        onValueChange = { hostDraft = it },
                        label = { Text("PC address") },
                        singleLine = true,
                        modifier = Modifier.weight(1f),
                    )
                    FilledTonalButton(
                        onClick = {
                            MediaMtxConfig.setHost(context, hostDraft)
                            host = hostDraft.trim()
                            showConnection = false
                        },
                        enabled = hostDraft.trim() != host,
                    ) { Text("Connect") }
                }
            }

            TimelineScrubber(
                durationMs = state.durationMs,
                positionMs = state.positionMs,
                inPointMs = state.inPointMs,
                outPointMs = state.outPointMs,
                hasSelection = state.hasSelection,
                onSeek = controller::seekTo,
                onDragInPoint = controller::dragInPoint,
                onDragOutPoint = controller::dragOutPoint,
                modifier = Modifier.padding(horizontal = 20.dp),
            )

            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Text(
                    when {
                        !state.hasSelection -> "Set IN to start a clip"
                        !state.outMarked -> "${state.selectedDurationMs / 1000}s so far - set OUT"
                        else -> "Clip: ${state.selectedDurationMs / 1000}s"
                    },
                    color = Color.White,
                    style = MaterialTheme.typography.titleSmall,
                )
                if (state.isLive) {
                    Surface(color = Color(0xFFD64545), shape = MaterialTheme.shapes.extraSmall) {
                        Text(
                            "LIVE",
                            color = Color.White,
                            style = MaterialTheme.typography.labelMedium,
                            modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp),
                        )
                    }
                }
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Row {
                    TextButton(onClick = { controller.stepFrame(forward = false) }, enabled = state.hasContent) {
                        Text("< frame", color = Color.White)
                    }
                    TextButton(onClick = { controller.togglePlayPause() }, enabled = state.hasContent) {
                        Text(if (state.isPlaying) "Pause" else "Play", color = Color.White)
                    }
                    TextButton(onClick = { controller.stepFrame(forward = true) }, enabled = state.hasContent) {
                        Text("frame >", color = Color.White)
                    }
                }
                FilledTonalButton(onClick = { controller.jumpToLive() }, enabled = state.hasContent && !state.isLive) {
                    Text("Go live")
                }
            }

            if (state.isPreviewing) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    OutlinedButton(onClick = { controller.exitPreview() }, modifier = Modifier.height(52.dp)) { Text("Cancel") }
                    Button(
                        onClick = {
                            val range = controller.inOutEpochRange() ?: return@Button
                            scope.launch {
                                isExporting = true
                                statusMessage = "Preparing clip..."
                                val job = prepared?.takeIf { it.first == range }?.second
                                    ?: scope.async { ClipDownloader(context).prepare(range.first, range.second) { statusMessage = it } }
                                        .also { prepared = range to it }
                                val downloader = ClipDownloader(context)
                                job.await().mapCatching { downloader.saveToGallery(it).getOrThrow() }.fold(
                                    onSuccess = { file ->
                                        prepared = null
                                        statusMessage = "Exported: ${file.name} -- check Movies/ClipCut"
                                        CaptionPostActivity.start(context, file.absolutePath)
                                    },
                                    onFailure = { e ->
                                        prepared = null
                                        statusMessage = "Export failed: ${e.message}"
                                    },
                                )
                                isExporting = false
                            }
                        },
                        enabled = !isExporting,
                        modifier = Modifier.weight(1f).height(52.dp),
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
                    FilledTonalButton(
                        onClick = {
                            controller.setInAtPlayhead()
                            statusMessage = "IN marked"
                        },
                        enabled = state.hasContent,
                        modifier = Modifier.weight(1f).height(52.dp),
                    ) { Text("Set IN", style = MaterialTheme.typography.labelLarge) }
                    FilledTonalButton(
                        onClick = {
                            controller.setOutAtPlayhead()
                            statusMessage = "OUT marked"
                        },
                        enabled = state.hasContent && state.hasSelection,
                        modifier = Modifier.weight(1f).height(52.dp),
                    ) { Text("Set OUT", style = MaterialTheme.typography.labelLarge) }
                }
                Button(
                    onClick = { controller.enterPreview() },
                    enabled = state.hasContent && state.outMarked && state.selectedDurationMs > 0,
                    modifier = Modifier.fillMaxWidth().height(52.dp),
                ) { Text("Preview", style = MaterialTheme.typography.labelLarge) }
            }

            statusMessage?.let { Text(it, color = Color.White, style = MaterialTheme.typography.bodySmall) }
        }
    }
}
