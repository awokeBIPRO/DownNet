package com.downnet.download

import android.content.Context
import com.downnet.core.bin.NativeBinaries
import com.downnet.extract.MediaFormat
import java.io.File
import kotlin.concurrent.thread

/**
 * Drives the bundled ffmpeg CLI for the two remux jobs DownNet needs:
 * merging a split video+audio pair and reassembling HLS segments. Pure
 * stream copy (-c copy) — no transcode, battery-friendly, fast.
 *
 * Progress comes from ffmpeg's `-progress pipe:1` machine-readable output
 * (out_time_us vs the known total duration).
 */
object Muxer {

    /** MP4 when the pair is h264+aac (plays everywhere); Matroska otherwise. */
    fun containerFor(video: MediaFormat, audio: MediaFormat): String {
        val h264 = video.vcodec?.let { it.startsWith("avc") || it.startsWith("h264") } == true
        val aac = audio.acodec?.let { it.startsWith("mp4a") || it.startsWith("aac") } == true
        return if (h264 && aac) "mp4" else "mkv"
    }

    fun merge(
        context: Context,
        video: File,
        audio: File,
        output: File,
        container: String,
        durationSec: Double?,
        onProgress: (Float) -> Unit,
    ) {
        val args = mutableListOf(
            "-i", video.absolutePath,
            "-i", audio.absolutePath,
            "-map", "0:v:0", "-map", "1:a:0",
            "-c", "copy",
        )
        if (container == "mp4") args += listOf("-movflags", "+faststart")
        args += output.absolutePath
        exec(context, args, durationSec, onProgress)
    }

    /** MPEG-TS segments (X video) → MP4 with AAC config conversion. */
    fun concat(
        context: Context,
        listFile: File,
        output: File,
        durationSec: Double?,
        onProgress: (Float) -> Unit,
    ) {
        exec(
            context,
            listOf(
                "-f", "concat", "-safe", "0", "-i", listFile.absolutePath,
                "-c", "copy", "-bsf:a", "aac_adtstoasc",
                "-movflags", "+faststart",
                output.absolutePath,
            ),
            durationSec, onProgress,
        )
    }

    private fun exec(
        context: Context,
        args: List<String>,
        durationSec: Double?,
        onProgress: (Float) -> Unit,
    ) {
        val ffmpeg = NativeBinaries.ffmpeg(context)
        check(ffmpeg.canExecute()) { "ffmpeg binary not executable at ${ffmpeg.path}" }

        val command = listOf(
            ffmpeg.absolutePath,
            "-hide_banner", "-loglevel", "error", "-nostdin", "-progress", "pipe:1",
        ) + args

        val process = ProcessBuilder(command).start()

        // Drain stderr (ffmpeg's error channel) on its own thread so the
        // pipe never blocks the process.
        val stderr = StringBuilder()
        val errThread = thread(isDaemon = true, name = "ffmpeg-stderr") {
            process.errorStream.bufferedReader().forEachLine { line ->
                synchronized(stderr) {
                    if (stderr.length < 4000) stderr.append(line).append('\n')
                }
            }
        }

        try {
            process.inputStream.bufferedReader().forEachLine { line ->
                val eq = line.indexOf('=')
                if (eq <= 0) return@forEachLine
                if (line.substring(0, eq) == "out_time_us") {
                    val micros = line.substring(eq + 1).toLongOrNull() ?: return@forEachLine
                    if (durationSec != null && durationSec > 0) {
                        onProgress(
                            ((micros / 1_000_000.0) / durationSec).toFloat().coerceIn(0f, 1f)
                        )
                    }
                }
            }
            val exit = process.waitFor()
            errThread.join(2000)
            check(exit == 0) {
                "ffmpeg failed (exit $exit): ${synchronized(stderr) { stderr.toString() }.take(400)}"
            }
        } catch (t: Throwable) {
            runCatching { process.destroyForcibly() }
            throw t
        }
    }
}
