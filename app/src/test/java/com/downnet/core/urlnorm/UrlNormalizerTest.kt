package com.downnet.core.urlnorm

import com.downnet.core.urlnorm.UrlNormalizer.Platform
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class UrlNormalizerTest {

    // --- firstUrl: shared text is messy -----------------------------------

    @Test fun `finds bare url`() {
        assertEquals(
            "https://youtu.be/abc",
            UrlNormalizer.firstUrl("https://youtu.be/abc"),
        )
    }

    @Test fun `finds url inside share text`() {
        assertEquals(
            "https://x.com/user/status/123",
            UrlNormalizer.firstUrl("Check this out lol https://x.com/user/status/123"),
        )
    }

    @Test fun `strips trailing punctuation`() {
        assertEquals(
            "https://instagram.com/p/abc/",
            UrlNormalizer.firstUrl("watch this https://instagram.com/p/abc/."),
        )
    }

    @Test fun `returns null when there is no link`() {
        assertNull(UrlNormalizer.firstUrl("just some words"))
    }

    // --- platform detection ------------------------------------------------

    @Test fun `platform table`() {
        assertEquals(Platform.YOUTUBE, UrlNormalizer.platformOf("https://www.youtube.com/watch?v=x"))
        assertEquals(Platform.YOUTUBE, UrlNormalizer.platformOf("https://youtu.be/x"))
        assertEquals(Platform.YOUTUBE, UrlNormalizer.platformOf("https://music.youtube.com/watch?v=x"))
        assertEquals(Platform.X, UrlNormalizer.platformOf("https://x.com/user/status/1"))
        assertEquals(Platform.X, UrlNormalizer.platformOf("https://twitter.com/user/status/1"))
        assertEquals(Platform.X, UrlNormalizer.platformOf("https://t.co/abc123"))
        assertEquals(Platform.INSTAGRAM, UrlNormalizer.platformOf("https://www.instagram.com/p/abc/"))
        assertEquals(Platform.INSTAGRAM, UrlNormalizer.platformOf("https://instagram.com/reel/abc/"))
        assertEquals(Platform.GENERIC, UrlNormalizer.platformOf("https://reddit.com/r/x"))
    }

    // --- YouTube canonicalization -------------------------------------------

    @Test fun `youtu dot be with tracking to watch`() {
        assertEquals(
            "https://www.youtube.com/watch?v=abc",
            UrlNormalizer.normalize("https://youtu.be/abc?si=TRACKING"),
        )
    }

    @Test fun `shorts to watch`() {
        assertEquals(
            "https://www.youtube.com/watch?v=abc",
            UrlNormalizer.normalize("https://www.youtube.com/shorts/abc?feature=share"),
        )
    }

    @Test fun `watch url keeps only the video id`() {
        assertEquals(
            "https://www.youtube.com/watch?v=abc",
            UrlNormalizer.normalize("https://www.youtube.com/watch?v=abc&feature=share&t=30"),
        )
    }

    @Test fun `music youtube collapses to watch`() {
        assertEquals(
            "https://www.youtube.com/watch?v=abc",
            UrlNormalizer.normalize("https://music.youtube.com/watch?v=abc&si=x"),
        )
    }

    @Test fun `live path to watch`() {
        assertEquals(
            "https://www.youtube.com/watch?v=abc",
            UrlNormalizer.normalize("https://www.youtube.com/live/abc"),
        )
    }

    // --- X canonicalization --------------------------------------------------

    @Test fun `x status with tail junk`() {
        assertEquals(
            "https://x.com/user/status/123",
            UrlNormalizer.normalize("https://x.com/user/status/123/photo/1?s=20&t=ab"),
        )
    }

    @Test fun `twitter to x`() {
        assertEquals(
            "https://x.com/user/status/123",
            UrlNormalizer.normalize("https://twitter.com/user/status/123?t=ab&s=09"),
        )
    }

    @Test fun `web intent share url`() {
        assertEquals(
            "https://x.com/i/status/123",
            UrlNormalizer.normalize("https://x.com/i/web/status/123"),
        )
    }

    @Test fun `old statuses plural`() {
        assertEquals(
            "https://x.com/user/status/123",
            UrlNormalizer.normalize("https://twitter.com/user/statuses/123"),
        )
    }

    // --- Instagram canonicalization -------------------------------------------

    @Test fun `instagram post strips igsh`() {
        assertEquals(
            "https://www.instagram.com/p/abc/",
            UrlNormalizer.normalize("https://www.instagram.com/p/abc/?igsh=XYZ&igshid=XYZ"),
        )
    }

    @Test fun `instagram reel`() {
        assertEquals(
            "https://www.instagram.com/reel/abc/",
            UrlNormalizer.normalize("https://www.instagram.com/reel/abc/?utm_source=ig_share"),
        )
    }

    @Test fun `instagram stories keeps three segments`() {
        assertEquals(
            "https://www.instagram.com/stories/someuser/12345/",
            UrlNormalizer.normalize("https://www.instagram.com/stories/someuser/12345/?x=y"),
        )
    }

    // --- generic --------------------------------------------------------------

    @Test fun `generic strips utm and fbclid`() {
        assertEquals(
            "https://example.com/page?id=2",
            UrlNormalizer.normalize("https://example.com/page?utm_campaign=x&fbclid=abc&id=2#frag"),
        )
    }

    @Test fun `non url passes through`() {
        assertEquals("not a url", UrlNormalizer.normalize("not a url"))
    }

    // --- shorteners ----------------------------------------------------------

    @Test fun `t co is a known shortener`() {
        assertEquals(true, isShortener("https://t.co/abc123"))
    }

    @Test fun `regular host is not a shortener`() {
        assertEquals(false, isShortener("https://youtube.com/watch?v=x"))
    }
}
