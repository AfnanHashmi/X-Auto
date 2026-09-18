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
            if (clipRecordDao.findByPath(clipPath)?.posted == true) {
                _uiState.update { it.copy(isPosting = false, isPosted = true) }
                return@launch
            }
            _uiState.update { it.copy(isPosting = true, errorMessage = null) }
            mainAccountApi.post(_uiState.value.caption, mediaRef).fold(
                onSuccess = {
                    _uiState.update { it.copy(isPosting = false, isPosted = true) }
                    clipRecordDao.findByPath(clipPath)?.let { record ->
                        clipRecordDao.update(record.copy(posted = true))
                    }
                },
                onFailure = { e ->
                    _uiState.update { it.copy(isPosting = false, errorMessage = e.message ?: "Unknown error") }
                },
            )
        }
    }
}
