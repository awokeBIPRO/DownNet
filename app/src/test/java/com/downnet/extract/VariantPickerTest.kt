package com.downnet.extract

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
    fun `muxed list is sorted by height descending`() {
        val info = MediaInfo(
            title = "t",
            formats = listOf(fmt("18", height = 360), fmt("22", height = 720), fmt("h", height = 1080)),
        )
        val labels = VariantPicker.muxedChoices(info).map { it.label }
        assertEquals(listOf("1080p", "720p", "360p"), labels)
    }

    @Test
    fun `deduplicates per height preferring mp4 and higher bitrate`() {
        val info = MediaInfo(
            title = "t",
            formats = listOf(
                fmt("webm-720", ext = "webm", height = 720, tbr = 900.0),
                fmt("mp4-720", ext = "mp4", height = 720, tbr = 800.0),
            ),
        )
        val choices = VariantPicker.muxedChoices(info)
        assertEquals(1, choices.size)
        assertEquals("mp4-720", choices[0].format.formatId)
    }

    @Test
    fun `video-only and audio-only streams are excluded from muxed list`() {
        val info = MediaInfo(
            title = "t",
            formats = listOf(
                fmt("muxed", height = 360),
                fmt("vonly", acodec = "none", height = 1080),
                fmt("aonly", vcodec = "none", height = null, tbr = null, abr = 128.0),
            ),
        )
        val ids = VariantPicker.muxedChoices(info).map { it.format.formatId }
        assertEquals(listOf("muxed"), ids)
    }

    @Test
    fun `m3u8 and dash manifests are not offered as files`() {
        val info = MediaInfo(
            title = "t",
            formats = listOf(
                fmt("hls", protocol = "m3u8_native", height = 1080),
                fmt("dash", protocol = "dash", height = 1080),
                fmt("direct", height = 360),
            ),
        )
        val ids = VariantPicker.muxedChoices(info).map { it.format.formatId }
        assertEquals(listOf("direct"), ids)
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
        assertEquals("m4a-audio", VariantPicker.audioChoice(info)?.format?.formatId)
    }

    @Test
    fun `no audio when all formats carry video`() {
        val info = MediaInfo(title = "t", formats = listOf(fmt("22", height = 720)))
        assertNull(VariantPicker.audioChoice(info))
    }

    @Test
    fun `byte formatting`() {
        assertEquals("999 B", VariantPicker.formatBytes(999))
        assertEquals("10 KB", VariantPicker.formatBytes(10_240))
        assertEquals("1.5 MB", VariantPicker.formatBytes(1_572_864))
        assertEquals("2.0 GB", VariantPicker.formatBytes(2L shl 30))
    }
}
