package com.clipcut.ui

/**
 * Nav graph routes for the cut -> caption -> post flow. A [ClipRef] threads the exported
 * clip file between screens; it is null until the Cut screen produces one.
 */
sealed class ClipCutDestination(val route: String) {
    data object Cut : ClipCutDestination("cut")
    data object Caption : ClipCutDestination("caption/{clipPath}") {
        fun createRoute(clipPath: String) = "caption/$clipPath"
    }
    data object Post : ClipCutDestination("post/{clipPath}/{caption}")
}
