package com.downnet.extract

import com.downnet.extract.VariantPicker.Kind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class VariantPickerTest {

    private fun fmt(
        id: String,
        ext: String = "mp4",
        protocol: String = "https",
        vcodec: String? = "avc1",
        acodec: String? = "mp4a",
        height: Int? = 720,
        tbr: Double? = 1000.0,
        abr: Double? = null,
        filesize: Long? = 1_000_000,
    ) = MediaFormat(
        formatId = id, ext = ext, protocol = protocol, url = "https://cdn/$id",
        vcodec = vcodec, acodec = acodec, height = height, tbr = tbr, abr = abr,
        filesize = filesize,
    )

    @Test
    fun `split video plus audio becomes a merged choice per height`() {
        val info = MediaInfo(
            title = "t",
            formats = listOf(
                fmt("v1080", acodec = "none", height = 1080, filesize = 50_000_000),
                fmt("v720", acodec = "none", height = 720, filesize = 20_000_000),
                fmt("a140", vcodec = "none", height = null, tbr = null, abr = 128.0, filesize = 3_000_000),
            ),
        )
        val choices = VariantPicker.videoChoices(info)
        assertEquals(listOf("1080p", "720p"), choices.map { it.label })
        assertEquals(listOf(Kind.MERGED, Kind.MERGED), choices.map { it.kind })
        // The merged choice carries the video+audio pair.
        assertEquals(listOf("v1080", "a140"), choices[0].formats.map { it.formatId })
        assertEquals("MP4 · 50.5 MB · merge", choices[0].detail)
    }

    @Test
    fun `webm split prefers matroska container`() {
        val info = MediaInfo(
            title = "t",
            formats = listOf(
                fmt("v2160", ext = "webm", acodec = "none", height = 2160, vcodec = "vp9"),
                fmt("awebm", ext = "webm", vcodec = "none", height = null, tbr = null, abr = 160.0),
            ),
        )
        val choices = VariantPicker.videoChoices(info)
        assertEquals(Kind.MERGED, choices[0].kind)
        assertEquals("MKV · merge", choices[0].detail)
    }

    @Test
    fun `merged beats hls beats muxed at the same height`() {
        val info = MediaInfo(
            title = "t",
            formats = listOf(
                fmt("split", acodec = "none", height = 720),
                fmt("hls", protocol = "m3u8_native", height = 720),
                fmt("muxed", height = 720),
                fmt("a140", vcodec = "none", height = null, tbr = null, abr = 128.0),
            ),
        )
        val choices = VariantPicker.videoChoices(info)
        assertEquals(1, choices.size)
        assertEquals(Kind.MERGED, choices[0].kind)
    }

    @Test
    fun `hls choice when no split stream exists`() {
        val info = MediaInfo(
            title = "t",
            formats = listOf(
                fmt("hls", protocol = "m3u8_native", height = 720),
                fmt("hls-low", protocol = "m3u8_native", height = 360),
            ),
        )
        val choices = VariantPicker.videoChoices(info)
        assertEquals(listOf("720p", "360p"), choices.map { it.label })
        assertEquals(listOf(Kind.HLS, Kind.HLS), choices.map { it.kind })
    }

    @Test
    fun `muxed only choice when no audio and no hls`() {
        val info = MediaInfo(
            title = "t",
            formats = listOf(fmt("18", height = 360), fmt("22", height = 720)),
        )
        val choices = VariantPicker.videoChoices(info)
        assertEquals(listOf("720p", "360p"), choices.map { it.label })
        assertEquals(listOf(Kind.MUXED, Kind.MUXED), choices.map { it.kind })
    }

    @Test
    fun `dedup per height prefers mp4 and higher bitrate`() {
        val info = MediaInfo(
            title = "t",
            formats = listOf(
                fmt("webm-720", ext = "webm", height = 720, tbr = 900.0),
                fmt("mp4-720", ext = "mp4", height = 720, tbr = 800.0),
            ),
        )
        val choices = VariantPicker.videoChoices(info)
        assertEquals(1, choices.size)
        assertEquals("mp4-720", choices[0].formats[0].formatId)
    }

    @Test
    fun `video-only without any audio falls back to other kinds`() {
        val info = MediaInfo(
            title = "t",
            formats = listOf(
                fmt("vonly", acodec = "none", height = 1080),
                fmt("muxed", height = 360),
            ),
        )
        // No split pairing possible, no hls; only the muxed row survives.
        val choices = VariantPicker.videoChoices(info)
        assertEquals(listOf("360p"), choices.map { it.label })
    }

    @Test
    fun `audio pick prefers m4a over other containers at similar bitrate`() {
        val info = MediaInfo(
            title = "t",
            formats = listOf(
                fmt("webm-audio", ext = "webm", vcodec = "none", height = null, tbr = null, abr = 140.0),
                fmt("m4a-audio", ext = "m4a", vcodec = "none", height = null, tbr = null, abr = 128.0),
            ),
        )
        assertEquals("m4a-audio", VariantPicker.audioChoice(info)?.formats?.get(0)?.formatId)
    }

    @Test
    fun `no audio when all formats carry video`() {
        val info = MediaInfo(title = "t", formats = listOf(fmt("22", height = 720)))
        assertNull(VariantPicker.audioChoice(info))
    }

    @Test
    fun `image formats become photo choices and skip codecs`() {
        val photo = fmt(
            "p0", ext = "jpg", vcodec = "none", acodec = "none",
            height = null, tbr = null, filesize = 200_000,
        )
        val info = MediaInfo(title = "t", formats = listOf(photo, fmt("18", height = 360)))
        val images = VariantPicker.imageChoices(info)
        assertEquals(1, images.size)
        assertEquals("Photo", images[0].label)
        assertEquals("JPG", images[0].detail)
        assertEquals(Kind.PHOTO, images[0].kind)
        val two = VariantPicker.imageChoices(
            MediaInfo(title = "t", formats = listOf(photo, photo.copy(formatId = "p1")))
        )
        assertEquals(listOf("Photo 1", "Photo 2"), two.map { it.label })
    }

    @Test
    fun `byte formatting`() {
        assertEquals("999 B", VariantPicker.formatBytes(999))
        assertEquals("10 KB", VariantPicker.formatBytes(10_240))
        assertEquals("1.5 MB", VariantPicker.formatBytes(1_572_864))
        assertEquals("2.0 GB", VariantPicker.formatBytes(2L shl 30))
    }
}
