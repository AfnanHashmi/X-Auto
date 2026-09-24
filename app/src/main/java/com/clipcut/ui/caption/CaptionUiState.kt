package com.clipcut.ui.caption

import com.clipcut.data.db.CaptionTemplate
import com.clipcut.domain.api.CaptionFormat
import com.clipcut.domain.api.CaptionFormatText
import com.clipcut.domain.api.CaptionMood
import com.clipcut.domain.api.CaptionSnapshot
import com.clipcut.domain.api.MediaRef
import com.clipcut.domain.api.Post

data class CaptionUiState(
    val clipPath: String = "",
    /**
     * The post is held as its three parts, not as one string. A plain post's heading cannot be told
     * apart from its caption once they are joined, so [caption] is derived from these and never parsed.
     */
    val format: CaptionFormat = CaptionFormat.PLAIN,
    val heading: String = "",
    val body: String = "",
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
    /** Which media account this clip is being posted to ("DK" or "BB"). */
    val mediaAccountLabel: String = "",
) {
    /** The exact text that is copied and posted: the layout's label, the heading, then the caption. */
    val caption: String get() = CaptionFormatText.compose(format, heading, body)

    /** What Undo restores. */
    fun snapshot() = CaptionSnapshot(format, heading, body)

    /** Ready to post once the clip has uploaded (so there's a [MediaRef] to attach) and there's text. */
    val canProceedToPost: Boolean get() = hasContent && (standalone || mediaRef != null)

    /**
     * A labelled post needs both its heading and caption. A plain post needs any text at all: its
     * heading is optional, so a plain caption on its own is still a complete post.
     */
    val hasContent: Boolean
        get() = if (format == CaptionFormat.PLAIN) heading.isNotBlank() || body.isNotBlank() else heading.isNotBlank() && body.isNotBlank()

    /** Enhancing needs something to enhance: the heading or the caption. */
    val canEnhance: Boolean get() = !isEnhancing && (heading.isNotBlank() || body.isNotBlank())
}
