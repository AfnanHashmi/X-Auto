package com.clipcut.ui.cut

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

/**
 * Placeholder for the Cut screen (skeleton for Phase 1).
 *
 * Phase 2 replaces this with an ExoPlayer surface bound to the live HLS DVR window;
 * Phase 4 adds the scrubber, in/out handles, and export button.
 */
@Composable
fun CutScreen(modifier: Modifier = Modifier) {
    Box(
        modifier = modifier.fillMaxSize().padding(16.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(text = "Cut screen — Phase 2/4", style = MaterialTheme.typography.titleMedium)
    }
}
