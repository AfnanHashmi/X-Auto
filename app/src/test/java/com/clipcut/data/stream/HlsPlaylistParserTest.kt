package com.clipcut.data.stream

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class HlsPlaylistParserTest {

    private val masterPlaylist = """
        #EXTM3U
        #EXT-X-VERSION:6
        #EXT-X-INDEPENDENT-SEGMENTS
        #EXT-X-MEDIA:TYPE=AUDIO,GROUP-ID="aac_audio",NAME="Audio_1",DEFAULT=YES,AUTOSELECT=YES,CHANNELS="2",URI="/app/stream/chunklist_4_audio_x_llhls.m3u8"

        #EXT-X-STREAM-INF:BANDWIDTH=4292136,RESOLUTION=1920x1080,FRAME-RATE=30.000,CODECS="avc1.640028,mp4a.40.2",AUDIO="aac_audio"
        /app/stream/chunklist_0_video_x_llhls.m3u8
    """.trimIndent()

    private val mediaPlaylist = """
        #EXTM3U
        #EXT-X-VERSION:6
        #EXT-X-TARGETDURATION:6
        #EXT-X-SERVER-CONTROL:CAN-BLOCK-RELOAD=YES,PART-HOLD-BACK=1.500000
        #EXT-X-PART-INF:PART-TARGET=0.500000
        #EXT-X-MEDIA-SEQUENCE:0
        #EXT-X-MAP:URI="init_0_video_x_llhls.m4s"
        #EXT-X-PROGRAM-DATE-TIME:2026-09-17T15:17:56.622+00:00
        #EXTINF:6.000000,
        seg_0_0_video_x_llhls.m4s
        #EXT-X-PROGRAM-DATE-TIME:2026-09-17T15:18:02.622+00:00
        #EXTINF:6.000000,
        seg_0_1_video_x_llhls.m4s
        #EXT-X-PROGRAM-DATE-TIME:2026-09-17T15:18:08.622+00:00
        #EXT-X-PART:DURATION=0.500000,URI="part_0_2_0_video_x_llhls.m4s",INDEPENDENT=YES
        #EXT-X-PRELOAD-HINT:TYPE=PART,URI="part_0_2_1_video_x_llhls.m4s"
        #EXT-X-RENDITION-REPORT:URI="chunklist_1_video_x_llhls.m3u8",LAST-MSN=1,LAST-PART=1
    """.trimIndent()

    @Test
    fun `detects master playlist by STREAM-INF tag`() {
        assertEquals(true, HlsPlaylistParser.isMasterPlaylist(masterPlaylist))
        assertEquals(false, HlsPlaylistParser.isMasterPlaylist(mediaPlaylist))
    }

    @Test
    fun `extracts the variant URI after STREAM-INF, absolute or relative`() {
        assertEquals("/app/stream/chunklist_0_video_x_llhls.m3u8", HlsPlaylistParser.firstVariantUri(masterPlaylist))
    }

    @Test
    fun `parses init segment and full segments with program-date-time`() {
        val parsed = HlsPlaylistParser.parseMediaPlaylist(mediaPlaylist)

        assertEquals("init_0_video_x_llhls.m4s", parsed.initSegmentUri)
        assertEquals(2, parsed.segments.size)

        val first = parsed.segments[0]
        assertEquals("seg_0_0_video_x_llhls.m4s", first.uri)
        assertEquals(6_000L, first.durationMs)
        // 2026-09-17T15:17:56.622Z
        assertEquals(1789658276622L, first.programDateTimeEpochMs)

        val second = parsed.segments[1]
        assertEquals("seg_0_1_video_x_llhls.m4s", second.uri)
        assertEquals(first.programDateTimeEpochMs + 6_000L, second.programDateTimeEpochMs)
    }

    @Test
    fun `ignores the trailing partial segment with no EXTINF yet`() {
        val parsed = HlsPlaylistParser.parseMediaPlaylist(mediaPlaylist)
        // The 3rd PDT tag (15:18:08.622) has only #EXT-X-PART/PRELOAD-HINT after it, no EXTINF+URI.
        assertEquals(2, parsed.segments.size)
    }

    @Test
    fun `returns null variant URI when there is no STREAM-INF tag`() {
        assertNull(HlsPlaylistParser.firstVariantUri(mediaPlaylist))
    }
}
