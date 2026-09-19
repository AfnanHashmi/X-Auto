package com.clipcut.ui.post

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel

@Composable
fun PostScreen(
    modifier: Modifier = Modifier,
    viewModel: PostViewModel = hiltViewModel(),
    onDone: () -> Unit = {},
) {
    val state by viewModel.uiState.collectAsState()

    // Kick off automatically -- the user already reviewed the caption on the previous screen.
    LaunchedEffect(viewModel) {
        viewModel.post()
    }

    Column(
        modifier = modifier.fillMaxSize().padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Text("Copy for X", style = MaterialTheme.typography.titleLarge)

        when {
            state.isPosting -> Column(
                modifier = Modifier.fillMaxWidth(),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                CircularProgressIndicator()
                Text("Copying to clipboard...")
            }

            state.isPosted -> Column(
                modifier = Modifier.fillMaxWidth(),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Text("Copied to clipboard", style = MaterialTheme.typography.headlineSmall)
                Text("Paste it into a new post on your main account:", style = MaterialTheme.typography.bodyMedium)
                Text(state.copiedText, style = MaterialTheme.typography.bodyMedium)
                Button(onClick = viewModel::post) { Text("Copy again") }
                Button(onClick = onDone) { Text("Done") }
            }

            state.errorMessage != null -> Column(
                modifier = Modifier.fillMaxWidth(),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Text("Copy failed: ${state.errorMessage}", color = MaterialTheme.colorScheme.error)
                Button(onClick = viewModel::post) { Text("Retry") }
            }
        }
    }
}
