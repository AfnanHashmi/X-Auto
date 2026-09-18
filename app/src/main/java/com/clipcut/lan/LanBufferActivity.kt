package com.clipcut.lan

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
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
import androidx.core.content.ContextCompat
import androidx.media3.ui.PlayerView
import com.clipcut.ui.cut.TimelineScrubber
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.net.Inet4Address
import java.net.NetworkInterface

private const val DEFAULT_CLIP_SPAN_MS = 15_000L

/**
 * The app's only screen: OBS/UDP low-latency buffer control + scrub/export. "Start Buffer" starts
 * [LanBufferForegroundService], which begins receiving/decoding/recording into [LanBufferSession]
 * immediately and keeps running (behind a persistent notification) independent of whether this
 * screen -- or the app at all -- is open; reopening this screen later just attaches to whatever's
 * already running.
 *
 * Two viewing modes, matching the old (pre-LAN) LLHLS Cut screen's workflow as closely as the
 * different backend allows -- see [LanReviewController]'s doc comment for the mechanics:
 * - **Live**: [LanBufferSession]'s own continuously-playing, sub-second-latency player.
 * - **Review**: entered by touching the scrubber at all (drag, tap, or an in/out handle), which
 *   freezes a snapshot of the buffer into a [LanReviewController] presenting it as one continuous,
 *   seekable, pausable timeline -- drag backwards, pause, mark IN, play forward, pause, mark OUT,
 *   Preview, Export. "Jump to live" releases the controller and returns to Live.
 *
 * The previous online-URL (LLHLS) path this lived alongside -- CutScreen/CutViewModel, plus the
 * caption/post-to-X pipeline hanging off it -- has been removed. There is no other clipping tool
 * in this app anymore; export is gallery-only, with no in-app path to posting.
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
    var liveWindowStartMs by remember { mutableStateOf<Long?>(null) }
    var liveWindowEndMs by remember { mutableStateOf<Long?>(null) }
    var isExporting by remember { mutableStateOf(false) }
    var statusMessage by remember { mutableStateOf<String?>(null) }
    val localIp = remember { findLocalIpv4() ?: "<no wifi address>" }

    var reviewController by remember { mutableStateOf<LanReviewController?>(null) }

    fun releaseReview() {
        reviewController?.release()
        reviewController = null
    }

    DisposableEffect(Unit) {
        onDispose { reviewController?.release() }
    }

    // POST_NOTIFICATIONS (API 33+) is what makes the foreground service's "running in background"
    // notification actually visible -- the service itself still works without it, but the whole
    // point here is the user can see/trust it's running, so ask for it right before Start Buffer.
    var pendingStart by remember { mutableStateOf(false) }
    val notificationPermissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) {
        if (pendingStart) {
            LanBufferForegroundService.start(context)
            isActive = true
            pendingStart = false
        }
    }
    fun startBuffer() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            pendingStart = true
            notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
        } else {
            LanBufferForegroundService.start(context)
            isActive = true
        }
    }

    LaunchedEffect(Unit) {
        while (true) {
            isActive = LanBufferSession.isActive
            val range = LanBufferSession.bufferedRange()
            if (range == null) {
                bufferInfo = "buffer: (none yet)"
                liveWindowStartMs = null
                liveWindowEndMs = null
            } else {
                val (start, end) = range
                bufferInfo = "buffer: ${LanBufferSession.chunkCount()} chunks, ${(end - start) / 1000}s span"
                liveWindowStartMs = start
                liveWindowEndMs = end
            }
            delay(1000)
        }
    }

    /**
     * Any interaction with the scrubber while parked on the live view (no review session yet)
     * starts one: freezes a snapshot of everything buffered up to right now via
     * [LanReviewController]. Returns null if there's nothing buffered to review yet.
     */
    fun ensureReviewController(): LanReviewController? {
        reviewController?.let { return it }
        val lanBuffer = LanBufferSession.currentLanBuffer() ?: return null
        val range = lanBuffer.bufferedRange() ?: return null
        val chunks = lanBuffer.chunksCovering(range.first, range.second) ?: return null
        return LanReviewController(context, lanBuffer, chunks).also { reviewController = it }
    }

    val controller = reviewController
    val reviewState = if (controller != null) controller.uiState.collectAsState().value else null

    Column(modifier = Modifier.fillMaxSize().background(Color.Black)) {
        Box(modifier = Modifier.weight(1f).fillMaxWidth()) {
            when {
                controller != null -> AndroidView(
                    modifier = Modifier.fillMaxSize(),
                    factory = { ctx -> PlayerView(ctx).apply { useController = false } },
                    update = { view -> view.player = controller.player },
                )
                isActive -> AndroidView(
                    modifier = Modifier.fillMaxSize(),
                    factory = { ctx -> PlayerView(ctx).apply { useController = false } },
                    update = { view -> view.player = LanBufferSession.currentPlayer() },
                )
                else -> Text("Buffer not running", color = Color.Gray, modifier = Modifier.padding(24.dp))
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
                    onClick = { startBuffer() },
                    enabled = !isActive,
                ) { Text("Start Buffer") }

                OutlinedButton(
                    onClick = {
                        releaseReview()
                        LanBufferForegroundService.stop(context)
                        isActive = false
                        bufferInfo = "buffer: (none yet)"
                        liveWindowStartMs = null
                        liveWindowEndMs = null
                    },
                    enabled = isActive,
                ) { Text("Kill background processes") }
            }

            if (controller != null && reviewState != null) {
                // ---- Review mode: one continuous seekable/pausable timeline over the frozen
                // snapshot -- see LanReviewController for why this replaced the old per-chunk
                // looping player. ----
                TimelineScrubber(
                    durationMs = reviewState.durationMs,
                    positionMs = reviewState.positionMs,
                    inPointMs = reviewState.inPointMs,
                    outPointMs = reviewState.outPointMs,
                    onSeek = controller::seekTo,
                    onDragInPoint = controller::dragInPoint,
                    onDragOutPoint = controller::dragOutPoint,
                    modifier = Modifier.padding(horizontal = 20.dp),
                )

                Text(
                    "Selected: ${reviewState.selectedDurationMs / 1000}s",
                    color = Color.White,
                    style = MaterialTheme.typography.bodySmall,
                )

                if (reviewState.isPreviewing) {
                    // Matches the old PreviewScreen: only Cancel and Export are visible while
                    // looping [in, out) -- no transport controls, no scrubber interaction.
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        OutlinedButton(onClick = { controller.exitPreview() }) { Text("Cancel") }

                        Button(
                            onClick = {
                                val lanBuffer = LanBufferSession.currentLanBuffer() ?: return@Button
                                val (inEpoch, outEpoch) = controller.inOutEpochRange()
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
                } else {
                    // Transport row, laid out exactly like the old Cut screen: step-back,
                    // play/pause, step-forward on the left; Jump to live on the right.
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                    ) {
                        Row {
                            TextButton(onClick = { controller.stepFrame(forward = false) }) {
                                Text("|<", color = Color.White)
                            }
                            TextButton(onClick = { controller.togglePlayPause() }) {
                                Text(if (reviewState.isPlaying) "Pause" else "Play", color = Color.White)
                            }
                            TextButton(onClick = { controller.stepFrame(forward = true) }) {
                                Text(">|", color = Color.White)
                            }
                        }
                        OutlinedButton(onClick = { releaseReview() }) { Text("Jump to live") }
                    }

                    // Stamp IN/OUT at wherever the playhead is paused right now, alongside the
                    // scrubber's own draggable handles for fine adjustment afterwards.
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        OutlinedButton(
                            onClick = { controller.setInAtPlayhead() },
                            modifier = Modifier.weight(1f),
                        ) { Text("Set IN") }
                        OutlinedButton(
                            onClick = { controller.setOutAtPlayhead() },
                            modifier = Modifier.weight(1f),
                        ) { Text("Set OUT") }
                    }

                    Button(
                        onClick = { controller.enterPreview() },
                        enabled = reviewState.selectedDurationMs > 0,
                        modifier = Modifier.fillMaxWidth(),
                    ) { Text("Preview") }
                }
            } else {
                // ---- Live mode: no review session yet. The scrubber shows the growing live
                // window parked at its own edge; touching it at all starts a review session (see
                // ensureReviewController) with that same gesture applied immediately. ----
                val start = liveWindowStartMs
                val end = liveWindowEndMs
                if (start != null && end != null) {
                    val windowSpanMs = (end - start).coerceAtLeast(0)
                    val defaultInMs = (windowSpanMs - DEFAULT_CLIP_SPAN_MS).coerceAtLeast(0)

                    TimelineScrubber(
                        durationMs = windowSpanMs,
                        positionMs = windowSpanMs,
                        inPointMs = defaultInMs,
                        outPointMs = windowSpanMs,
                        onSeek = { seekMs ->
                            val epoch = start + seekMs
                            ensureReviewController()?.let { it.seekTo(epoch - it.windowStartEpochMs) }
                        },
                        onDragInPoint = { timelineMs ->
                            val epoch = start + timelineMs
                            ensureReviewController()?.let { it.dragInPoint(epoch - it.windowStartEpochMs) }
                        },
                        onDragOutPoint = { timelineMs ->
                            val epoch = start + timelineMs
                            ensureReviewController()?.let { it.dragOutPoint(epoch - it.windowStartEpochMs) }
                        },
                        modifier = Modifier.padding(horizontal = 20.dp),
                    )
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
