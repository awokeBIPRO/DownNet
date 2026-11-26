package com.downnet.extract

import com.downnet.extract.ytdlp.YtDlp

/**
 * Routes a URL to an extractor.
 *
 * M1: everything goes to yt-dlp (YouTube + ~1000 generic sites). M4 adds
 * Kotlin-native fast paths for X and Instagram; their contract is already
 * fixed — a Kotlin extractor that fails falls back to yt-dlp here, so a
 * platform lockout degrades gracefully instead of erroring.
 */
class ExtractorRegistry(private val ytdlp: YtDlp) {

    suspend fun extract(url: String): Result<MediaInfo> =
        ytdlp.extract(url)
}
