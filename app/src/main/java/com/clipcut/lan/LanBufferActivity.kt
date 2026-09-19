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
 * The app's only screen: OBS/UDP low-latency buffer control + scrub/export.
 *
 * There is exactly one video area and one set of controls, always visible once the buffer has any
 * content -- no hidden gesture reveals anything. Two players share that one video area (a raw,
 * always-live sub-second UDP player from [LanBufferSession], and a seekable/pausable player over
 * the recorded buffer from [LanReviewController]), but which one is "current" is an implementation
 * detail, not a UI mode: pressing Pause, stepping a frame, dragging the timeline, or marking IN/OUT
 * all transparently start the buffer player at that point if it isn't already running (see
 * [ensureReviewController] below), and **Jump to live** goes back to the raw feed. "Start Buffer"
 * starts [LanBufferForegroundService], which begins receiving/decoding/recording immediately and
 * keeps running (behind a persistent notification) independent of whether this screen -- or the
 * app at all -- is open; reopening this screen later just attaches to whatever's already running.
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

    var bufferController by remember { mutableStateOf<LanReviewController?>(null) }

    fun releaseBufferPlayer() {
        bufferController?.release()
        bufferController = null
        LanBufferSession.setPreviewMuted(false)
        LanBufferSession.setLiveVideoDecodingEnabled(true)
    }

    DisposableEffect(Unit) {
        onDispose {
            if (bufferController != null) {
                bufferController?.release()
                LanBufferSession.setPreviewMuted(false)
                LanBufferSession.setLiveVideoDecodingEnabled(true)
            }
        }
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
     * Returns the buffer player, starting it (paused, parked at the live edge of everything
     * buffered so far) if it isn't already running. Every control below calls this rather than
     * checking a "mode" -- there is no mode, just "is the buffer player running yet or not".
     */
    fun ensureBufferController(): LanReviewController? {
        bufferController?.let { return it }
        val lanBuffer = LanBufferSession.currentLanBuffer() ?: return null
        val range = lanBuffer.bufferedRange() ?: return null
        val chunks = lanBuffer.chunksCovering(range.first, range.second) ?: return null
        // The live player's video is never actually shown once the buffer player starts (the one
        // video area points at the buffer player instead), but it would otherwise keep silently
        // decoding and fighting the buffer player for the phone's one hardware video decoder.
        LanBufferSession.setPreviewMuted(true)
        LanBufferSession.setLiveVideoDecodingEnabled(false)
        return LanReviewController(context, lanBuffer, chunks).also { bufferController = it }
    }

    val controller = bufferController
    val reviewState = if (controller != null) controller.uiState.collectAsState().value else null

    val hasContent = liveWindowStartMs != null || controller != null
    val windowSpanMs = reviewState?.durationMs
        ?: ((liveWindowEndMs ?: 0L) - (liveWindowStartMs ?: 0L)).coerceAtLeast(0)
    val positionMs = reviewState?.positionMs ?: windowSpanMs
    val inPointMs = reviewState?.inPointMs ?: (windowSpanMs - DEFAULT_CLIP_SPAN_MS).coerceAtLeast(0)
    val outPointMs = reviewState?.outPointMs ?: windowSpanMs
    val isPlaying = reviewState?.isPlaying ?: true // the live feed is always playing.
    val isPreviewing = reviewState?.isPreviewing ?: false
    val selectedDurationMs = (outPointMs - inPointMs).coerceAtLeast(0)

    Column(modifier = Modifier.fillMaxSize().background(Color.Black)) {
        Box(modifier = Modifier.weight(1f).fillMaxWidth()) {
            if (isActive || controller != null) {
                // One video area, one AndroidView, for the whole lifetime of this screen -- never
                // two competing AndroidView call sites swapped by a when-branch. Swapping call
                // sites disposes one PlayerView and creates another without ever telling the
                // outgoing player to let go of its (now-destroyed) video surface, leaving it
                // decoding into nowhere. `onRelease` makes sure a player always relinquishes the
                // surface if this composable itself ever goes away.
                AndroidView(
                    modifier = Modifier.fillMaxSize(),
                    factory = { ctx -> PlayerView(ctx).apply { useController = false } },
                    update = { view -> view.player = controller?.player ?: LanBufferSession.currentPlayer() },
                    onRelease = { view -> view.player = null },
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
                    onClick = { startBuffer() },
                    enabled = !isActive,
                ) { Text("Start Buffer") }

                OutlinedButton(
                    onClick = {
                        releaseBufferPlayer()
                        LanBufferForegroundService.stop(context)
                        isActive = false
                        bufferInfo = "buffer: (none yet)"
                        liveWindowStartMs = null
                        liveWindowEndMs = null
                    },
                    enabled = isActive,
                ) { Text("Kill background processes") }
            }

            TimelineScrubber(
                durationMs = windowSpanMs,
                positionMs = positionMs,
                inPointMs = inPointMs,
                outPointMs = outPointMs,
                onSeek = { seekMs ->
                    val c = controller
                    if (c != null) {
                        c.seekTo(seekMs)
                    } else {
                        val start = liveWindowStartMs
                        if (start != null) {
                            val epoch = start + seekMs
                            ensureBufferController()?.let { it.seekTo(epoch - it.windowStartEpochMs) }
                        }
                    }
                },
                onDragInPoint = { timelineMs ->
                    val c = controller
                    if (c != null) {
                        c.dragInPoint(timelineMs)
                    } else {
                        val start = liveWindowStartMs
                        if (start != null) {
                            val epoch = start + timelineMs
                            ensureBufferController()?.let { it.dragInPoint(epoch - it.windowStartEpochMs) }
                        }
                    }
                },
                onDragOutPoint = { timelineMs ->
                    val c = controller
                    if (c != null) {
                        c.dragOutPoint(timelineMs)
                    } else {
                        val start = liveWindowStartMs
                        if (start != null) {
                            val epoch = start + timelineMs
                            ensureBufferController()?.let { it.dragOutPoint(epoch - it.windowStartEpochMs) }
                        }
                    }
                },
                modifier = Modifier.padding(horizontal = 20.dp),
            )

            Text(
                "Selected: ${selectedDurationMs / 1000}s",
                color = Color.White,
                style = MaterialTheme.typography.bodySmall,
            )

            // Transport row: step-back, play/pause, step-forward on the left; Jump to live on the
            // right. Always rendered once there's content -- disabled, never hidden, when it can't
            // do anything yet.
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Row {
                    TextButton(
                        onClick = { ensureBufferController()?.stepFrame(forward = false) },
                        enabled = hasContent,
                    ) { Text("|<", color = Color.White) }
                    TextButton(
                        onClick = {
                            val c = controller
                            if (c == null) ensureBufferController() else c.togglePlayPause()
                        },
                        enabled = hasContent,
                    ) { Text(if (isPlaying) "Pause" else "Play", color = Color.White) }
                    TextButton(
                        onClick = { ensureBufferController()?.stepFrame(forward = true) },
                        enabled = hasContent,
                    ) { Text(">|", color = Color.White) }
                }
                OutlinedButton(
                    onClick = { releaseBufferPlayer() },
                    enabled = controller != null,
                ) { Text("Jump to live") }
            }

            if (isPreviewing && controller != null) {
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
                        if (isExporting) {
                            CircularProgressIndicator(modifier = Modifier.padding(end = 8.dp))
                        }
                        Text("Export clip")
                    }
                }
            } else {
                // Stamp IN/OUT at wherever the playhead is paused right now, alongside the
                // scrubber's own draggable handles for fine adjustment afterwards.
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    OutlinedButton(
                        onClick = {
                            // Marking IN/OUT while still watching live must never silently freeze
                            // the picture -- that reads as "the button did nothing" and defeats the
                            // whole point of the low-latency view. A fresh buffer player starts
                            // paused by default (right, for a deliberate scrub/pause-there gesture),
                            // but here the user was just watching, so keep it moving.
                            val wasLive = controller == null
                            val c = ensureBufferController() ?: return@OutlinedButton
                            if (wasLive) c.togglePlayPause()
                            c.setInAtPlayhead()
                            statusMessage = "IN marked"
                        },
                        enabled = hasContent,
                        modifier = Modifier.weight(1f),
                    ) { Text("Set IN") }
                    OutlinedButton(
                        onClick = {
                            val wasLive = controller == null
                            val c = ensureBufferController() ?: return@OutlinedButton
                            if (wasLive) c.togglePlayPause()
                            c.setOutAtPlayhead()
                            statusMessage = "OUT marked"
                        },
                        enabled = hasContent,
                        modifier = Modifier.weight(1f),
                    ) { Text("Set OUT") }
                }

                Button(
                    onClick = { ensureBufferController()?.enterPreview() },
                    enabled = hasContent && selectedDurationMs > 0,
                    modifier = Modifier.fillMaxWidth(),
                ) { Text("Preview") }
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
