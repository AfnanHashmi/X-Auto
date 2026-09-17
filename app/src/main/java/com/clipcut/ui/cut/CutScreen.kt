package com.clipcut.ui.cut

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
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

/**
 * Phase 2: live HLS playback + DVR seek spike. `PlayerView`'s default controller already exposes
 * a seek bar across the player-reported seekable window and a jump-to-live button — no custom
 * scrubber needed to prove the spike; Phase 4 replaces this controller with the real cut UI.
 *
 * The bottom bar is a Phase 3 spike trigger for [CutViewModel.runExportSpike] -- removed once
 * Phase 4 wires a real in/out selection to [com.clipcut.domain.ClipExporter].
 */
@Composable
fun CutScreen(modifier: Modifier = Modifier, viewModel: CutViewModel = hiltViewModel()) {
    val exportStatus by viewModel.exportStatus.collectAsState()

    Box(modifier = modifier.fillMaxSize()) {
        AndroidView(
            modifier = Modifier.fillMaxSize(),
            factory = { context ->
                PlayerView(context).apply {
                    player = viewModel.player
                }
            },
        )
        Column(
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth()
                .background(Color.Black.copy(alpha = 0.6f))
                .padding(12.dp),
        ) {
            Button(onClick = { viewModel.runExportSpike() }) {
                Text("Test export (10s)")
            }
            exportStatus?.let { status ->
                Text(text = status, color = Color.White, style = MaterialTheme.typography.bodySmall)
            }
        }
    }
}
