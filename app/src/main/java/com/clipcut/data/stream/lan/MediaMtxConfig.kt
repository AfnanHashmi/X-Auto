package com.clipcut.data.stream.lan

import android.content.Context

/**
 * Where the PC's MediaMTX lives. The phone dials the PC (pull), so this is the only address in the
 * whole pipeline; it's a persisted setting rather than a constant because a DHCP lease change
 * would otherwise mean a rebuild.
 */
object MediaMtxConfig {
    private const val PREFS = "mediamtx"
    private const val KEY_HOST = "host"
    const val DEFAULT_HOST = "192.168.1.11"
    private const val STREAM_PATH = "live/test"
    private const val HLS_PORT = 8888
    private const val PLAYBACK_PORT = 9996

    fun host(context: Context): String =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY_HOST, DEFAULT_HOST) ?: DEFAULT_HOST

    fun setHost(context: Context, host: String) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putString(KEY_HOST, host.trim()).apply()
    }

    fun hlsUrl(host: String) = "http://$host:$HLS_PORT/$STREAM_PATH/index.m3u8"

    /** MediaMTX playback-server clip URL; [startIso] is RFC 3339 UTC, [durationSec] in seconds. */
    fun clipUrl(host: String, startIso: String, durationSec: Double) =
        "http://$host:$PLAYBACK_PORT/get?path=$STREAM_PATH&start=$startIso&duration=$durationSec&format=mp4"
}
