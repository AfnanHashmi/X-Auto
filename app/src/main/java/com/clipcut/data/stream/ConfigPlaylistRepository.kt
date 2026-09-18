package com.clipcut.data.stream

import com.clipcut.BuildConfig
import javax.inject.Inject

/**
 * Returns the q1080 playlist URL. The `policy`/`signature` on it are no longer authoritative --
 * [StreamSigningInterceptor] replaces them per-request with the currently valid credential, so
 * this URL's own params only matter as a fallback for when the auth chain can't mint one at all.
 * That's also why the URL never needs re-baking into the APK when it expires.
 */
class ConfigPlaylistRepository @Inject constructor() : PlaylistRepository {
    override suspend fun currentPlaylistUrl(): String = BuildConfig.STREAM_URL
}
