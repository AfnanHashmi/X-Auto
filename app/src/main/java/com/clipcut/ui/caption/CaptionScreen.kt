package com.clipcut.ui.caption

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import com.clipcut.domain.api.CaptionMood
import com.clipcut.domain.api.MediaRef

@Composable
fun CaptionScreen(
    modifier: Modifier = Modifier,
    viewModel: CaptionViewModel = hiltViewModel(),
    onContinueToPost: (caption: String, mediaRef: MediaRef) -> Unit = { _, _ -> },
) {
    val state by viewModel.uiState.collectAsState()
    var newTemplateTitle by remember { mutableStateOf("") }
    var showSaveTemplate by remember { mutableStateOf(false) }

    Column(
        modifier = modifier.fillMaxSize().padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text("Caption", style = MaterialTheme.typography.titleLarge)

        // Auto-embedded media from the media account's latest post (Phase 5's getLatestPost()).
        Surface(color = MaterialTheme.colorScheme.surfaceVariant, modifier = Modifier.fillMaxWidth()) {
            Column(modifier = Modifier.padding(12.dp)) {
                if (state.isUploading) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        CircularProgressIndicator(modifier = Modifier.padding(end = 8.dp))
                        Text("Uploading clip...")
                    }
                } else if (state.mediaRef != null) {
                    Text("Uploaded: ${state.mediaRef?.url}", style = MaterialTheme.typography.bodySmall)
                }
                if (!state.isUploading && state.mediaRef == null) {
                    OutlinedButton(onClick = viewModel::retryUpload) {
                        Text(if (state.uploadOutcomeUnknown) "Upload anyway (may duplicate)" else "Retry upload")
                    }
                }
                state.embeddedPost?.let { post ->
                    Text(
                        text = "Embedding latest media-account post: ${post.mediaRef.url}",
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
            }
        }

        OutlinedTextField(
            value = state.caption,
            onValueChange = viewModel::updateCaption,
            label = { Text("Caption") },
            modifier = Modifier.fillMaxWidth().onFocusChanged { focus ->
                if (!focus.isFocused) viewModel.commitManualEdit()
            },
            minLines = 3,
        )

        MoodDropdown(selected = state.mood, onSelect = viewModel::selectMood, enabled = !state.isEnhancing)

        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(onClick = viewModel::enhance, enabled = !state.isEnhancing && state.caption.isNotBlank()) {
                if (state.isEnhancing) {
                    CircularProgressIndicator(modifier = Modifier.padding(end = 8.dp))
                }
                Text("Enhance with AI")
            }
            OutlinedButton(onClick = viewModel::undo, enabled = state.canUndo) {
                Text("Undo")
            }
        }

        Text("Templates", style = MaterialTheme.typography.titleSmall)
        if (state.templates.isEmpty()) {
            Text("No saved templates yet.", style = MaterialTheme.typography.bodySmall)
        } else {
            LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                items(state.templates) { template ->
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        AssistChip(onClick = { viewModel.applyTemplate(template) }, label = { Text(template.title) })
                        TextButton(onClick = { viewModel.deleteTemplate(template) }) {
                            Text("✕")
                        }
                    }
                }
            }
        }

        if (showSaveTemplate) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                    value = newTemplateTitle,
                    onValueChange = { newTemplateTitle = it },
                    label = { Text("Template name") },
                    modifier = Modifier.weight(1f),
                )
                TextButton(
                    onClick = {
                        viewModel.saveAsTemplate(newTemplateTitle)
                        newTemplateTitle = ""
                        showSaveTemplate = false
                    },
                    enabled = newTemplateTitle.isNotBlank() && state.caption.isNotBlank(),
                ) {
                    Text("Save")
                }
            }
        } else {
            TextButton(onClick = { showSaveTemplate = true }, enabled = state.caption.isNotBlank()) {
                Text("Save current caption as template")
            }
        }

        state.statusMessage?.let { message ->
            Text(text = message, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
        }

        Button(
            onClick = {
                val ref = state.mediaRef
                if (ref != null) onContinueToPost(state.caption, ref)
            },
            enabled = state.canProceedToPost,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text("Copy caption + link")
        }
    }
}

/** Mood picker: one full-width button showing the current mood, with a down arrow at its right edge. */
@Composable
private fun MoodDropdown(selected: CaptionMood, onSelect: (CaptionMood) -> Unit, enabled: Boolean) {
    var expanded by remember { mutableStateOf(false) }
    Box(modifier = Modifier.fillMaxWidth()) {
        OutlinedButton(onClick = { expanded = true }, enabled = enabled, modifier = Modifier.fillMaxWidth()) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text("${selected.emoji}  ${selected.label}")
                Text("\u25BE") // down-pointing triangle
            }
        }
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            CaptionMood.entries.forEach { mood ->
                DropdownMenuItem(
                    text = { Text("${mood.emoji}  ${mood.label}") },
                    onClick = {
                        onSelect(mood)
                        expanded = false
                    },
                )
            }
        }
    }
}
