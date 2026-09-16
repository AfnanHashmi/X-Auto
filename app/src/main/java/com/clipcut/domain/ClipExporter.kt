package com.clipcut.domain

import java.io.File

/** An in/out selection on the live DVR timeline, in wall-clock epoch millis. */
data class ClipRange(val startEpochMs: Long, val endEpochMs: Long) {
    init {
        require(endEpochMs > startEpochMs) { "endEpochMs must be after startEpochMs" }
    }
}

/**
 * Trims the selected [ClipRange] out of the live buffer into a local MP4.
 *
 * Implemented in Phase 3 with Media3 Transformer (ClippingConfiguration, auto-transmux).
 * See the plan's Gate B for the `.ts`-input fallback if Transformer rejects the source.
 */
interface ClipExporter {
    suspend fun export(range: ClipRange): Result<File>
}
