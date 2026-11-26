package com.downnet.extract

import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Decodes fixture JSON exactly as bridge.py emits it. */
class BridgeResponseTest {

    private val json = Json { ignoreUnknownKeys = true }

    @Test
    fun `success payload with formats and headers`() {
        val raw = """
        {
          "ok": true,
          "info": {
            "id": "dQw4w9WgXcQ",
            "title": "Never Gonna Give You Up",
            "uploader": "Rick Astley",
            "duration": 213.0,
            "webpage_url": "https://www.youtube.com/watch?v=dQw4w9WgXcQ",
            "extractor_key": "Youtube",
            "formats": [
              {
                "format_id": "18",
                "ext": "mp4",
                "protocol": "https",
                "url": "https://googlevideo/18",
                "vcodec": "avc1.42001E",
                "acodec": "mp4a.40.2",
                "height": 360,
                "fps": 25.0,
                "tbr": 500.0,
                "filesize": 8345216,
                "http_headers": {"Referer": "https://www.youtube.com/"}
              },
              {
                "format_id": "140",
                "ext": "m4a",
                "protocol": "https",
                "url": "https://googlevideo/140",
                "vcodec": "none",
                "acodec": "mp4a.40.2",
                "abr": 128.0,
                "filesize_approx": 3400000
              }
            ]
          }
        }
        """.trimIndent()

        val response = json.decodeFromString<BridgeResponse>(raw)
        assertTrue(response.ok)
        val info = response.info!!
        assertEquals("Never Gonna Give You Up", info.title)
        assertEquals(2, info.formats.size)

        val muxed = info.formats[0]
        assertTrue(muxed.hasVideo)
        assertTrue(muxed.hasAudio)
        assertTrue(muxed.isDirectFile)
        assertEquals(8345216L, muxed.bytes)
        assertEquals("https://www.youtube.com/", muxed.httpHeaders!!["Referer"])

        val audio = info.formats[1]
        assertFalse(audio.hasVideo)
        assertTrue(audio.hasAudio)
        assertEquals(3400000L, audio.bytes)
    }

    @Test
    fun `error payload`() {
        val raw = """{"ok": false, "error": "ERROR: Unsupported URL"}"""
        val response = json.decodeFromString<BridgeResponse>(raw)
        assertFalse(response.ok)
        assertEquals("ERROR: Unsupported URL", response.error)
    }

    @Test
    fun `unknown keys are ignored`() {
        val raw = """{"ok": true, "future_field": 42, "info": {"id": "x", "formats": []}}"""
        val response = json.decodeFromString<BridgeResponse>(raw)
        assertTrue(response.ok)
        assertEquals("x", response.info?.id)
        assertEquals(0, response.info?.formats?.size)
    }
}
