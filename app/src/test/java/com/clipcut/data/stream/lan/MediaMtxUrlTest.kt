package com.clipcut.data.stream.lan

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The URL builders are pure, so they are worth pinning: a typo here points the app at the wrong port
 * or path and the failure looks like a network problem rather than a code one.
 */
class MediaMtxUrlTest {
    private val lan = "192.168.1.15"
    private val tailscale = "100.92.14.3"

    @Test
    fun hlsUrlUsesTheStreamPortAndPath() {
        assertEquals("http://$lan:8888/live/test/index.m3u8", MediaMtxConfig.hlsUrl(lan))
    }

    @Test
    fun theSameBuildersWorkForAnyAddressSoRemoteNeedsNoSpecialCase() {
        assertEquals("http://$tailscale:8888/live/test/index.m3u8", MediaMtxConfig.hlsUrl(tailscale))
        assertTrue(MediaMtxConfig.listUrl(tailscale).startsWith("http://$tailscale:9996/list"))
    }

    @Test
    fun clipUrlCarriesTheWindowAndAsksForMp4() {
        val url = MediaMtxConfig.clipUrl(lan, "2026-09-20T12:00:00Z", 15.5)
        assertEquals(
            "http://$lan:9996/get?path=live/test&start=2026-09-20T12:00:00Z&duration=15.5&format=mp4",
            url,
        )
    }

    @Test
    fun cuttingAndWatchingUseTheSamePathSoOneClockGovernsBoth() {
        // Phase 1 deliberately keeps these equal; a preview stream would split them and introduce an offset.
        assertTrue(MediaMtxConfig.hlsUrl(lan).contains("/live/test/"))
        assertTrue(MediaMtxConfig.clipUrl(lan, "x", 1.0).contains("path=live/test"))
        assertTrue(MediaMtxConfig.listUrl(lan).contains("path=live/test"))
    }
}
