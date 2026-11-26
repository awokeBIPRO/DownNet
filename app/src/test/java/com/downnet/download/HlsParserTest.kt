package com.downnet.download

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class HlsParserTest {

    private val master = """
        #EXTM3U
        #EXT-X-STREAM-INF:BANDWIDTH=432000,RESOLUTION=426x240
        v240/index.m3u8
        #EXT-X-STREAM-INF:BANDWIDTH=2160000,RESOLUTION=1280x720
        v720/index.m3u8
        #EXT-X-STREAM-INF:BANDWIDTH=624000,RESOLUTION=640x360
        v360/index.m3u8
    """.trimIndent()

    private val media = """
        #EXTM3U
        #EXT-X-VERSION=3
        #EXT-X-TARGETDURATION:4
        #EXTINF:4.000,
        https://video.cdn.example/seg1.ts
        #EXTINF:3.760,
        https://video.cdn.example/seg2.ts
        #EXTINF:2.240,
        seg3.ts
        #EXT-X-ENDLIST
    """.trimIndent()

    @Test
    fun `master detection and best variant`() {
        assertTrue(HlsParser.isMasterPlaylist(master))
        assertFalse(HlsParser.isMasterPlaylist(media))

        val variants = HlsParser.parseMaster(master, "https://video.cdn.example/master.m3u8")
        assertEquals(3, variants.size)
        val best = variants.maxByOrNull { it.bandwidth }!!
        assertEquals("https://video.cdn.example/v720/index.m3u8", best.url)
        assertEquals(2_160_000L, best.bandwidth)
    }

    @Test
    fun `media playlist parses segments and durations`() {
        val playlist = HlsParser.parseMedia(media, "https://video.cdn.example/v720/index.m3u8")
        assertEquals(3, playlist.segments.size)
        assertEquals("https://video.cdn.example/seg1.ts", playlist.segments[0].url)
        // Relative segment URLs resolve against the playlist URL.
        assertEquals("https://video.cdn.example/v720/seg3.ts", playlist.segments[2].url)
        assertEquals(4.0, playlist.segments[0].durationSec, 0.001)
        assertEquals(10.0, playlist.totalDurationSec, 0.001)
        assertFalse(playlist.isLive)
        assertFalse(playlist.encrypted)
    }

    @Test
    fun `live playlists are detected by missing endlist`() {
        val live = media.replace("#EXT-X-ENDLIST", "")
        assertTrue(HlsParser.parseMedia(live, "https://x/y.m3u8").isLive)
    }

    @Test
    fun `encrypted playlists are flagged`() {
        val locked = "#EXT-X-KEY:METHOD=AES-128,URI=\"key\"\n" + media
        assertTrue(HlsParser.parseMedia(locked, "https://x/y.m3u8").encrypted)
    }
}
