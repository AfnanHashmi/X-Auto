package com.clipcut.ui

import android.net.Uri
import com.clipcut.domain.api.MediaRef

/**
 * Nav graph routes for the cut -> caption -> post flow. Path args are `Uri.encode`d when building
 * routes and must be `Uri.decode`d when read back -- clip file paths and caption text both contain
 * characters (`/`, spaces, punctuation) that would otherwise break Navigation Compose's
 * single-segment `{arg}` matching.
 */
sealed class ClipCutDestination(val route: String) {
    /** Nested graph wrapping [Cut] and [Preview], which share one [com.clipcut.ui.cut.CutViewModel]
     * instance scoped to this graph's back stack entry. */
    data object CutFlow : ClipCutDestination("cutFlow")

    data object Cut : ClipCutDestination("cut")

    data object Preview : ClipCutDestination("preview")

    data object Caption : ClipCutDestination("caption/{clipPath}") {
        fun createRoute(clipPath: String) = "caption/${Uri.encode(clipPath)}"
    }

    data object Post : ClipCutDestination(
        "post/{clipPath}/{caption}/{mediaRefId}/{mediaRefUrl}/{mediaRefAuthorHandle}",
    ) {
        // Nav Compose path segments can't be empty, so an unknown handle is encoded as the
        // sentinel "none" rather than "" -- decoded back to blank in PostViewModel.
        fun createRoute(clipPath: String, caption: String, mediaRef: MediaRef) =
            "post/${Uri.encode(clipPath)}/${Uri.encode(caption)}/${Uri.encode(mediaRef.id)}/" +
                "${Uri.encode(mediaRef.url)}/${Uri.encode(mediaRef.authorHandle.ifBlank { "none" })}"
    }
}
