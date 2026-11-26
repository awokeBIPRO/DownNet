package com.downnet.download

import android.content.Context
import android.net.Uri
import android.os.SystemClock
import com.downnet.core.urlnorm.UrlNormalizer.Platform
import com.downnet.extract.MediaFormat
import com.downnet.extract.MediaInfo
import com.downnet.share.SharingShortcuts
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.io.FileOutputStream
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong

/**
 * M1 in-memory download queue: sequential worker, Range-ready staging, then
 * MediaStore publish. One download at a time — the M2 milestone replaces the
 * in-flight list with the Room queue + dataSync foreground service, HTTP
 * Range-resume and the 403-re-extract self-heal; the public surface
 * (enqueue + items StateFlow) is designed to survive that swap.
 */
class DownloadRepository(
    private val context: Context,
    sharedClient: OkHttpClient,
) {

    enum class Status { QUEUED, DOWNLOADING, PUBLISHING, DONE, FAILED }

    data class DownloadItem(
        val id: Long,
        val title: String,
        val detail: String,
        val platform: Platform?,
        val status: Status,
        val progress: Float = 0f,
        val bytesDone: Long = 0,
        val bytesTotal: Long? = null,
        val speed: Long? = null,      // bytes/s, while DOWNLOADING
        val error: String? = null,
        val contentUri: String? = null,
    )

    private class Job(
        val id: Long,
        val url: String,
        val headers: Map<String, String>?,
        val filename: String,
        val mime: String,
        val kind: MediaKind,
        val fromShare: Boolean,
        val notifId: Int,
    )

    private val client = sharedClient.newBuilder()
        // A transfer with moving bytes should never die on a timer.
        .readTimeout(60, TimeUnit.SECONDS)
        .callTimeout(0, TimeUnit.MILLISECONDS)
        .build()

    private val _items = MutableStateFlow<List<DownloadItem>>(emptyList())
    val items: StateFlow<List<DownloadItem>> = _items.asStateFlow()

    private val queue = Channel<Job>(Channel.UNLIMITED)
    private val nextId = AtomicLong(1)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    init {
        scope.launch {
            for (job in queue) {
                runJob(job)
            }
        }
    }

    fun enqueue(
        info: MediaInfo,
        format: MediaFormat,
        platform: Platform?,
        fromShare: Boolean,
    ): DownloadItem {
        val id = nextId.getAndIncrement()
        val ext = format.ext ?: "bin"
        val kind = when {
            format.hasAudio && !format.hasVideo -> MediaKind.AUDIO
            format.hasVideo -> MediaKind.VIDEO
            else -> MediaKind.IMAGE
        }
        val title = sanitizeFilename(info.title ?: "download")
        val item = DownloadItem(
            id = id,
            title = title,
            detail = listOfNotNull(
                if (format.hasVideo && format.hasAudio && (format.height ?: 0) > 0) {
                    "${format.height}p"
                } else {
                    null
                },
                format.ext?.uppercase(),
            ).joinToString(" · "),
            platform = platform,
            status = Status.QUEUED,
        )
        _items.update { it + item }
        queue.trySend(
            Job(
                id = id,
                url = format.url.orEmpty(),
                headers = format.httpHeaders,
                filename = "$title.$ext",
                mime = MediaStorePublisher.mimeFor(ext),
                kind = kind,
                fromShare = fromShare,
                notifId = (1000 + id).toInt(),
            )
        )
        return item
    }

    fun remove(id: Long) {
        _items.update { list -> list.filterNot { it.id == id } }
    }

    // --- worker ------------------------------------------------------------

    private suspend fun runJob(job: Job) {
        val partialDir = File(context.filesDir, "partial").apply { mkdirs() }
        val staged = File(partialDir, "job-${job.id}.part")
        try {
            if (job.url.isEmpty()) error("No URL for this format")
            val request = Request.Builder()
                .url(job.url)
                .header("User-Agent", DOWNLOAD_UA)
                .apply { job.headers?.forEach { (k, v) -> header(k, v) } }
                .build()

            updateItem(job.id) { it.copy(status = Status.DOWNLOADING, progress = 0f) }

            withContext(Dispatchers.IO) {
                client.newCall(request).execute().use { response ->
                    check(response.isSuccessful) { "HTTP ${response.code}" }
                    val body = response.body ?: error("Empty response body")
                    val total = body.contentLength().takeIf { it > 0 }
                    var lastEmit = 0L
                    var startedAt = 0L
                    var done = 0L
                    body.byteStream().use { input ->
                        FileOutputStream(staged).use { out ->
                            val buffer = ByteArray(64 * 1024)
                            while (true) {
                                val read = input.read(buffer)
                                if (read < 0) break
                                out.write(buffer, 0, read)
                                done += read
                                val now = SystemClock.elapsedRealtime()
                                if (startedAt == 0L) startedAt = now
                                // Throttled progress, like morphe's downloader.
                                if (now - lastEmit >= 200) {
                                    lastEmit = now
                                    val speed = if (now > startedAt) done * 1000 / (now - startedAt) else null
                                    updateItem(job.id) {
                                        it.copy(
                                            status = Status.DOWNLOADING,
                                            progress = total?.let { t -> done.toFloat() / t } ?: 0f,
                                            bytesDone = done,
                                            bytesTotal = total,
                                            speed = speed,
                                        )
                                    }
                                }
                            }
                            out.flush()
                        }
                    }
                    updateItem(job.id) {
                        it.copy(bytesDone = done, bytesTotal = total ?: done)
                    }
                }
            }

            updateItem(job.id) { it.copy(status = Status.PUBLISHING) }
            val uri: Uri = MediaStorePublisher.publish(
                context, staged, job.filename, job.mime, job.kind,
            )
            updateItem(job.id) {
                it.copy(status = Status.DONE, progress = 1f, contentUri = uri.toString())
            }
            if (job.fromShare) {
                SharingShortcuts.reportUsage(context)
            }
            Notifications.downloadComplete(context, job.notifId, job.title, uri, job.mime)
        } catch (t: Throwable) {
            staged.delete()
            updateItem(job.id) {
                it.copy(
                    status = Status.FAILED,
                    error = t.message ?: t.javaClass.simpleName,
                )
            }
        }
    }

    private fun updateItem(id: Long, transform: (DownloadItem) -> DownloadItem) {
        _items.update { list -> list.map { if (it.id == id) transform(it) else it } }
    }

    private fun sanitizeFilename(name: String): String =
        name.replace(Regex("[\\\\/:*?\"<>|\\x00-\\x1f]"), "_")
            .trim()
            .take(120)
            .ifEmpty { "download" }

    private companion object {
        // Some CDNs reject default OkHttp UA; a plain mobile-browser UA is
        // accepted everywhere the M1 downloader touches.
        val DOWNLOAD_UA =
            "Mozilla/5.0 (Linux; Android 15) AppleWebKit/537.36 " +
                "(KHTML, like Gecko) Chrome/140.0.0.0 Mobile Safari/537.36"
    }
}
