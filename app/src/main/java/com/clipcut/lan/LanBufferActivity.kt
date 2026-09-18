package com.clipcut.lan

import android.net.Uri
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
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
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
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.datasource.FileDataSource
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.ProgressiveMediaSource
import androidx.media3.extractor.ExtractorsFactory
import androidx.media3.extractor.ts.TsExtractor
import androidx.media3.ui.PlayerView
import com.clipcut.ui.cut.TimelineScrubber
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.net.Inet4Address
import java.net.NetworkInterface

private const val DEFAULT_CLIP_SPAN_MS = 15_000L
private const val MIN_CLIP_SPAN_MS = 1_000L

/**
 * Standalone LAN low-latency buffer control + scrub/export: "Start Buffer" begins receiving/
 * decoding/recording immediately via [LanBufferSession], independent of whether this screen is
 * open; reopening this screen later just attaches to whatever's already running. Once the buffer
 * has content, drag in/out points against the growing local window and export a real clip via
 * [LanClipExporter] -- same verification pattern used throughout this project: check the result in
 * Movies/ClipCut, not a live in-app preview (LAN mode has no separate preview player yet).
 *
 * Deliberately separate from [com.clipcut.ui.cut.CutScreen]/CutViewModel -- the real clipping tool
 * the user depends on for live broadcasts. The production Cut screen links here via a plain Intent
 * (see its "LAN Mode" button), not through any shared ViewModel or nav-graph state, so this can be
 * exercised and hardened without any risk to that working, tested flow.
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
    val scope = rememberCoroutineScope()

    var isActive by remember { mutableStateOf(LanBufferSession.isActive) }
    var bufferInfo by remember { mutableStateOf("buffer: (none yet)") }
    var windowStartMs by remember { mutableStateOf<Long?>(null) }
    var windowEndMs by remember { mutableStateOf<Long?>(null) }
    var inPointEpochMs by remember { mutableStateOf<Long?>(null) }
    var outPointEpochMs by remember { mutableStateOf<Long?>(null) }
    var userAdjustedSelection by remember { mutableStateOf(false) }
    var isExporting by remember { mutableStateOf(false) }
    var statusMessage by remember { mutableStateOf<String?>(null) }
    val localIp = remember { findLocalIpv4() ?: "<no wifi address>" }

    // Preview plays a separate, short-lived player over a temp concatenation of the selected
    // chunks -- the live buffer keeps running/recording throughout (never stopped), just muted so
    // its audio doesn't play under the preview's. "Back to Live" un-mutes it and tears the preview
    // player down; it never touches LanBufferSession.
    var isPreviewing by remember { mutableStateOf(false) }
    var isPreparingPreview by remember { mutableStateOf(false) }
    var previewPlayer by remember { mutableStateOf<ExoPlayer?>(null) }
    var previewFile by remember { mutableStateOf<File?>(null) }

    fun stopPreview() {
        previewPlayer?.release()
        previewPlayer = null
        previewFile?.delete()
        previewFile = null
        LanBufferSession.currentPlayer()?.volume = 1f
        isPreviewing = false
    }

    DisposableEffect(Unit) {
        onDispose {
            previewPlayer?.release()
            previewFile?.delete()
            LanBufferSession.currentPlayer()?.volume = 1f
        }
    }

    LaunchedEffect(Unit) {
        while (true) {
            isActive = LanBufferSession.isActive
            val range = LanBufferSession.bufferedRange()
            if (range == null) {
                bufferInfo = "buffer: (none yet)"
                windowStartMs = null
                windowEndMs = null
            } else {
                val (start, end) = range
                bufferInfo = "buffer: ${LanBufferSession.chunkCount()} chunks, ${(end - start) / 1000}s span"
                windowStartMs = start
                windowEndMs = end
                if (!userAdjustedSelection) {
                    // Keep tracking "the last DEFAULT_CLIP_SPAN_MS" as the buffer grows from empty,
                    // rather than freezing at whatever tiny span existed on the first tick (LAN mode
                    // starts at zero, unlike the LLHLS window which already has a full history) --
                    // stops the moment the user drags a handle themselves.
                    outPointEpochMs = end
                    inPointEpochMs = (end - DEFAULT_CLIP_SPAN_MS).coerceAtLeast(start)
                }
            }
            delay(1000)
        }
    }

    Column(modifier = Modifier.fillMaxSize().background(Color.Black)) {
        Box(modifier = Modifier.weight(1f).fillMaxWidth()) {
            if (isPreviewing) {
                AndroidView(
                    modifier = Modifier.fillMaxSize(),
                    factory = { ctx -> PlayerView(ctx).apply { useController = false } },
                    update = { view -> view.player = previewPlayer },
                )
            } else if (isActive) {
                AndroidView(
                    modifier = Modifier.fillMaxSize(),
                    factory = { ctx -> PlayerView(ctx).apply { useController = false } },
                    update = { view -> view.player = LanBufferSession.currentPlayer() },
                )
            } else {
                Text("Buffer not running", color = Color.Gray, modifier = Modifier.padding(24.dp))
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
                    onClick = {
                        if (isPreviewing) stopPreview()
                        LanBufferSession.stop()
                        isActive = false
                        bufferInfo = "buffer: (none yet)"
                        windowStartMs = null
                        windowEndMs = null
                        inPointEpochMs = null
                        outPointEpochMs = null
                        userAdjustedSelection = false
                    },
                    enabled = isActive,
                ) { Text("Kill background processes") }
            }

            val start = windowStartMs
            val end = windowEndMs
            val inEpoch = inPointEpochMs
            val outEpoch = outPointEpochMs
            if (start != null && end != null && inEpoch != null && outEpoch != null) {
                val windowSpanMs = (end - start).coerceAtLeast(0)
                val inMs = (inEpoch - start).coerceIn(0, windowSpanMs)
                val outMs = (outEpoch - start).coerceIn(0, windowSpanMs)

                TimelineScrubber(
                    durationMs = windowSpanMs,
                    positionMs = windowSpanMs, // always the live edge -- there's no seekable "past" here.
                    inPointMs = inMs,
                    outPointMs = outMs,
                    onSeek = {}, // no seek concept: the live player is always at the live edge.
                    onDragInPoint = { timelineMs ->
                        userAdjustedSelection = true
                        val epoch = start + timelineMs
                        val maxEpoch = outEpoch - MIN_CLIP_SPAN_MS
                        inPointEpochMs = epoch.coerceAtMost(maxEpoch)
                    },
                    onDragOutPoint = { timelineMs ->
                        userAdjustedSelection = true
                        val epoch = start + timelineMs
                        val minEpoch = inEpoch + MIN_CLIP_SPAN_MS
                        outPointEpochMs = epoch.coerceAtLeast(minEpoch)
                    },
                    modifier = Modifier.padding(horizontal = 20.dp),
                )

                Text(
                    "Selected: ${(outEpoch - inEpoch) / 1000}s",
                    color = Color.White,
                    style = MaterialTheme.typography.bodySmall,
                )

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    if (isPreviewing) {
                        OutlinedButton(onClick = { stopPreview() }) { Text("Back to Live") }
                    } else {
                        Button(
                            onClick = {
                                val lanBuffer = LanBufferSession.currentLanBuffer() ?: return@Button
                                scope.launch {
                                    isPreparingPreview = true
                                    val chunks = lanBuffer.chunksCovering(inEpoch, outEpoch)
                                    if (chunks == null) {
                                        statusMessage = "Selection isn't fully buffered yet"
                                        isPreparingPreview = false
                                        return@launch
                                    }
                                    val file = File(context.cacheDir, "lan_preview_${inEpoch}.ts")
                                    withContext(Dispatchers.IO) {
                                        file.outputStream().use { out ->
                                            chunks.forEach { chunk -> chunk.file.inputStream().use { it.copyTo(out) } }
                                        }
                                    }
                                    val extractorsFactory = ExtractorsFactory { arrayOf(TsExtractor()) }
                                    val source = ProgressiveMediaSource.Factory({ FileDataSource() }, extractorsFactory)
                                        .createMediaSource(MediaItem.fromUri(Uri.fromFile(file)))
                                    previewPlayer = ExoPlayer.Builder(context).build().apply {
                                        setMediaSource(source)
                                        repeatMode = Player.REPEAT_MODE_ONE
                                        prepare()
                                        playWhenReady = true
                                    }
                                    previewFile = file
                                    // Keep the live buffer running/recording, just silence its audio
                                    // so it doesn't play under the preview's.
                                    LanBufferSession.currentPlayer()?.volume = 0f
                                    isPreviewing = true
                                    isPreparingPreview = false
                                }
                            },
                            enabled = !isPreparingPreview,
                            modifier = Modifier.weight(1f),
                        ) {
                            if (isPreparingPreview) {
                                CircularProgressIndicator(modifier = Modifier.padding(end = 8.dp))
                            }
                            Text("Preview")
                        }
                    }

                    Button(
                        onClick = {
                            val lanBuffer = LanBufferSession.currentLanBuffer() ?: return@Button
                            scope.launch {
                                isExporting = true
                                LanClipExporter(context)
                                    .export(lanBuffer, inEpoch, outEpoch) { status -> statusMessage = status }
                                    .fold(
                                        onSuccess = { file ->
                                            statusMessage = "Exported: ${file.name} -- check Movies/ClipCut"
                                        },
                                        onFailure = { e -> statusMessage = "Export failed: ${e.message}" },
                                    )
                                isExporting = false
                            }
                        },
                        enabled = !isExporting,
                        modifier = Modifier.weight(1f),
                    ) {
                        if (isExporting) {
                            CircularProgressIndicator(modifier = Modifier.padding(end = 8.dp))
                        }
                        Text("Export clip")
                    }
                }
            }

            statusMessage?.let {
                Text(it, color = Color.White, style = MaterialTheme.typography.bodySmall)
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
