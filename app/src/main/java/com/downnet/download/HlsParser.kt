package com.downnet.download

import java.net.URI

/**
 * Minimal HLS parser — just enough for what media playlists actually need:
 * master → best variant, media → ordered segment list with durations.
 * Encrypted playlists (#EXT-X-KEY) and live streams (no ENDLIST) are
 * detected and refused by the caller with a clear message.
 * Pure JVM, unit tested.
 */
object HlsParser {

    data class MasterVariant(val url: String, val bandwidth: Long)

    data class Segment(val url: String, val durationSec: Double)

    data class MediaPlaylist(
        val segments: List<Segment>,
        val totalDurationSec: Double,
        val isLive: Boolean,
        val encrypted: Boolean,
    )

    fun isMasterPlaylist(text: String): Boolean = text.contains("#EXT-X-STREAM-INF")

    fun parseMaster(text: String, baseUrl: String): List<MasterVariant> {
        val variants = mutableListOf<MasterVariant>()
        var pendingBandwidth: Long? = null
        for (raw in text.lines()) {
            val line = raw.trim()
            when {
                line.startsWith("#EXT-X-STREAM-INF") ->
                    pendingBandwidth = Regex("BANDWIDTH=(\\d+)")
                        .find(line)?.groupValues?.get(1)?.toLongOrNull() ?: 0L
                line.isNotEmpty() && !line.startsWith("#") && pendingBandwidth != null -> {
                    variants += MasterVariant(resolve(baseUrl, line), pendingBandwidth)
                    pendingBandwidth = null
                }
            }
        }
        return variants
    }

    fun parseMedia(text: String, baseUrl: String): MediaPlaylist {
        val segments = mutableListOf<Segment>()
        var pendingDuration: Double? = null
        var encrypted = false
        for (raw in text.lines()) {
            val line = raw.trim()
            when {
                line.startsWith("#EXT-X-KEY") -> encrypted = true
                line.startsWith("#EXTINF:") ->
                    pendingDuration = line.removePrefix("#EXTINF:")
                        .substringBefore(',')
                        .toDoubleOrNull()
                line.isNotEmpty() && !line.startsWith("#") -> {
                    segments += Segment(resolve(baseUrl, line), pendingDuration ?: 0.0)
                    pendingDuration = null
                }
            }
        }
        val total = segments.sumOf { it.durationSec }
        return MediaPlaylist(
            segments = segments,
            totalDurationSec = total,
            isLive = !text.contains("#EXT-X-ENDLIST"),
            encrypted = encrypted,
        )
    }

    private fun resolve(base: String, ref: String): String =
        runCatching { URI(base).resolve(ref.trim()).toString() }.getOrDefault(ref)
}
