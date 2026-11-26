package com.downnet.extract

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/** Wire contract of bridge.py — one JSON shape for success and failure. */
@Serializable
data class BridgeResponse(
    val ok: Boolean = false,
    val info: MediaInfo? = null,
    val error: String? = null,
)

@Serializable
data class MediaInfo(
    val id: String? = null,
    val title: String? = null,
    val uploader: String? = null,
    val duration: Double? = null,
    val thumbnail: String? = null,
    @SerialName("webpage_url") val webpageUrl: String? = null,
    @SerialName("extractor_key") val extractorKey: String? = null,
    val formats: List<MediaFormat> = emptyList(),
)

@Serializable
data class MediaFormat(
    @SerialName("format_id") val formatId: String? = null,
    val ext: String? = null,
    val protocol: String? = null,
    val url: String? = null,
    val vcodec: String? = null,
    val acodec: String? = null,
    val width: Int? = null,
    val height: Int? = null,
    val fps: Double? = null,
    val tbr: Double? = null,
    val abr: Double? = null,
    val filesize: Long? = null,
    @SerialName("filesize_approx") val filesizeApprox: Long? = null,
    @SerialName("http_headers") val httpHeaders: Map<String, String>? = null,
) {
    /** yt-dlp uses the string "none" for absent codecs. */
    val hasVideo: Boolean get() = vcodec != null && vcodec != "none"
    val hasAudio: Boolean get() = acodec != null && acodec != "none"

    /**
     * Only direct file URLs are downloadable by the M1 pipeline; m3u8/DASH
     * manifests need the HLS/mux machinery that lands with the real
     * downloader (M2). Downloading a manifest URL as a file yields a tiny
     * playlist — this filter is what prevents that.
     */
    val isDirectFile: Boolean
        get() = url != null && (protocol == null || protocol.startsWith("http"))

    val bytes: Long? get() = filesize ?: filesizeApprox
}

/**
 * Turns yt-dlp's format soup into the short list the quality sheet shows.
 * M1 policy: muxed (video+audio in one file) rows plus one best audio-only
 * row; split video+audio streams with ffmpeg merging arrive in M2.
 */
object VariantPicker {

    data class Choice(
        val format: MediaFormat,
        val isAudioOnly: Boolean,
        val label: String,
        val detail: String,
    )

    fun muxedChoices(info: MediaInfo, limit: Int = 8): List<Choice> =
        info.formats.asSequence()
            .filter { it.hasVideo && it.hasAudio && it.isDirectFile }
            .groupBy { it.height ?: 0 }
            .map { (_, group) ->
                group.maxWith(
                    compareBy({ it.ext == "mp4" }, { it.tbr ?: 0.0 })
                )
            }
            .sortedByDescending { it.height ?: 0 }
            .take(limit)
            .map { fmt ->
                Choice(
                    format = fmt,
                    isAudioOnly = false,
                    label = if ((fmt.height ?: 0) > 0) "${fmt.height}p" else "Video",
                    detail = listOfNotNull(
                        fmt.ext?.uppercase(),
                        fmt.bytes?.let { formatBytes(it) },
                    ).joinToString(" · "),
                )
            }

    fun audioChoice(info: MediaInfo): Choice? =
        info.formats.asSequence()
            .filter { it.hasAudio && !it.hasVideo && it.isDirectFile }
            .maxByOrNull { (if (it.ext == "m4a" || it.ext == "mp4") 1e6 else 0.0) + (it.abr ?: 0.0) }
            ?.let { fmt ->
                Choice(
                    format = fmt,
                    isAudioOnly = true,
                    label = "Audio",
                    detail = listOfNotNull(
                        fmt.ext?.uppercase(),
                        fmt.abr?.let { "%.0f kbps".format(it) },
                        fmt.bytes?.let { formatBytes(it) },
                    ).joinToString(" · "),
                )
            }

    fun formatBytes(bytes: Long): String = when {
        bytes >= 1L shl 30 -> "%.1f GB".format(bytes / 1073741824.0)
        bytes >= 1L shl 20 -> "%.1f MB".format(bytes / 1048576.0)
        bytes >= 1L shl 10 -> "%.0f KB".format(bytes / 1024.0)
        else -> "$bytes B"
    }
}
