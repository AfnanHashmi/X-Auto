package com.clipcut.ui.caption

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.rememberModalBottomSheetState
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
import com.clipcut.domain.api.CaptionFormat
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
        modifier = modifier.fillMaxSize().imePadding().verticalScroll(rememberScrollState()).padding(16.dp),
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

        val formatted = state.formatted
        Text("Format", style = MaterialTheme.typography.labelMedium)
        @OptIn(ExperimentalLayoutApi::class)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            CaptionFormat.entries.forEach { format ->
                FilterChip(
                    selected = formatted.format == format,
                    onClick = { viewModel.selectFormat(format) },
                    label = { Text(format.label) },
                )
            }
        }

        if (formatted.format.prefix == null) {
            OutlinedTextField(
                value = state.caption,
                onValueChange = viewModel::updateCaption,
                label = { Text("Caption") },
                modifier = Modifier.fillMaxWidth().onFocusChanged { focus ->
                    if (!focus.isFocused) viewModel.commitManualEdit()
                },
                minLines = 3,
            )
        } else {
            // The label is fixed: shown, but not editable and never sent to the AI.
            Surface(color = MaterialTheme.colorScheme.surfaceVariant, modifier = Modifier.fillMaxWidth()) {
                Row(
                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween,
                ) {
                    Text(formatted.format.prefix!!.trimEnd(), style = MaterialTheme.typography.titleSmall)
                    Text("fixed", style = MaterialTheme.typography.labelSmall)
                }
            }
            OutlinedTextField(
                value = formatted.heading,
                onValueChange = viewModel::updateHeading,
                label = { Text("Heading") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth().onFocusChanged { focus ->
                    if (!focus.isFocused) viewModel.commitManualEdit()
                },
            )
            OutlinedTextField(
                value = formatted.body,
                onValueChange = viewModel::updateBody,
                label = { Text("Caption") },
                modifier = Modifier.fillMaxWidth().onFocusChanged { focus ->
                    if (!focus.isFocused) viewModel.commitManualEdit()
                },
                minLines = 3,
            )
        }

        MoodPicker(selected = state.mood, onSelect = viewModel::selectMood, enabled = !state.isEnhancing)

        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(onClick = viewModel::enhance, enabled = state.canEnhance) {
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
                    enabled = newTemplateTitle.isNotBlank() && state.hasContent,
                ) {
                    Text("Save")
                }
            }
        } else {
            TextButton(onClick = { showSaveTemplate = true }, enabled = state.hasContent) {
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

private val MOOD_GROUPS = listOf(
    "🔥  Energy" to listOf(CaptionMood.FIGHT, CaptionMood.BASHING, CaptionMood.SAVAGE, CaptionMood.VICTORY, CaptionMood.SHOCK),
    "🎭  Expression" to listOf(CaptionMood.DIALOGUE, CaptionMood.FUN, CaptionMood.SINGING, CaptionMood.EMOTIONAL, CaptionMood.CUTE),
    "👀  Commentary" to listOf(
        CaptionMood.EXPOSE, CaptionMood.CONFESSION, CaptionMood.FRUSTRATION, CaptionMood.APPRECIATION, CaptionMood.EPISODE,
    ),
)

/**
 * Mood picker. A full-width button shows the current mood with a down arrow at its right edge;
 * tapping it opens a bottom sheet: Auto on top as a wide card, then the 15 moods as chips grouped
 * into Energy / Expression / Commentary.
 */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
private fun MoodPicker(selected: CaptionMood, onSelect: (CaptionMood) -> Unit, enabled: Boolean) {
    var open by remember { mutableStateOf(false) }
    OutlinedButton(onClick = { open = true }, enabled = enabled, modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text("${selected.emoji}  ${selected.label}")
            Text("\u25BE") // down-pointing triangle
        }
    }
    if (open) {
        ModalBottomSheet(
            onDismissRequest = { open = false },
            sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        ) {
            fun pick(mood: CaptionMood) {
                onSelect(mood)
                open = false
            }
            Column(
                modifier = Modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp, bottom = 32.dp),
                verticalArrangement = Arrangement.spacedBy(14.dp),
            ) {
                Text("How should it sound?", style = MaterialTheme.typography.titleMedium)

                if (selected == CaptionMood.AUTO) {
                    Button(onClick = { pick(CaptionMood.AUTO) }, modifier = Modifier.fillMaxWidth()) {
                        Text("✨  Auto  \u2013  picks the best fit")
                    }
                } else {
                    OutlinedButton(onClick = { pick(CaptionMood.AUTO) }, modifier = Modifier.fillMaxWidth()) {
                        Text("✨  Auto  \u2013  picks the best fit")
                    }
                }

                MOOD_GROUPS.forEach { (title, moods) ->
                    Text(title, style = MaterialTheme.typography.labelLarge)
                    FlowRow(
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalArrangement = Arrangement.spacedBy(4.dp),
                    ) {
                        moods.forEach { mood ->
                            FilterChip(
                                selected = mood == selected,
                                onClick = { pick(mood) },
                                label = { Text("${mood.emoji}  ${mood.label}") },
                            )
                        }
                    }
                }
            }
        }
    }
}
