package com.clipcut.ui.cut

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.viewinterop.AndroidView
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.media3.ui.PlayerView

/**
 * Phase 2: live HLS playback + DVR seek spike. `PlayerView`'s default controller already exposes
 * a seek bar across the player-reported seekable window and a jump-to-live button — no custom
 * scrubber needed to prove the spike; Phase 4 replaces this controller with the real cut UI.
 */
@Composable
fun CutScreen(modifier: Modifier = Modifier, viewModel: CutViewModel = hiltViewModel()) {
    AndroidView(
        modifier = modifier.fillMaxSize(),
        factory = { context ->
            PlayerView(context).apply {
                player = viewModel.player
            }
        },
    )
}
