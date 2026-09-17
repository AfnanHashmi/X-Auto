package com.clipcut.ui.caption

import com.clipcut.data.db.CaptionTemplate
import com.clipcut.domain.api.MediaRef
import com.clipcut.domain.api.Post

data class CaptionUiState(
    val clipPath: String = "",
    val caption: String = "",
    val isUploading: Boolean = true,
    val mediaRef: MediaRef? = null,
    val embeddedPost: Post? = null,
    val templates: List<CaptionTemplate> = emptyList(),
    val isEnhancing: Boolean = false,
    val canUndo: Boolean = false,
    val statusMessage: String? = null,
) {
    /** Ready to post once the clip has uploaded (so there's a [MediaRef] to attach) and there's text. */
    val canProceedToPost: Boolean get() = mediaRef != null && caption.isNotBlank()
}
