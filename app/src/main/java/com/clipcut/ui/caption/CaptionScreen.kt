package com.clipcut.ui.caption

import com.clipcut.domain.api.real.MIN_VARIANTS
import com.clipcut.domain.api.real.MAX_VARIANTS
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
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.height
import com.clipcut.ui.components.Screen
import com.clipcut.ui.components.SectionCard
import com.clipcut.ui.components.StatusRow
import com.clipcut.ui.components.Tone
import com.clipcut.domain.api.CaptionFormat
import com.clipcut.domain.api.CaptionMood
import com.clipcut.domain.history.ROSTER
import com.clipcut.domain.api.MediaRef

@Composable
fun CaptionScreen(
    modifier: Modifier = Modifier,
    viewModel: CaptionViewModel = hiltViewModel(),
    onContinueToPost: (caption: String, mediaRef: MediaRef) -> Unit = { _, _ -> },
    onCopyCaption: (caption: String) -> Unit = {},
) {
    var copiedCaption by remember { mutableStateOf<String?>(null) }
    val state by viewModel.uiState.collectAsState()
    var newTemplateTitle by remember { mutableStateOf("") }
    var showSaveTemplate by remember { mutableStateOf(false) }

    Screen(
        title = "Caption",
        subtitle = if (state.standalone) "Write and enhance a caption, then copy it." else "Write, enhance and copy the post for your main account.",
        modifier = modifier.imePadding(),
    ) {
      Column(
        modifier = Modifier.verticalScroll(rememberScrollState()).padding(bottom = 24.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
      ) {
        if (!state.standalone) SectionCard(if (state.mediaAccountLabel.isBlank()) "Media post" else "Media post - ${state.mediaAccountLabel}") {
            if (state.isUploading) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp)
                    Text("Uploading clip...", modifier = Modifier.padding(start = 12.dp))
                }
            } else if (state.mediaRef != null) {
                StatusRow("Posted", Tone.Good)
                Text(
                    state.mediaRef?.url.orEmpty(),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            } else {
                StatusRow(
                    if (state.uploadOutcomeUnknown) "Not confirmed - may already be posted" else "Not uploaded",
                    Tone.Bad,
                )
                OutlinedButton(onClick = viewModel::retryUpload) {
                    Text(if (state.uploadOutcomeUnknown) "Upload anyway (may duplicate)" else "Retry upload")
                }
            }
            state.embeddedPost?.let { post ->
                Text(
                    "Latest on the media account: ${post.mediaRef.url}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }

        @OptIn(ExperimentalLayoutApi::class)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            CaptionFormat.entries.forEach { format ->
                FilterChip(
                    selected = state.format == format,
                    onClick = { viewModel.selectFormat(format) },
                    label = { Text(format.label) },
                )
            }
        }

        // A layout's label is fixed: shown, but not editable and never sent to the AI. A plain post has none.
        state.format.prefix?.let { label ->
            Surface(color = MaterialTheme.colorScheme.surfaceVariant, modifier = Modifier.fillMaxWidth()) {
                Row(
                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween,
                ) {
                    Text(label.trimEnd(), style = MaterialTheme.typography.titleSmall)
                    Text("fixed", style = MaterialTheme.typography.labelSmall)
                }
            }
        }
        OutlinedTextField(
            value = state.heading,
            onValueChange = viewModel::updateHeading,
            label = { Text(if (state.format.prefix == null) "Heading (optional)" else "Heading") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth().onFocusChanged { focus ->
                if (!focus.isFocused) viewModel.commitManualEdit()
            },
        )
        OutlinedTextField(
            value = state.body,
            onValueChange = viewModel::updateBody,
            label = { Text("Caption") },
            modifier = Modifier.fillMaxWidth().onFocusChanged { focus ->
                if (!focus.isFocused) viewModel.commitManualEdit()
            },
            minLines = 3,
        )

        MoodPicker(selected = state.mood, onSelect = viewModel::selectMood, enabled = !state.isEnhancing)
        state.readMood?.let { read ->
            Text(
                "Read as: $read",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        HashtagPicker(caption = state.caption, onAdd = viewModel::addHashtag, enabled = !state.isEnhancing)

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

        VariantsSection(state, viewModel)

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
                if (state.standalone) {
                    onCopyCaption(state.caption)
                    copiedCaption = state.caption
                } else {
                    val ref = state.mediaRef
                    if (ref != null) onContinueToPost(state.caption, ref)
                }
            },
            enabled = state.canProceedToPost,
            modifier = Modifier.fillMaxWidth().height(52.dp),
        ) {
            Text(
                if (state.standalone) "Copy caption" else "Copy caption + link",
                style = MaterialTheme.typography.labelLarge,
            )
        }
        if (state.standalone && copiedCaption != null) {
            Text(
                if (copiedCaption == state.caption) "Copied to clipboard" else "Copied earlier - edited since, copy again",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.secondary,
            )
        }
      }
    }
}

/** Dropdown of fixed hashtags; tapping one writes it at the end of the caption. The sheet stays open for several. */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
private fun HashtagPicker(caption: String, onAdd: (String) -> Unit, enabled: Boolean) {
    var open by remember { mutableStateOf(false) }
    OutlinedButton(onClick = { open = true }, enabled = enabled, modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text("#  Hashtags")
            Text("▾")
        }
    }
    if (open) {
        ModalBottomSheet(
            onDismissRequest = { open = false },
            sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        ) {
            Column(
                modifier = Modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp, bottom = 32.dp),
                verticalArrangement = Arrangement.spacedBy(14.dp),
            ) {
                Text("Tap to add", style = MaterialTheme.typography.titleMedium)
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    HASHTAGS.forEach { tag ->
                        FilterChip(
                            selected = caption.contains(tag, ignoreCase = true),
                            onClick = { onAdd(tag) },
                            label = { Text(tag) },
                        )
                    }
                }
                TextButton(onClick = { open = false }, modifier = Modifier.align(Alignment.End)) { Text("Done") }
            }
        }
    }
}

