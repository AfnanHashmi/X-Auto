package com.clipcut.ui.cut

import android.content.Intent
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
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.viewinterop.AndroidView
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.media3.ui.PlayerView
import com.clipcut.lan.LanBufferActivity
import java.util.Locale
import kotlin.math.absoluteValue

/**
 * The real cut UI: video surface, a scrubber (capped client-side to the last 5 minutes of DVR,
 * see [CutViewModel]) with draggable in/out handles, frame-step controls, and a Preview button
 * that hands off to [com.clipcut.ui.cut.PreviewScreen] for the actual export/cancel decision.
 */
@Composable
fun CutScreen(
    modifier: Modifier = Modifier,
    viewModel: CutViewModel = hiltViewModel(),
    onPreview: () -> Unit = {},
) {
    val state by viewModel.uiState.collectAsState()
    val context = LocalContext.current

    // Muted (not paused/stopped) whenever this screen's Activity leaves the foreground -- e.g.
    // LAN Mode launching on top of it in the same task, or the app backgrounding outright.
    // Muting rather than touching playWhenReady/CutViewModel state avoids any risk to this
    // screen's live-DVR position logic; it only ever silences this player's own audio output, so
    // it can never be heard doubled up against LAN mode's independent player.
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner, viewModel) {
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_STOP -> viewModel.player.volume = 0f
                Lifecycle.Event.ON_START -> viewModel.player.volume = 1f
                else -> {}
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    Column(modifier = modifier.fillMaxSize().background(Color.Black)) {
        Box(modifier = Modifier.weight(1f).fillMaxWidth()) {
            AndroidView(
                modifier = Modifier.fillMaxSize(),
                factory = { context ->
                    PlayerView(context).apply {
                        player = viewModel.player
                        useController = false
                    }
                },
            )
        }

        Column(
            modifier = Modifier.fillMaxWidth().padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(formatMs(state.positionMs), color = Color.White, style = MaterialTheme.typography.bodyMedium)
                Text(
                    text = if (state.isLive) "LIVE" else "",
                    color = Color(0xFFEF5350),
                    style = MaterialTheme.typography.labelLarge,
                )
                Text(formatMs(state.durationMs), color = Color.White, style = MaterialTheme.typography.bodyMedium)
            }

            TimelineScrubber(
                durationMs = state.durationMs,
                positionMs = state.positionMs,
                inPointMs = state.inPointMs,
                outPointMs = state.outPointMs,
                onSeek = viewModel::seekTo,
                onDragInPoint = viewModel::dragInPoint,
                onDragOutPoint = viewModel::dragOutPoint,
                // Extra inset beyond the screen's own padding: handles near duration's ends
                // (very common here -- the default selection is the last few seconds, right at
                // the live edge) must stay clear of the OS's edge back-gesture capture zone.
                modifier = Modifier.padding(horizontal = 20.dp),
            )

            Text(
                text = "Selected: ${formatMs(state.selectedDurationMs)}",
                color = Color.White,
                style = MaterialTheme.typography.bodySmall,
            )

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    TextButton(onClick = { viewModel.stepFrame(forward = false) }) {
                        Text("|<", color = Color.White)
                    }
                    TextButton(onClick = viewModel::togglePlayPause) {
                        Text(if (state.isPlaying) "Pause" else "Play", color = Color.White)
                    }
                    TextButton(onClick = { viewModel.stepFrame(forward = true) }) {
                        Text(">|", color = Color.White)
                    }
                }
                OutlinedButton(onClick = viewModel::seekToLive) {
                    Text("Jump to live")
                }
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.End,
            ) {
                // Plain Activity launch, not a nav-graph route or a CutViewModel branch: LAN mode's
                // playback model (local recorded buffer, no server DVR) is different enough from
                // this screen's server-DVR-seek model that sharing state between them isn't worth
                // the risk to this screen, which the user depends on for live broadcasts.
                TextButton(
                    onClick = { context.startActivity(Intent(context, LanBufferActivity::class.java)) },
                ) {
                    Text("LAN Mode (low latency)", color = Color(0xFF80CBC4))
                }
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Button(
                    onClick = onPreview,
                    enabled = state.selectedDurationMs > 0,
                    modifier = Modifier.weight(1f),
                ) {
                    Text("Preview")
                }
            }

            state.statusMessage?.let { message ->
                Text(text = message, color = Color.White, style = MaterialTheme.typography.bodySmall)
            }

            state.streamWarning?.let { warning ->
                Text(
                    text = warning,
                    color = Color(0xFFFFB74D),
                    style = MaterialTheme.typography.bodySmall,
                )
            }
        }
    }
}

internal fun formatMs(ms: Long): String {
    val totalSeconds = ms.absoluteValue / 1000
    val minutes = totalSeconds / 60
    val seconds = totalSeconds % 60
    return String.format(Locale.US, "%d:%02d", minutes, seconds)
}
