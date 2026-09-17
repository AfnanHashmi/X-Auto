package com.clipcut.data.stream

import java.time.OffsetDateTime

/** One full (non-partial) segment from a media playlist, with its wall-clock start time. */
data class PlaylistSegment(
    val uri: String,
    val programDateTimeEpochMs: Long,
    val durationMs: Long,
)

data class ParsedMediaPlaylist(
    val initSegmentUri: String?,
    val segments: List<PlaylistSegment>,
)

/**
 * Pure parsing of OvenMediaEngine LLHLS playlists: master (`#EXT-X-STREAM-INF`) and media
 * (`#EXT-X-PROGRAM-DATE-TIME` + `#EXTINF` per full segment). Partial segments (`#EXT-X-PART`,
 * `#EXT-X-PRELOAD-HINT`) are intentionally ignored -- only full, already-written segments are
 * ever exported.
 */
object HlsPlaylistParser {
    fun isMasterPlaylist(text: String): Boolean = text.contains("#EXT-X-STREAM-INF")

    /** The URI of the first (highest/only) variant listed after an `#EXT-X-STREAM-INF` tag. */
    fun firstVariantUri(text: String): String? {
        val lines = text.lines()
        val streamInfIndex = lines.indexOfFirst { it.startsWith("#EXT-X-STREAM-INF") }
        if (streamInfIndex == -1) return null
        return lines.drop(streamInfIndex + 1).firstOrNull { it.isNotBlank() && !it.startsWith("#") }
    }

    fun parseMediaPlaylist(text: String): ParsedMediaPlaylist {
        var initUri: String? = null
        var pendingPdtMs: Long? = null
        var pendingDurationMs: Long? = null
        val segments = mutableListOf<PlaylistSegment>()

        for (rawLine in text.lines()) {
            val line = rawLine.trim()
            when {
                line.startsWith("#EXT-X-MAP:") -> initUri = quotedAttr(line, "URI")
                line.startsWith("#EXT-X-PROGRAM-DATE-TIME:") ->
                    pendingPdtMs = parseEpochMs(line.substringAfter(":"))
                line.startsWith("#EXTINF:") ->
                    pendingDurationMs = line.removePrefix("#EXTINF:").substringBefore(",").trim()
                        .toDouble().times(1000).toLong()
                line.isNotEmpty() && !line.startsWith("#") -> {
                    val pdt = pendingPdtMs
                    val duration = pendingDurationMs
                    if (pdt != null && duration != null) {
                        segments += PlaylistSegment(uri = line, programDateTimeEpochMs = pdt, durationMs = duration)
                    }
                    pendingPdtMs = null
                    pendingDurationMs = null
                }
            }
        }
        return ParsedMediaPlaylist(initUri, segments)
    }

    private fun quotedAttr(line: String, key: String): String? =
        Regex("$key=\"([^\"]*)\"").find(line)?.groupValues?.get(1)

    private fun parseEpochMs(value: String): Long =
        OffsetDateTime.parse(value.trim()).toInstant().toEpochMilli()
}
