package com.downnet.download

import android.content.Context
import android.net.Uri
import android.os.SystemClock
import com.downnet.core.urlnorm.UrlNormalizer.Platform
import com.downnet.extract.MediaInfo
import com.downnet.extract.VariantPicker
import com.downnet.extract.VariantPicker.Choice
import com.downnet.extract.VariantPicker.Kind
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
 * M2a in-memory download queue: sequential worker with three job shapes —
 * single direct file, split video+audio pair (ffmpeg merge), and HLS
 * playlist (segment download + concat remux). M2b replaces the in-flight
 * list with the Room queue + dataSync foreground service, Range-resume and
 * the 403 re-extract self-heal; the public surface (enqueue + items) is
 * designed to survive that swap.
 */
class DownloadRepository(
    private val context: Context,
    sharedClient: OkHttpClient,
) {

    enum class Status { QUEUED, DOWNLOADING, MERGING, PUBLISHING, DONE, FAILED }

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

    private data class Part(val url: String, val headers: Map<String, String>?)

    private sealed interface Source {
        data class Direct(val parts: List<Part>) : Source
        data class Hls(val playlistUrl: String, val headers: Map<String, String>?) : Source
    }

    private class Job(
        val id: Long,
        val title: String,
        val source: Source,
        val filename: String,
        val mime: String,
        val kind: MediaKind,
        val fromShare: Boolean,
        val notifId: Int,
        val durationSec: Double?,   // for merge progress
        val mergeContainer: String?, // set for split-stream pairs
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
        choice: Choice,
        platform: Platform?,
        fromShare: Boolean,
    ): DownloadItem {
        val id = nextId.getAndIncrement()
        val kind: MediaKind
        val source: Source
        var mergeContainer: String? = null
        var ext: String

        when (choice.kind) {
            Kind.MERGED -> {
                val video = choice.formats[0]
                val audio = choice.formats[1]
                kind = MediaKind.VIDEO
                mergeContainer = Muxer.containerFor(video, audio)
                ext = mergeContainer
                source = Source.Direct(
                    listOf(
                        Part(video.url.orEmpty(), video.httpHeaders),
                        Part(audio.url.orEmpty(), audio.httpHeaders),
                    )
                )
            }
            Kind.HLS -> {
                kind = MediaKind.VIDEO
                ext = "mp4"
                source = Source.Hls(choice.formats[0].url.orEmpty(), choice.formats[0].httpHeaders)
            }
            Kind.AUDIO -> {
                kind = MediaKind.AUDIO
                ext = choice.formats[0].ext ?: "m4a"
                source = Source.Direct(listOf(Part(choice.formats[0].url.orEmpty(), choice.formats[0].httpHeaders)))
            }
            Kind.PHOTO -> {
                kind = MediaKind.IMAGE
                ext = choice.formats[0].ext ?: "jpg"
                source = Source.Direct(listOf(Part(choice.formats[0].url.orEmpty(), choice.formats[0].httpHeaders)))
            }
            Kind.MUXED -> {
                kind = MediaKind.VIDEO
                ext = choice.formats[0].ext ?: "mp4"
                source = Source.Direct(listOf(Part(choice.formats[0].url.orEmpty(), choice.formats[0].httpHeaders)))
            }
        }

        val title = sanitizeFilename(info.title ?: "download")
        val item = DownloadItem(
            id = id,
            title = title,
            detail = listOfNotNull(choice.label, ext.uppercase()).joinToString(" · "),
            platform = platform,
            status = Status.QUEUED,
        )
        _items.update { it + item }
        queue.trySend(
            Job(
                id = id,
                title = title,
                source = source,
                filename = "$title.$ext",
                mime = MediaStorePublisher.mimeFor(ext),
                kind = kind,
                fromShare = fromShare,
                notifId = (1000 + id).toInt(),
                durationSec = info.duration,
                mergeContainer = mergeContainer,
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
        try {
            when (val source = job.source) {
                is Source.Direct -> runDirect(job, source, partialDir)
                is Source.Hls -> runHls(job, source, partialDir)
            }
        } catch (t: Throwable) {
            // Staged files are cleaned inside the runners; only the state remains.
            updateItem(job.id) {
                it.copy(status = Status.FAILED, error = t.message ?: t.javaClass.simpleName)
            }
        }
    }

    private suspend fun runDirect(job: Job, source: Source.Direct, partialDir: File) {
        if (source.parts.size == 1) {
            val staged = File(partialDir, "job-${job.id}.part")
            downloadPart(job, source.parts[0], staged)
            updateItem(job.id) { it.copy(status = Status.PUBLISHING) }
            publishAndFinish(job, staged)
            return
        }

        // Split video+audio pair: fetch both, then merge.
        val videoFile = File(partialDir, "job-${job.id}.v")
        val audioFile = File(partialDir, "job-${job.id}.a")
        val merged = File(partialDir, "job-${job.id}.merged.${job.mergeContainer ?: "mp4"}")
        try {
            var doneTotal = 0L
            val totals = mutableListOf<Long?>()
            for ((index, part) in source.parts.withIndex()) {
                val target = if (index == 0) videoFile else audioFile
                val result = downloadPart(
                    job, part, target, partCount = 2, baseBytes = doneTotal,
                )
                doneTotal += result.first
                totals += result.second
            }
            val knownTotal = totals.filterNotNull().sum()
            updateItem(job.id) {
                it.copy(
                    bytesDone = doneTotal,
                    bytesTotal = if (knownTotal > 0) knownTotal else doneTotal,
                )
            }

            updateItem(job.id) { it.copy(status = Status.MERGING, progress = 0f) }
            withContext(Dispatchers.IO) {
                Muxer.merge(
                    context, videoFile, audioFile, merged,
                    job.mergeContainer ?: "mp4", job.durationSec,
                ) { progress ->
                    updateItem(job.id) { it.copy(progress = progress) }
                }
            }
            videoFile.delete()
            audioFile.delete()

            updateItem(job.id) { it.copy(status = Status.PUBLISHING) }
            publishAndFinish(job, merged)
        } catch (t: Throwable) {
            videoFile.delete()
            audioFile.delete()
            merged.delete()
            throw t
        }
    }

    private suspend fun runHls(job: Job, source: Source.Hls, partialDir: File) {
        val workDir = File(partialDir, "job-${job.id}").apply { mkdirs() }
        val output = File(partialDir, "job-${job.id}.mp4")
        try {
            // Master playlist → best variant.
            var playlistUrl = source.playlistUrl
            var text = fetchText(playlistUrl, source.headers)
            if (HlsParser.isMasterPlaylist(text)) {
                val best = HlsParser.parseMaster(text, playlistUrl)
                    .maxByOrNull { it.bandwidth }
                    ?: error("HLS master playlist had no variants")
                playlistUrl = best.url
                text = fetchText(playlistUrl, source.headers)
            }
            val playlist = HlsParser.parseMedia(text, playlistUrl)
            check(!playlist.encrypted) { "Encrypted HLS streams aren't supported yet" }
            check(!playlist.isLive) { "Live streams aren't supported yet" }
            check(playlist.segments.isNotEmpty()) { "HLS playlist had no segments" }

            updateItem(job.id) {
                it.copy(
                    status = Status.DOWNLOADING,
                    bytesTotal = null,
                )
            }

            // Segments, weighted by duration for progress.
            val totalDuration = playlist.totalDurationSec
            var doneDuration = 0.0
            val segmentFiles = mutableListOf<File>()
            playlist.segments.forEachIndexed { index, segment ->
                val file = File(workDir, "seg-%04d.ts".format(index))
                val (bytes, _) = downloadSegment(job, segment, file)
                segmentFiles += file
                doneDuration += segment.durationSec
                updateItem(job.id) {
                    it.copy(
                        progress = (doneDuration / totalDuration).toFloat(),
                        bytesDone = it.bytesDone + bytes,
                    )
                }
            }

            val listFile = File(workDir, "concat.txt")
            listFile.writeText(segmentFiles.joinToString("\n") { "file '${it.name}'" })

            updateItem(job.id) { it.copy(status = Status.MERGING, progress = 0f) }
            withContext(Dispatchers.IO) {
                Muxer.concat(context, listFile, output, playlist.totalDurationSec) { progress ->
                    updateItem(job.id) { it.copy(progress = progress) }
                }
            }

            updateItem(job.id) { it.copy(status = Status.PUBLISHING) }
            publishAndFinish(job, output)
        } catch (t: Throwable) {
            output.delete()
            workDir.deleteRecursively()
            throw t
        } finally {
            workDir.deleteRecursively()
        }
    }

    // --- transfers -----------------------------------------------------------

    /** @return (bytesDone, bytesTotal if known). */
    private suspend fun downloadPart(
        job: Job,
        part: Part,
        target: File,
        partCount: Int = 1,
        baseBytes: Long = 0,
    ): Pair<Long, Long?> = withContext(Dispatchers.IO) {
        val request = requestFor(part)
        client.newCall(request).execute().use { response ->
            check(response.isSuccessful) { "HTTP ${response.code}" }
            val body = response.body ?: error("Empty response body")
            val total = body.contentLength().takeIf { it > 0 }
            if (partCount == 1) {
                updateItem(job.id) { it.copy(status = Status.DOWNLOADING, progress = 0f) }
            }
            var lastEmit = 0L
            var startedAt = 0L
            var done = 0L
            body.byteStream().use { input ->
                FileOutputStream(target).use { out ->
                    val buffer = ByteArray(64 * 1024)
                    while (true) {
                        val read = input.read(buffer)
                        if (read < 0) break
                        out.write(buffer, 0, read)
                        done += read
                        val now = SystemClock.elapsedRealtime()
                        if (startedAt == 0L) startedAt = now
                        if (now - lastEmit >= 200) {
                            lastEmit = now
                            val speed = if (now > startedAt) done * 1000 / (now - startedAt) else null
                            updateItem(job.id) {
                                it.copy(
                                    status = Status.DOWNLOADING,
                                    progress = if (partCount == 1 && total != null) {
                                        done.toFloat() / total
                                    } else {
                                        it.progress
                                    },
                                    bytesDone = baseBytes + done,
                                    bytesTotal = total,
                                    speed = speed,
                                )
                            }
                        }
                    }
                    out.flush()
                }
            }
            done to total
        }
    }

    private suspend fun downloadSegment(
        job: Job,
        segment: HlsParser.Segment,
        target: File,
    ): Pair<Long, Long?> = withContext(Dispatchers.IO) {
        val request = Request.Builder()
            .url(segment.url)
            .header("User-Agent", DOWNLOAD_UA)
            .build()
        client.newCall(request).execute().use { response ->
            check(response.isSuccessful) { "Segment HTTP ${response.code}" }
            val body = response.body ?: error("Empty segment body")
            var done = 0L
            body.byteStream().use { input ->
                FileOutputStream(target).use { out ->
                    val buffer = ByteArray(64 * 1024)
                    while (true) {
                        val read = input.read(buffer)
                        if (read < 0) break
                        out.write(buffer, 0, read)
                        done += read
                    }
                    out.flush()
                }
            }
            done to body.contentLength().takeIf { it > 0 }
        }
    }

    private fun fetchText(url: String, headers: Map<String, String>?): String =
        client.newCall(requestFor(Part(url, headers))).execute().use { response ->
            check(response.isSuccessful) { "Playlist HTTP ${response.code}" }
            response.body?.string() ?: error("Empty playlist body")
        }

    private fun requestFor(part: Part): Request =
        Request.Builder()
            .url(part.url)
            .header("User-Agent", DOWNLOAD_UA)
            .apply { part.headers?.forEach { (k, v) -> header(k, v) } }
            .build()

    private suspend fun publishAndFinish(job: Job, staged: File) {
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
        // accepted everywhere the M1 downloader touched.
        val DOWNLOAD_UA =
            "Mozilla/5.0 (Linux; Android 15) AppleWebKit/537.36 " +
                "(KHTML, like Gecko) Chrome/140.0.0.0 Mobile Safari/537.36"
    }
}
