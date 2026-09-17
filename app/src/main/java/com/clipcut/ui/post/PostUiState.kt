package com.clipcut.ui.post

data class PostUiState(
    val caption: String = "",
    val isPosting: Boolean = false,
    val isPosted: Boolean = false,
    val errorMessage: String? = null,
)
