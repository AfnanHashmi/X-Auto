package com.clipcut.ui

import android.net.Uri
import com.clipcut.domain.api.MediaRef

/**
 * Nav graph routes for the caption -> post flow reached from [com.clipcut.lan.CaptionPostActivity].
 * Path args are `Uri.encode`d when building routes and must be `Uri.decode`d when read back -- clip
 * file paths and caption text both contain characters (`/`, spaces, punctuation) that would
 * otherwise break Navigation Compose's single-segment `{arg}` matching.
 *
 * Deliberately just these two routes, not the old `ClipCutDestination`'s full Cut/Preview/Caption/
 * Post graph -- the LAN screen (`LanBufferActivity`) has its own scrubbing UI built directly into
 * it, not via a shared ViewModel/nav-graph the way the old LLHLS Cut/Preview screens worked.
 */
sealed class PostFlowDestination(val route: String) {
    data object Caption : PostFlowDestination("caption/{clipPath}") {
        fun createRoute(clipPath: String) = "caption/${Uri.encode(clipPath)}"
    }

    data object Post : PostFlowDestination("post/{clipPath}/{caption}/{mediaRefId}/{mediaRefUrl}") {
        fun createRoute(clipPath: String, caption: String, mediaRef: MediaRef) =
            "post/${Uri.encode(clipPath)}/${Uri.encode(caption)}/${Uri.encode(mediaRef.id)}/${Uri.encode(mediaRef.url)}"
    }
}
