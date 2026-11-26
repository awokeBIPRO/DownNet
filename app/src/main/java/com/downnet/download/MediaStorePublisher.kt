package com.downnet.download

import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.provider.MediaStore
import android.util.Log
import android.os.Environment
import java.io.File
import java.io.IOException

enum class MediaKind { VIDEO, AUDIO, IMAGE }

/**
 * Publishes a fully-staged file into MediaStore so the gallery (and every
 * other app) picks it up — scoped storage, zero permissions, own
 * contributions only.
 *
 * Staging then publishing (rather than streaming straight into the row):
 * a partial file in filesDir is cleanly Range-resumable and crash-safe; an
 * interrupted IS_PENDING row is swept instead of leaving a corrupt
 * "complete" entry in the gallery.
 *
 * Some OEM builds deny third-party writes into Music/ etc. through the
 * per-type collections — anything that fails there falls back to the
 * Downloads collection, which is writable on every device we've met and is
 * still scanned into the gallery.
 */
object MediaStorePublisher {

    private const val TAG = "MediaStorePublisher"

    fun publish(
        context: Context,
        staged: File,
        displayName: String,
        mime: String,
        kind: MediaKind,
    ): Uri {
        try {
            return tryStore(context, staged, displayName, mime, kind)
        } catch (t: Throwable) {
            Log.w(TAG, "Primary ${kind.name.lowercase()} location rejected; falling back to Downloads", t)
        }
        return runCatching { tryStore(context, staged, displayName, mime, null) }
            .getOrElse { t ->
                throw IOException("MediaStore rejected the write: ${t.message}", t)
            }
    }

    /** kind == null means the universal Downloads location. */
    private fun tryStore(
        context: Context,
        staged: File,
        displayName: String,
        mime: String,
        kind: MediaKind?,
    ): Uri {
        val resolver = context.contentResolver
        val (collection, relative) = when (kind) {
            MediaKind.VIDEO ->
                MediaStore.Video.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY) to
                    "${Environment.DIRECTORY_MOVIES}/DownNet"
            MediaKind.AUDIO ->
                MediaStore.Audio.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY) to
                    "${Environment.DIRECTORY_MUSIC}/DownNet"
            MediaKind.IMAGE ->
                MediaStore.Images.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY) to
                    "${Environment.DIRECTORY_PICTURES}/DownNet"
            null ->
                MediaStore.Downloads.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY) to
                    "${Environment.DIRECTORY_DOWNLOADS}/DownNet"
        }

        var uri: Uri? = null
        try {
            val pending = ContentValues().apply {
                put(MediaStore.MediaColumns.DISPLAY_NAME, displayName)
                put(MediaStore.MediaColumns.MIME_TYPE, mime)
                put(MediaStore.MediaColumns.RELATIVE_PATH, relative)
                put(MediaStore.MediaColumns.IS_PENDING, 1)
            }
            uri = resolver.insert(collection, pending)
                ?: error("MediaStore insert failed")

            resolver.openOutputStream(uri, "w")?.use { out ->
                staged.inputStream().use { input ->
                    input.copyTo(out, 64 * 1024)
                }
            } ?: error("Could not open MediaStore output stream")

            val done = ContentValues().apply { put(MediaStore.MediaColumns.IS_PENDING, 0) }
            resolver.update(uri, done, null, null)
            return uri
        } catch (t: Throwable) {
            // Never leave a half-written pending row behind.
            uri?.let { runCatching { resolver.delete(it, null, null) } }
            throw t
        } finally {
            staged.delete()
        }
    }

    fun mimeFor(ext: String): String = when (ext.lowercase()) {
        "mp4", "m4v" -> "video/mp4"
        "webm" -> "video/webm"
        "mkv" -> "video/x-matroska"
        "mov" -> "video/quicktime"
        "ts" -> "video/mp2t"
        "m4a" -> "audio/mp4"
        "mp3" -> "audio/mpeg"
        "opus" -> "audio/opus"
        "ogg", "oga" -> "audio/ogg"
        "wav" -> "audio/x-wav"
        "flac" -> "audio/flac"
        "jpg", "jpeg" -> "image/jpeg"
        "png" -> "image/png"
        "gif" -> "image/gif"
        "webp" -> "image/webp"
        else -> "application/octet-stream"
    }
}
