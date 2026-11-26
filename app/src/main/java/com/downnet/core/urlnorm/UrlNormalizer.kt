package com.downnet.core.urlnorm

import java.net.URI

/**
 * Turns whatever a share sheet hands us into a canonical media URL:
 * "Check this out https://youtu.be/xyz?si=junk" -> "https://www.youtube.com/watch?v=xyz".
 *
 * Pure JVM, no Android or network dependency, unit tested. Shortener
 * unwrapping (t.co and friends) lives in [ShortLinkResolver] because it needs
 * the network.
 */
object UrlNormalizer {

    enum class Platform(val label: String) {
        YOUTUBE("YouTube"), X("X"), INSTAGRAM("Instagram"), GENERIC("Web"),
    }

    data class Normalized(val url: String, val platform: Platform)

    private val URL_IN_TEXT = Regex("""https?://[^\s<>"'()\[\]{}]+""", RegexOption.IGNORE_CASE)

    /** First http(s) URL found in arbitrary shared text. */
    fun firstUrl(text: String): String? =
        URL_IN_TEXT.find(text.trim())?.value
            ?.trimEnd('.', ',', ';', '!', '?')
            ?.takeIf { it.startsWith("http", ignoreCase = true) }

    fun platformOf(url: String): Platform {
        val host = hostOf(url)
        return when {
            host == "youtu.be" || host.endsWith("youtube.com") || host.endsWith("youtube-nocookie.com") -> Platform.YOUTUBE
            host == "x.com" || host == "t.co" || host == "twitter.com" || host.endsWith(".twitter.com") -> Platform.X
            host == "instagram.com" || host.endsWith(".instagram.com") || host == "instagr.am" -> Platform.INSTAGRAM
            else -> Platform.GENERIC
        }
    }

    /** Canonical form for the platforms we know; tracking-stripped for the rest. */
    fun normalize(url: String): String {
        val trimmed = url.trim()
        if (!trimmed.startsWith("http", ignoreCase = true)) return trimmed
        return when (platformOf(trimmed)) {
            Platform.YOUTUBE -> normalizeYouTube(trimmed)
            Platform.X -> normalizeX(trimmed)
            Platform.INSTAGRAM -> normalizeInstagram(trimmed)
            Platform.GENERIC -> stripTrackingParams(trimmed)
        }
    }

    // --- YouTube -----------------------------------------------------------

    private fun normalizeYouTube(url: String): String {
        val uri = parse(url) ?: return url
        val host = (uri.host ?: "").lowercase()
        val segments = pathSegments(uri)

        val idFromPath: String? = when {
            host == "youtu.be" -> segments.firstOrNull()
            segments.size >= 2 && segments[0] in YOUTUBE_ID_PATHS -> segments[1]
            else -> null
        }
        val videoId = idFromPath ?: queryParam(uri, "v")
        return if (videoId != null) {
            "https://www.youtube.com/watch?v=$videoId"
        } else {
            stripTrackingParams(url)
        }
    }

    // --- X / Twitter -------------------------------------------------------

    private fun normalizeX(url: String): String {
        val uri = parse(url) ?: return url
        val segments = pathSegments(uri)

        // x.com/<user>/status/<id>... or x.com/i/web/status/<id>
        val statusIndex = segments.indexOfFirst { it == "status" || it == "statuses" }
        if (statusIndex >= 0 && segments.size > statusIndex + 1) {
            val id = segments[statusIndex + 1]
            val user = segments.getOrNull(0)?.takeUnless { it == "i" || it == "intent" }
            val prefix = if (user != null) "$user/" else "i/"
            return "https://x.com/${prefix}status/$id"
        }
        // x.com/intent/tweet?tweet_id=<id>
        queryParam(uri, "tweet_id")?.let { return "https://x.com/i/status/$it" }
        return stripTrackingParams(url)
    }

    // --- Instagram ---------------------------------------------------------

    private fun normalizeInstagram(url: String): String {
        val uri = parse(url) ?: return url
        val segments = pathSegments(uri)
        val keep = when {
            segments.size >= 3 && segments[0] == "stories" -> 3   // stories/<user>/<id>
            segments.size >= 2 && segments[0] in INSTAGRAM_MEDIA_PATHS -> 2 // p|reel|reels|tv/<id>
            else -> 0
        }
        return if (keep > 0) {
            "https://www.instagram.com/" + segments.subList(0, keep).joinToString("/") + "/"
        } else {
            stripTrackingParams(url)
        }
    }

    // --- Generic -----------------------------------------------------------

    private val TRACKING_PARAMS = setOf(
        "si", "feature", "igsh", "igshid", "fbclid", "gclid", "t", "ref_src",
        "ref_url", "cmpid", "share_source", "_hsenc", "_hsmi", "mibextid",
    )

    private fun stripTrackingParams(url: String): String {
        val uri = parse(url) ?: return url
        val rawQuery = uri.rawQuery ?: return dropFragment(url)
        val kept = rawQuery.split('&')
            .filter { it.isNotEmpty() }
            .filter { pair ->
                val key = pair.substringBefore('=')
                !key.startsWith("utm_") && key !in TRACKING_PARAMS
            }
        val base = dropFragment(url)
            .substringBefore('?')
            .removeSuffix("&")
        return if (kept.isEmpty()) base else "$base?${kept.joinToString("&")}"
    }

    // --- helpers -----------------------------------------------------------

    private val YOUTUBE_ID_PATHS = setOf("shorts", "embed", "v", "live")
    private val INSTAGRAM_MEDIA_PATHS = setOf("p", "reel", "reels", "tv")

    private fun parse(url: String): URI? =
        runCatching { URI(url.trim()) }.getOrNull()?.takeIf { it.scheme?.startsWith("http") == true }

    private fun hostOf(url: String): String =
        parse(url)?.host?.lowercase() ?: ""

    private fun pathSegments(uri: URI): List<String> =
        (uri.path ?: "").split('/').filter { it.isNotBlank() }

    private fun queryParam(uri: URI, name: String): String? =
        uri.rawQuery?.split('&')
            ?.firstOrNull { it.substringBefore('=') == name && it.contains('=') }
            ?.substringAfter('=')

    private fun dropFragment(url: String): String = url.substringBefore('#')
}

/** Hosts whose only job is to redirect; unwrap before anything else. */
private val SHORTENERS = setOf(
    "t.co", "bit.ly", "tinyurl.com", "goo.gl", "ow.ly", "is.gd", "buff.ly",
    "vm.tiktok.com", "vt.tiktok.com", "share.epicgames.com",
)

fun isShortener(url: String): Boolean =
    runCatching { URI(url).host?.lowercase() in SHORTENERS }.getOrDefault(false)
