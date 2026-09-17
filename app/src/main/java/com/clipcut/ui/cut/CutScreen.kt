package com.clipcut.ui.cut

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.media3.ui.PlayerView
import java.util.Locale
import kotlin.math.absoluteValue

/**
 * The real cut UI: video surface, a DVR-window-spanning scrubber with draggable in/out handles,
 * frame-step controls, and an export button. Scrubback depth is whatever [CutUiState.durationMs]
 * reports from the live player -- never a hardcoded window.
 */
@Composable
fun CutScreen(modifier: Modifier = Modifier, viewModel: CutViewModel = hiltViewModel()) {
    val state by viewModel.uiState.collectAsState()

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
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Button(
                    onClick = viewModel::export,
                    enabled = !state.isExporting && state.selectedDurationMs > 0,
                    modifier = Modifier.weight(1f),
                ) {
                    if (state.isExporting) {
                        CircularProgressIndicator(modifier = Modifier.size(18.dp), color = Color.White)
                    } else {
                        Text("Export clip")
                    }
                }
                if (state.isExporting) {
                    OutlinedButton(onClick = viewModel::cancelExport) {
                        Text("Cancel")
                    }
                }
            }

            state.statusMessage?.let { message ->
                Text(text = message, color = Color.White, style = MaterialTheme.typography.bodySmall)
            }
        }
    }
}

private fun formatMs(ms: Long): String {
    val totalSeconds = ms.absoluteValue / 1000
    val minutes = totalSeconds / 60
    val seconds = totalSeconds % 60
    return String.format(Locale.US, "%d:%02d", minutes, seconds)
}
