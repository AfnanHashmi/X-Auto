package com.clipcut.data.stream

import com.clipcut.BuildConfig
import javax.inject.Inject

/**
 * Phase 2 spike stand-in: returns a manually-minted URL from `local.properties`.
 *
 * Replaced in Phase 5/6 by an implementation that runs the login -> access_session ->
 * /api/stream/urls flow (see docs/phase0-probe-findings.md) and re-mints before the ~12h expiry.
 */
class ConfigPlaylistRepository @Inject constructor() : PlaylistRepository {
    override suspend fun currentPlaylistUrl(): String = BuildConfig.STREAM_URL
}
