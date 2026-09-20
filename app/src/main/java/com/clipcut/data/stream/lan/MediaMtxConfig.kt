package com.clipcut.data.stream.lan

import android.content.Context

/**
 * Where the PC's MediaMTX lives. The phone dials the PC (pull), so this is the only address in the
 * whole pipeline.
 *
 * Two addresses are kept, because the PC has two of them: [Profile.HOME] is its address on the house
 * WiFi, which the router keeps changing, and [Profile.REMOTE] is its fixed Tailscale address, which
 * works from anywhere. Everything else -- ports, stream path, URL shapes -- is identical between
 * them, so switching profile is only ever a change of host.
 */
object MediaMtxConfig {
    enum class Profile(val label: String) { HOME("Home"), REMOTE("Remote") }

    private const val PREFS = "mediamtx"

    // The original single-host key, still the home address, so an existing install keeps its setting.
    private const val KEY_HOME_HOST = "host"
    private const val KEY_REMOTE_HOST = "remote_host"
    private const val KEY_ACTIVE = "active_profile"

    /** Only used before an address has ever been set in the app; edit it there, not here. */
    const val DEFAULT_HOST = "192.168.1.4"
    private const val STREAM_PATH = "live/test"
    private const val HLS_PORT = 8888
    private const val PLAYBACK_PORT = 9996

    private fun prefs(context: Context) = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun activeProfile(context: Context): Profile =
        runCatching { Profile.valueOf(prefs(context).getString(KEY_ACTIVE, null) ?: "") }.getOrDefault(Profile.HOME)

    fun setActiveProfile(context: Context, profile: Profile) {
        prefs(context).edit().putString(KEY_ACTIVE, profile.name).apply()
    }

    fun hostOf(context: Context, profile: Profile): String {
        val key = if (profile == Profile.REMOTE) KEY_REMOTE_HOST else KEY_HOME_HOST
        return prefs(context).getString(key, DEFAULT_HOST)?.takeIf { it.isNotBlank() } ?: DEFAULT_HOST
    }

    /** The address of whichever profile is selected. Every caller in the app reads through this. */
    fun host(context: Context): String = hostOf(context, activeProfile(context))

    /** Sets the address of the selected profile, so editing the field only ever changes that one. */
    fun setHost(context: Context, host: String) {
        val key = if (activeProfile(context) == Profile.REMOTE) KEY_REMOTE_HOST else KEY_HOME_HOST
        prefs(context).edit().putString(key, host.trim()).apply()
    }

    fun hlsUrl(host: String) = "http://$host:$HLS_PORT/$STREAM_PATH/index.m3u8"

    /** MediaMTX playback-server listing of the recordings it holds (one entry per unbroken run). */
    fun listUrl(host: String) = "http://$host:$PLAYBACK_PORT/list?path=$STREAM_PATH"

    /** MediaMTX playback-server clip URL; [startIso] is RFC 3339 UTC, [durationSec] in seconds. */
    fun clipUrl(host: String, startIso: String, durationSec: Double) =
        "http://$host:$PLAYBACK_PORT/get?path=$STREAM_PATH&start=$startIso&duration=$durationSec&format=mp4"
}
