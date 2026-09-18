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
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.media3.ui.PlayerView

/**
 * Shown after the user picks in/out points on [CutScreen], before committing to an export.
 * Shares the same [CutViewModel] instance (scoped to the parent nav-graph entry) so it can loop
 * playback of just the selected range and drive the existing export/cancel logic directly.
 */
@Composable
fun PreviewScreen(
    modifier: Modifier = Modifier,
    viewModel: CutViewModel,
    onExported: (clipPath: String) -> Unit = {},
    onCancel: () -> Unit = {},
) {
    val state by viewModel.uiState.collectAsState()

    LaunchedEffect(viewModel) {
        viewModel.enterPreview()
    }
    DisposableEffect(viewModel) {
        onDispose { viewModel.exitPreview() }
    }
    LaunchedEffect(viewModel) {
        viewModel.exportedClipPath.collect { path -> onExported(path) }
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
            Text(
                text = "Selected: ${formatMs(state.selectedDurationMs)}",
                color = Color.White,
                style = MaterialTheme.typography.bodySmall,
            )

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Button(
                    onClick = viewModel::export,
                    enabled = !state.isExporting,
                    modifier = Modifier.weight(1f),
                ) {
                    if (state.isExporting) {
                        CircularProgressIndicator(modifier = Modifier.size(18.dp), color = Color.White)
                    } else {
                        Text("Export")
                    }
                }
                OutlinedButton(
                    onClick = {
                        if (state.isExporting) viewModel.cancelExport()
                        viewModel.resetSelection()
                        onCancel()
                    },
                ) {
                    Text("Cancel")
                }
            }

            state.statusMessage?.let { message ->
                Text(text = message, color = Color.White, style = MaterialTheme.typography.bodySmall)
            }
        }
    }
}
