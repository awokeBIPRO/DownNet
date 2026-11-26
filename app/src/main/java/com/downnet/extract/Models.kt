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
 * A choice is a COMPOSITION: one file (muxed, audio, photo), a split
 * video+audio pair to merge with ffmpeg, or an HLS playlist to reassemble.
 */
object VariantPicker {

    enum class Kind { MERGED, MUXED, HLS, AUDIO, PHOTO }

    data class Choice(
        val formats: List<MediaFormat>,
        val kind: Kind,
        val label: String,
        val detail: String,
    )

    /**
     * One row per resolution, best composition per resolution:
     * merged split-streams > HLS > ready-muxed file.
     */
    fun videoChoices(info: MediaInfo, limit: Int = 10): List<Choice> {
        val direct = info.formats.filter { it.isDirectFile }
        val muxed = direct.filter { it.hasVideo && it.hasAudio }.groupBy { it.height ?: 0 }
        val split = direct.filter { it.hasVideo && !it.hasAudio }.groupBy { it.height ?: 0 }
        val hls = info.formats
            .filter { (it.protocol?.startsWith("m3u8") == true) && it.hasVideo && it.url != null }
            .groupBy { it.height ?: 0 }
        val audio = bestAudioFormat(info)

        val heights = (muxed.keys + split.keys + hls.keys).toSortedSet(
            compareByDescending { it }
        )
        val choices = mutableListOf<Choice>()
        for (height in heights) {
            val splitBest = split[height]?.maxWith(
                compareBy({ it.ext == "webm" }, { it.tbr ?: 0.0 })
            )
            if (splitBest != null && audio != null) {
                val container = if (splitBest.ext == "webm" || audio.ext == "webm") "mkv" else "mp4"
                val totalBytes = (splitBest.bytes ?: 0L) + (audio.bytes ?: 0L)
                choices += Choice(
                    formats = listOf(splitBest, audio),
                    kind = Kind.MERGED,
                    label = heightLabel(height),
                    detail = listOfNotNull(
                        container.uppercase(),
                        if (totalBytes > 0) formatBytes(totalBytes) else null,
                        "merge",
                    ).joinToString(" · "),
                )
                continue
            }
            val hlsBest = hls[height]?.maxWith(compareBy { it.tbr ?: 0.0 })
            if (hlsBest != null) {
                choices += Choice(
                    formats = listOf(hlsBest),
                    kind = Kind.HLS,
                    label = heightLabel(height),
                    detail = "HLS → MP4",
                )
                continue
            }
            val muxedBest = muxed[height]?.maxWith(
                compareBy({ it.ext == "mp4" }, { it.tbr ?: 0.0 })
            )
            if (muxedBest != null) {
                choices += Choice(
                    formats = listOf(muxedBest),
                    kind = Kind.MUXED,
                    label = heightLabel(height),
                    detail = listOfNotNull(
                        muxedBest.ext?.uppercase(),
                        muxedBest.bytes?.let { formatBytes(it) },
                    ).joinToString(" · "),
                )
            }
        }
        return choices.take(limit)
    }

    /** Static posts: no codecs at all, just a direct image file. */
    fun imageChoices(info: MediaInfo): List<Choice> {
        val images = info.formats.filter {
            !it.hasVideo && !it.hasAudio && it.isDirectFile
        }
        return images.mapIndexed { index, fmt ->
            Choice(
                formats = listOf(fmt),
                kind = Kind.PHOTO,
                label = if (images.size > 1) "Photo ${index + 1}" else "Photo",
                detail = fmt.ext?.uppercase() ?: "Image",
            )
        }
    }

    fun audioChoice(info: MediaInfo): Choice? =
        bestAudioFormat(info)?.let { fmt ->
            Choice(
                formats = listOf(fmt),
                kind = Kind.AUDIO,
                label = "Audio",
                detail = listOfNotNull(
                    fmt.ext?.uppercase(),
                    fmt.abr?.let { "%.0f kbps".format(it) },
                    fmt.bytes?.let { formatBytes(it) },
                ).joinToString(" · "),
            )
        }

    private fun bestAudioFormat(info: MediaInfo): MediaFormat? =
        info.formats.asSequence()
            .filter { it.hasAudio && !it.hasVideo && it.isDirectFile }
            .maxByOrNull { (if (it.ext == "m4a" || it.ext == "mp4") 1e6 else 0.0) + (it.abr ?: 0.0) }

    private fun heightLabel(height: Int) = if (height > 0) "${height}p" else "Video"

    fun formatBytes(bytes: Long): String = when {
        bytes >= 1L shl 30 -> "%.1f GB".format(bytes / 1073741824.0)
        bytes >= 1L shl 20 -> "%.1f MB".format(bytes / 1048576.0)
        bytes >= 1L shl 10 -> "%.0f KB".format(bytes / 1024.0)
        else -> "$bytes B"
    }
}