/** #BiggBoss20, the five contestants in the news first, then every other contestant from the roster. */
private val HASHTAGS: List<String> = run {
    val top = listOf("Qazi Touqeer", "Uditi Singh", "Kanika Mann", "Kushal Tanwar (Gullu)", "Arishfa Khan")
    val first = top.mapNotNull { n -> ROSTER.firstOrNull { it.name == n }?.hashtag }
    listOf("#BiggBoss20") + first + ROSTER.map { it.hashtag }.filter { it !in first }
}

private val MOODS = listOf(CaptionMood.FUN, CaptionMood.BASHING, CaptionMood.FIGHT, CaptionMood.EMOTIONAL, CaptionMood.APPRECIATION)

/**
 * Mood picker. A full-width button shows the current mood with a down arrow at its right edge;
 * tapping it opens a bottom sheet: Auto on top as a wide card, then the five moods as chips.
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
                        Text("✨  Auto  \u2013  reads your text and picks the mood")
                    }
                } else {
                    OutlinedButton(onClick = { pick(CaptionMood.AUTO) }, modifier = Modifier.fillMaxWidth()) {
                        Text("✨  Auto  \u2013  reads your text and picks the mood")
                    }
                }

                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    MOODS.forEach { mood ->
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

/**
 * 3 to 5 takes on the caption in the chosen mood, optionally aware of what is trending. Tapping one puts it in the
 * caption box; Undo brings the previous one back. The heading and fixed label are left alone.
 */
@Composable
private fun VariantsSection(state: CaptionUiState, viewModel: CaptionViewModel) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text("Variants", style = MaterialTheme.typography.titleSmall)
        OutlinedTextField(
            value = state.trending,
            onValueChange = viewModel::setTrending,
            label = { Text("Trending now (optional)") },
            placeholder = { Text("e.g. #QaziBornToShine, task fight") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
            (MIN_VARIANTS..MAX_VARIANTS).forEach { n ->
                FilterChip(selected = state.variantCount == n, onClick = { viewModel.setVariantCount(n) }, label = { Text("$n") })
            }
            FilterChip(selected = state.highQuality, onClick = { viewModel.setHighQuality(!state.highQuality) }, label = { Text("Best") })
        }
        OutlinedButton(onClick = viewModel::makeVariants, enabled = state.canMakeVariants, modifier = Modifier.fillMaxWidth()) {
            if (state.isMakingVariants) CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp)
            Text(if (state.isMakingVariants) "  Writing ${state.variantCount} variants…" else "Write ${state.variantCount} variants")
        }
        state.variants.forEachIndexed { i, v ->
            Surface(
                onClick = { viewModel.useVariant(v) },
                color = MaterialTheme.colorScheme.surfaceVariant,
                shape = MaterialTheme.shapes.small,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Column(modifier = Modifier.padding(12.dp)) {
                    Text("${i + 1}", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary)
                    Text(v, style = MaterialTheme.typography.bodyMedium)
                }
            }
        }
        if (state.variants.isNotEmpty()) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    "Tap one to use it · by ${state.variantsLabel.orEmpty()}",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.weight(1f),
                )
                TextButton(onClick = viewModel::clearVariants) { Text("Clear") }
            }
        }
    }
}
