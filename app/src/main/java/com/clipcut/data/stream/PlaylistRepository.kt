package com.clipcut.data.stream

/** Source of the live HLS playlist URL to play/scrub. */
interface PlaylistRepository {
    suspend fun currentPlaylistUrl(): String
}
