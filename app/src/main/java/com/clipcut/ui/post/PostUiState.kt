package com.clipcut.ui.post

data class PostUiState(
    val caption: String = "",
    val isPosting: Boolean = false,
    val isPosted: Boolean = false,
    val errorMessage: String? = null,
    /** The exact text placed on the clipboard, shown so it can be checked before pasting. */
    val copiedText: String = "",
)
