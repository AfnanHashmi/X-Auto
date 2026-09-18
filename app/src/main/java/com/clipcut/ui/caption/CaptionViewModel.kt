package com.clipcut.ui.caption

import android.net.Uri
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.clipcut.data.db.CaptionTemplate
import com.clipcut.data.db.CaptionTemplateDao
import com.clipcut.data.db.ClipRecordDao
import com.clipcut.data.db.UndoEntry
import com.clipcut.data.db.UndoEntryDao
import com.clipcut.domain.api.CaptionEnhancer
import com.clipcut.domain.api.MediaAccountApi
import com.clipcut.domain.api.MediaUploader
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.io.File
import java.util.UUID
import javax.inject.Inject

@HiltViewModel
class CaptionViewModel @Inject constructor(
    savedStateHandle: SavedStateHandle,
    private val mediaUploader: MediaUploader,
    private val mediaAccountApi: MediaAccountApi,
    private val captionEnhancer: CaptionEnhancer,
    private val captionTemplateDao: CaptionTemplateDao,
    private val undoEntryDao: UndoEntryDao,
    private val clipRecordDao: ClipRecordDao,
) : ViewModel() {

    private val clipPath: String = Uri.decode(checkNotNull(savedStateHandle["clipPath"]))
    private val sessionId = UUID.randomUUID().toString()

    // Manual edits are coalesced into a single undo step per "typing run" rather than per
    // keystroke: the pre-edit text is captured once (here) and pushed to the undo stack only
    // when the run is committed (focus lost, or another mutating action interrupts it).
    private var pendingEditBaseline: String? = null

    private val _uiState = MutableStateFlow(CaptionUiState(clipPath = clipPath))
    val uiState: StateFlow<CaptionUiState> = _uiState.asStateFlow()

    init {
        viewModelScope.launch {
            captionTemplateDao.observeAll().collect { templates ->
                _uiState.update { it.copy(templates = templates) }
            }
        }
        uploadClip()
        loadLatestPost()
    }

    private fun uploadClip() {
        viewModelScope.launch {
            _uiState.update { it.copy(isUploading = true) }
            mediaUploader.upload(File(clipPath)).fold(
                onSuccess = { ref ->
                    _uiState.update { it.copy(isUploading = false, mediaRef = ref) }
                    clipRecordDao.findByPath(clipPath)?.let { record ->
                        clipRecordDao.update(record.copy(mediaRefId = ref.id))
                    }
                },
                onFailure = { e ->
                    _uiState.update { it.copy(isUploading = false, statusMessage = "Upload failed: ${e.message}") }
                },
            )
        }
    }

    private fun loadLatestPost() {
        viewModelScope.launch {
            mediaAccountApi.getLatestPost().onSuccess { post ->
                _uiState.update { it.copy(embeddedPost = post) }
            }
        }
    }

    fun updateCaption(newText: String) {
        if (pendingEditBaseline == null) pendingEditBaseline = _uiState.value.caption
        _uiState.update { it.copy(caption = newText) }
    }

    /** Call when the caption field loses focus, committing the pending typing run as one undo step. */
    fun commitManualEdit() {
        val baseline = pendingEditBaseline ?: return
        pendingEditBaseline = null
        if (baseline != _uiState.value.caption) pushUndo(baseline)
    }

    fun applyTemplate(template: CaptionTemplate) {
        commitManualEdit()
        pushUndo(_uiState.value.caption)
        _uiState.update { it.copy(caption = template.body) }
    }

    fun saveAsTemplate(title: String) {
        val body = _uiState.value.caption
        if (title.isBlank() || body.isBlank()) return
        viewModelScope.launch {
            captionTemplateDao.insert(CaptionTemplate(title = title, body = body))
        }
    }

    fun deleteTemplate(template: CaptionTemplate) {
        viewModelScope.launch {
            captionTemplateDao.delete(template.id)
        }
    }

    fun enhance() {
        commitManualEdit()
        viewModelScope.launch {
            val before = _uiState.value.caption
            _uiState.update { it.copy(isEnhancing = true) }
            captionEnhancer.enhance(before).fold(
                onSuccess = { enhanced ->
                    pushUndo(before)
                    _uiState.update { it.copy(isEnhancing = false, caption = enhanced) }
                },
                onFailure = { e ->
                    _uiState.update { it.copy(isEnhancing = false, statusMessage = "Enhance failed: ${e.message}") }
                },
            )
        }
    }

    private fun pushUndo(previousText: String) {
        viewModelScope.launch {
            undoEntryDao.push(
                UndoEntry(sessionId = sessionId, previousText = previousText, createdAtEpochMs = System.currentTimeMillis()),
            )
            _uiState.update { it.copy(canUndo = true) }
        }
    }

    fun undo() {
        commitManualEdit()
        viewModelScope.launch {
            val entry = undoEntryDao.peekLatest(sessionId) ?: return@launch
            undoEntryDao.pop(entry.id)
            _uiState.update {
                it.copy(caption = entry.previousText, canUndo = false)
            }
            _uiState.update { it.copy(canUndo = undoEntryDao.peekLatest(sessionId) != null) }
        }
    }
}
