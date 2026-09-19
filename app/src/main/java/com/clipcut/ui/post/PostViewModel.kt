package com.clipcut.ui.post

import android.net.Uri
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.clipcut.data.db.ClipRecordDao
import com.clipcut.domain.api.MainAccountApi
import com.clipcut.domain.api.MediaRef
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.io.File
import javax.inject.Inject

@HiltViewModel
class PostViewModel @Inject constructor(
    savedStateHandle: SavedStateHandle,
    private val mainAccountApi: MainAccountApi,
    private val clipRecordDao: ClipRecordDao,
) : ViewModel() {

    private val clipPath: String = Uri.decode(checkNotNull(savedStateHandle["clipPath"]))
    private val mediaRef = MediaRef(
        id = Uri.decode(checkNotNull(savedStateHandle["mediaRefId"])),
        url = Uri.decode(checkNotNull(savedStateHandle["mediaRefUrl"])),
    )

    private val _uiState = MutableStateFlow(
        PostUiState(caption = Uri.decode(checkNotNull(savedStateHandle["caption"]))),
    )
    val uiState: StateFlow<PostUiState> = _uiState.asStateFlow()

    fun post() {
        // Posting is irreversible and this is called both automatically on screen entry and from
        // the Retry button, so guard against a second in-flight or repeat post: a duplicate tweet
        // is exactly the pattern that gets an account flagged.
        if (_uiState.value.isPosting || _uiState.value.isPosted) return
        viewModelScope.launch {
            val record = clipRecordDao.findByPath(clipPath)
            if (record?.posted == true) {
                _uiState.update { it.copy(isPosting = false, isPosted = true) }
                return@launch
            }
            if (record?.postAttempted == true) {
                // The app died between marking postAttempted and clearing it below -- meaning we
                // never got a response, so we genuinely don't know if the post went out. Retrying
                // blind risks a duplicate; surface it instead of guessing.
                _uiState.update {
                    it.copy(
                        isPosting = false,
                        errorMessage = "Couldn't confirm whether this posted before the app closed. " +
                            "Check your main account on X before retrying.",
                    )
                }
                return@launch
            }

            _uiState.update { it.copy(isPosting = true, errorMessage = null) }
            record?.let { clipRecordDao.update(it.copy(postAttempted = true)) }

            mainAccountApi.post(_uiState.value.caption, mediaRef).fold(
                onSuccess = {
                    _uiState.update { it.copy(isPosting = false, isPosted = true) }
                    clipRecordDao.findByPath(clipPath)?.let {
                        clipRecordDao.update(it.copy(posted = true, postAttempted = false))
                    }
                    // The clip only ever needed to be readable up to this point (upload already
                    // happened from the CaptionViewModel's cache copy, posting just links to it);
                    // nothing else in the app reads this file again. The gallery copy in
                    // Movies/ClipCut is untouched -- that's the kept, user-facing record.
                    runCatching { File(clipPath).delete() }
                },
                onFailure = { e ->
                    _uiState.update { it.copy(isPosting = false, errorMessage = e.message ?: "Unknown error") }
                    clipRecordDao.findByPath(clipPath)?.let {
                        clipRecordDao.update(it.copy(postAttempted = false))
                    }
                },
            )
        }
    }
}
