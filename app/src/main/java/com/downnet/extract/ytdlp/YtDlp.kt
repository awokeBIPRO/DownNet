package com.downnet.extract.ytdlp

import android.content.Context
import com.chaquo.python.PyObject
import com.chaquo.python.Python
import com.chaquo.python.android.AndroidPython
import com.downnet.core.bin.NativeBinaries
import com.downnet.extract.BridgeResponse
import com.downnet.extract.MediaInfo
import kotlinx.coroutines.withContext
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.serialization.json.Json
import java.util.concurrent.Executors

/**
 * Kotlin side of the yt-dlp bridge. yt-dlp only EXTRACTS here — every
 * download flows through the Kotlin pipeline, so there is exactly one queue,
 * one progress path and one resume/retry implementation.
 *
 * All Python calls run on a single dedicated thread: Chaquopy hosts CPython
 * in-process, so the GIL (plus interpreter startup) serializes naturally —
 * concurrent extract calls would only fight over it.
 */
class YtDlp(private val context: Context) {

    private val dispatcher = Executors.newSingleThreadExecutor().asCoroutineDispatcher()
    private val json = Json { ignoreUnknownKeys = true }

    @Volatile
    private var started = false

    suspend fun extract(url: String): Result<MediaInfo> = withContext(dispatcher) {
        runCatching { extractBlocking(url) }
    }

    private fun extractBlocking(url: String): MediaInfo {
        val missing = NativeBinaries.selfCheck(context)
        check(missing.isEmpty()) {
            "Native binaries missing after install: $missing (packaging regression)"
        }

        ensurePythonStarted()
        val bridge = Python.getInstance().getModule("bridge")
        val raw: String = bridge
            .callAttr(
                "extract", url,
                NativeBinaries.qjs(context).absolutePath,
                NativeBinaries.ensureBin(context).absolutePath,
            )
            .toString()

        val response = json.decodeFromString<BridgeResponse>(raw)
        val info = response.info
        check(response.ok && info != null) { response.error ?: "Extraction failed" }
        check(info.formats.isNotEmpty()) { "No downloadable formats found" }
        return info
    }

    /** Diagnostics for the settings screen / smoke tests. */
    fun selfcheck(): String = runCatching {
        ensurePythonStarted()
        val bridge: PyObject = Python.getInstance().getModule("bridge")
        bridge.callAttr("selfcheck", NativeBinaries.qjs(context).absolutePath).toString()
    }.getOrElse { "selfcheck failed: ${it.message}" }

    private fun ensurePythonStarted() {
        if (started) return
        synchronized(this) {
            if (started) return
            // Lazy rather than in Application.onCreate: interpreter startup
            // costs ~0.5-1s and nothing before the first share needs Python.
            Python.start(AndroidPython(context))
            started = true
        }
    }
}
