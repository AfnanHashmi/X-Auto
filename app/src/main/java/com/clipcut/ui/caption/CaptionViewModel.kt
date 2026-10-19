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
import com.clipcut.domain.api.CaptionFormat
import com.clipcut.domain.api.CaptionFormatText
import com.clipcut.domain.api.CaptionMood
import com.clipcut.domain.api.CaptionSnapshot
import com.clipcut.data.x.MediaAccountSelector
import com.clipcut.domain.api.MediaAccount
import com.clipcut.domain.api.MediaAccountApi
import com.clipcut.domain.api.MediaRef
import com.clipcut.domain.api.MediaUploader
import com.clipcut.domain.api.real.MAX_VARIANTS
import com.clipcut.domain.api.real.MIN_VARIANTS
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
    mediaAccountSelector: MediaAccountSelector,
) : ViewModel() {

    private val clipPath: String = Uri.decode(checkNotNull(savedStateHandle["clipPath"]))
    private val sessionId = UUID.randomUUID().toString()

    // Fixed for this screen's whole life: the clip's record, its upload and its "latest post" lookup all
    // belong to one account, whatever is selected on the home screen afterwards.
    private val account: MediaAccount = mediaAccountSelector.active

    // Manual edits are coalesced into a single undo step per "typing run" rather than per
    // keystroke: the pre-edit post is captured once (here) and pushed to the undo stack only
    // when the run is committed (focus lost, or another mutating action interrupts it).
    private var pendingEditBaseline: CaptionSnapshot? = null

    /** The generator has no clip: nothing is uploaded and the media account is never touched. */
    private val standalone = clipPath == STANDALONE_PATH

    private val _uiState = MutableStateFlow(
        CaptionUiState(clipPath = clipPath, standalone = standalone, isUploading = !standalone, mediaAccountLabel = account.label),
    )
    val uiState: StateFlow<CaptionUiState> = _uiState.asStateFlow()

    init {
        viewModelScope.launch {
            captionTemplateDao.observeAll().collect { templates ->
                _uiState.update { it.copy(templates = templates) }
            }
        }
        if (!standalone) {
            uploadClip()
            loadLatestPost()
        }
    }

    // In-memory guard against a double tap on the retry button while a request is in flight.
    private var uploadInFlight = false

    private fun uploadClip() {
        viewModelScope.launch {
            // Uploading publishes a real post on the media account, so it must happen at most once
            // per clip -- and this ViewModel is recreated whenever Android kills the app in the
            // background and the user comes back. The record is what survives that.
            val record = clipRecordDao.getOrCreate(clipPath, account.name)
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
            val record = clipRecordDao.getOrCreate(clipPath, account.name)
            if (record.mediaRefId != null) return
            // Persist the marker BEFORE the request, so a kill at any later point is detectable.
            clipRecordDao.update(record.copy(uploadAttempted = true))
            _uiState.update { it.copy(isUploading = true, uploadOutcomeUnknown = false, statusMessage = null) }

            mediaUploader.upload(File(clipPath), account).fold(
                onSuccess = { ref ->
                    // Save the reference before showing it, so "Uploaded" always means "recorded".
                    clipRecordDao.getOrCreate(clipPath, account.name).let {
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
                        clipRecordDao.getOrCreate(clipPath, account.name).let { clipRecordDao.update(it.copy(uploadAttempted = false)) }
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
            mediaAccountApi.getLatestPost(account).onSuccess { post ->
                _uiState.update { it.copy(embeddedPost = post) }
            }
        }
    }

    /** Starts a typing run: remembers the post as it was, once, so the whole run undoes as one step. */
    private fun beginEdit() {
        if (pendingEditBaseline == null) pendingEditBaseline = _uiState.value.snapshot()
    }

    fun updateBody(body: String) {
        beginEdit()
        _uiState.update { it.copy(body = body) }
    }

    fun updateHeading(heading: String) {
        beginEdit()
        _uiState.update { it.copy(heading = heading.replace("\n", " ")) }
    }

    /** Call when a field loses focus, committing the pending typing run as one undo step. */
    fun commitManualEdit() {
        val baseline = pendingEditBaseline ?: return
        pendingEditBaseline = null
        if (baseline != _uiState.value.snapshot()) pushUndo(baseline)
    }

    /** Replaces the whole post with a saved one. A labelled template restores its layout, heading and caption. */
    fun applyTemplate(template: CaptionTemplate) {
        commitManualEdit()
        pushUndo(_uiState.value.snapshot())
        val p = CaptionFormatText.parse(template.body)
        _uiState.update { it.copy(format = p.format, heading = p.heading, body = p.body) }
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

    fun selectMood(mood: CaptionMood) {
        _uiState.update { it.copy(mood = mood, readMood = null) }
    }

    /** Switches the post layout. The heading and the caption are kept exactly as typed. */
    fun selectFormat(format: CaptionFormat) {
        commitManualEdit()
        if (_uiState.value.format == format) return
        pushUndo(_uiState.value.snapshot())
        _uiState.update { it.copy(format = format) }
    }

    /** Writes a hashtag at the end of the caption; a tag already there is not repeated. */
    fun addHashtag(tag: String) {
        commitManualEdit()
        val before = _uiState.value.snapshot()
        if (before.body.contains(tag, ignoreCase = true)) return
        val body = if (before.body.isBlank()) tag else before.body.trimEnd() + " " + tag
        pushUndo(before)
        _uiState.update { it.copy(body = body) }
    }

    /**
     * A post with no heading and no label is one caption and is enhanced as one. Anything with a heading or
     * a fixed label is enhanced as heading + caption only, and the label (blank when there is none) is never
     * sent to the model or taken back from it.
     */
    private suspend fun runEnhance(before: CaptionSnapshot): Result<Pair<String, String>> {
        val mood = _uiState.value.mood
        val label = before.format.prefix
        if (label == null && before.heading.isBlank()) {
            return captionEnhancer.enhance(before.body, mood).map { enhanced -> before.heading to enhanced }
        }
        return captionEnhancer.enhanceTemplate(before.heading, before.body, mood, label.orEmpty())
    }

    fun enhance() {
        commitManualEdit()
        viewModelScope.launch {
            val before = _uiState.value.snapshot()
            _uiState.update { it.copy(isEnhancing = true) }
            runEnhance(before).fold(
                onSuccess = { (heading, body) ->
                    pushUndo(before)
                    _uiState.update {
                        it.copy(isEnhancing = false, heading = heading, body = body, readMood = captionEnhancer.lastReadMood, statusMessage = captionEnhancer.lastUsedLabel?.let { l -> "Rewritten by $l" })
                    }
                },
                onFailure = { e ->
                    _uiState.update { it.copy(isEnhancing = false, statusMessage = "Enhance failed: ${e.message}") }
                },
            )
        }
    }

    fun setVariantCount(n: Int) = _uiState.update { it.copy(variantCount = n.coerceIn(MIN_VARIANTS, MAX_VARIANTS)) }

    fun setTrending(text: String) = _uiState.update { it.copy(trending = text) }

    fun setHighQuality(on: Boolean) = _uiState.update { it.copy(highQuality = on) }

    /** Asks for [CaptionUiState.variantCount] takes on the caption text in the chosen mood. Nothing changes until one is picked. */
    fun makeVariants() {
        commitManualEdit()
        val s = _uiState.value
        if (!s.canMakeVariants) return
        _uiState.update { it.copy(isMakingVariants = true, statusMessage = null) }
        viewModelScope.launch {
            captionEnhancer.variants(s.body, s.mood, s.variantCount, s.trending, s.highQuality).fold(
                onSuccess = { v -> _uiState.update { it.copy(isMakingVariants = false, variants = v.variants, variantsLabel = v.label) } },
                onFailure = { e -> _uiState.update { it.copy(isMakingVariants = false, statusMessage = "Variants failed: ${e.message}") } },
            )
        }
    }

    /** Puts a variant in the caption box; Undo brings the previous caption back. */
    fun useVariant(text: String) {
        commitManualEdit()
        val before = _uiState.value.snapshot()
        if (before.body == text) return
        pushUndo(before)
        _uiState.update { it.copy(body = text) }
    }

    fun clearVariants() = _uiState.update { it.copy(variants = emptyList(), variantsLabel = null) }

    private fun pushUndo(previous: CaptionSnapshot) {
        viewModelScope.launch {
            undoEntryDao.push(
                UndoEntry(sessionId = sessionId, previousText = previous.encode(), createdAtEpochMs = System.currentTimeMillis()),
            )
            _uiState.update { it.copy(canUndo = true) }
        }
    }

    fun undo() {
        commitManualEdit()
        viewModelScope.launch {
            val entry = undoEntryDao.peekLatest(sessionId) ?: return@launch
            undoEntryDao.pop(entry.id)
            // Entries are snapshots; anything that is not one is plain text from an older build.
            val restored = CaptionSnapshot.decode(entry.previousText)
                ?: CaptionFormatText.parse(entry.previousText).let { CaptionSnapshot(it.format, it.heading, it.body) }
            _uiState.update {
                it.copy(format = restored.format, heading = restored.heading, body = restored.body, canUndo = false)
            }
            _uiState.update { it.copy(canUndo = undoEntryDao.peekLatest(sessionId) != null) }
        }
    }

    companion object {
        /** Stands in for the clip path when the screen is opened on its own. */
        const val STANDALONE_PATH = "standalone"
    }
}
