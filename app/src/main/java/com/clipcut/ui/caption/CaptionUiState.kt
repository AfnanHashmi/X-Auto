package com.clipcut.ui.caption

import com.clipcut.data.db.CaptionTemplate
import com.clipcut.domain.api.CaptionFormat
import com.clipcut.domain.api.CaptionFormatText
import com.clipcut.domain.api.CaptionMood
import com.clipcut.domain.api.FormattedCaption
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
    val mood: CaptionMood = CaptionMood.Default,
    /** The media post may already exist on X; uploading again needs an explicit, informed tap. */
    val uploadOutcomeUnknown: Boolean = false,
    /** The generator opened without a clip: nothing to upload, and only the caption is copied. */
    val standalone: Boolean = false,
) {
    /** Ready to post once the clip has uploaded (so there's a [MediaRef] to attach) and there's text. */
    val canProceedToPost: Boolean get() = hasContent && (standalone || mediaRef != null)

    /** [caption] split into layout label / heading / body. Derived, so undo and templates just work. */
    val formatted: FormattedCaption get() = CaptionFormatText.parse(caption)

    /** A templated post needs both its heading and caption; a plain one just needs text. */
    val hasContent: Boolean
        get() = formatted.let { if (it.format == CaptionFormat.PLAIN) caption.isNotBlank() else it.heading.isNotBlank() && it.body.isNotBlank() }

    /** Enhancing needs something to enhance: any text for plain, the heading or caption for templated. */
    val canEnhance: Boolean
        get() = !isEnhancing && formatted.let { if (it.format == CaptionFormat.PLAIN) caption.isNotBlank() else it.heading.isNotBlank() || it.body.isNotBlank() }
}
