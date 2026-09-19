package com.clipcut.ui.post

import android.net.Uri
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.clipcut.data.db.ClipRecordDao
import com.clipcut.data.db.getOrCreate
import com.clipcut.domain.api.MainAccountApi
import com.clipcut.domain.api.MainPostText
import com.clipcut.domain.api.MediaRef
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.io.File
import javax.inject.Inject

/**
 * The main-account step: copies "caption + embed link" to the clipboard for the user to post
 * manually. Nothing is published from here, so unlike a real post there is no duplicate risk and
 * this can run again whenever the screen is (re)entered -- which also re-fills a clipboard that was
 * overwritten in the meantime.
 */
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
    private val caption = Uri.decode(checkNotNull(savedStateHandle["caption"]))

    private val _uiState = MutableStateFlow(
        PostUiState(caption = caption, copiedText = MainPostText.build(caption, mediaRef.url)),
    )
    val uiState: StateFlow<PostUiState> = _uiState.asStateFlow()

    fun post() {
        if (_uiState.value.isPosting) return
        viewModelScope.launch {
            _uiState.update { it.copy(isPosting = true, errorMessage = null) }
            mainAccountApi.post(caption, mediaRef).fold(
                onSuccess = {
                    _uiState.update { it.copy(isPosting = false, isPosted = true) }
                    clipRecordDao.getOrCreate(clipPath).let { clipRecordDao.update(it.copy(posted = true)) }
                    // The cache copy is no longer needed; the gallery copy in Movies/ClipCut is kept.
                    runCatching { File(clipPath).delete() }
                },
                onFailure = { e ->
                    _uiState.update { it.copy(isPosting = false, errorMessage = e.message ?: "Unknown error") }
                },
            )
        }
    }
}
