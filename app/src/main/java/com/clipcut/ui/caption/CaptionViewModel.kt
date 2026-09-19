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
import com.clipcut.data.db.getOrCreate
import com.clipcut.domain.api.OutcomeUnknownException
import com.clipcut.domain.api.CaptionEnhancer
import com.clipcut.domain.api.MediaAccountApi
import com.clipcut.domain.api.MediaRef
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

    // In-memory guard against a double tap on the retry button while a request is in flight.
    private var uploadInFlight = false

    private fun uploadClip() {
        viewModelScope.launch {
            // Uploading publishes a real post on the media account, so it must happen at most once
            // per clip -- and this ViewModel is recreated whenever Android kills the app in the
            // background and the user comes back. The record is what survives that.
            val record = clipRecordDao.getOrCreate(clipPath)
            if (record.mediaRefId != null) {
                _uiState.update {
                    it.copy(
                        isUploading = false,
                        mediaRef = MediaRef(id = record.mediaRefId, url = record.mediaRefUrl.orEmpty()),
                    )
                }
                return@launch
            }
            if (record.uploadAttempted) {
                // Died (or lost the reply) mid-request: the media post may already be on X.
                _uiState.update {
                    it.copy(
                        isUploading = false,
                        uploadOutcomeUnknown = true,
                        statusMessage = "The app closed while uploading, so this clip may already be posted on " +
                            "the media account. Check it on X before uploading again.",
                    )
                }
                return@launch
            }
            startUpload()
        }
    }

    /** User-initiated: plain retry after a rejected upload, or "upload anyway" after an unknown outcome. */
    fun retryUpload() {
        viewModelScope.launch { startUpload() }
    }

    private suspend fun startUpload() {
        if (uploadInFlight) return
        uploadInFlight = true
        try {
            val record = clipRecordDao.getOrCreate(clipPath)
            if (record.mediaRefId != null) return
            // Persist the marker BEFORE the request, so a kill at any later point is detectable.
            clipRecordDao.update(record.copy(uploadAttempted = true))
            _uiState.update { it.copy(isUploading = true, uploadOutcomeUnknown = false, statusMessage = null) }

            mediaUploader.upload(File(clipPath)).fold(
                onSuccess = { ref ->
                    // Save the reference before showing it, so "Uploaded" always means "recorded".
                    clipRecordDao.getOrCreate(clipPath).let {
                        clipRecordDao.update(it.copy(mediaRefId = ref.id, mediaRefUrl = ref.url, uploadAttempted = false))
                    }
                    _uiState.update { it.copy(isUploading = false, mediaRef = ref) }
                },
                onFailure = { e ->
                    if (e is OutcomeUnknownException) {
                        // Leave uploadAttempted set: a blind retry could publish a duplicate.
                        _uiState.update {
                            it.copy(
                                isUploading = false,
                                uploadOutcomeUnknown = true,
                                statusMessage = "No reply from X, so the media post may have gone out. " +
                                    "Check the media account before uploading again.",
                            )
                        }
                    } else {
                        // A definitive rejection: nothing was posted, so a retry is safe.
                        clipRecordDao.getOrCreate(clipPath).let { clipRecordDao.update(it.copy(uploadAttempted = false)) }
                        _uiState.update { it.copy(isUploading = false, statusMessage = "Upload failed: ${e.message}") }
                    }
                },
            )
        } finally {
            uploadInFlight = false
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
