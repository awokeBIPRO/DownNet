package com.downnet.download

import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.provider.MediaStore
import java.io.File
import android.os.Environment

enum class MediaKind { VIDEO, AUDIO, IMAGE }

/**
 * Publishes a fully-staged file into MediaStore so the gallery (and every
 * other app) picks it up — scoped storage, zero permissions, own
 * contributions only.
 *
 * Staging then publishing (rather than streaming straight into the row):
 * a partial file in filesDir is cleanly Range-resumable and crash-safe; an
 * interrupted IS_PENDING row is just swept at startup instead of leaving a
 * corrupt "complete" entry in the gallery.
 */
object MediaStorePublisher {

    fun publish(
        context: Context,
        staged: File,
        displayName: String,
        mime: String,
        kind: MediaKind,
    ): Uri {
        val resolver = context.contentResolver
        val relative = when (kind) {
            MediaKind.VIDEO -> "${Environment.DIRECTORY_MOVIES}/DownNet"
            MediaKind.AUDIO -> "${Environment.DIRECTORY_MUSIC}/DownNet"
            MediaKind.IMAGE -> "${Environment.DIRECTORY_PICTURES}/DownNet"
        }
        val pending = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, displayName)
            put(MediaStore.MediaColumns.MIME_TYPE, mime)
            put(MediaStore.MediaColumns.RELATIVE_PATH, relative)
            put(MediaStore.MediaColumns.IS_PENDING, 1)
        }
        val collection = MediaStore.Files.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
        val uri = resolver.insert(collection, pending)
            ?: error("MediaStore insert failed")

        try {
            resolver.openOutputStream(uri, "w")?.use { out ->
                staged.inputStream().use { input ->
                    input.copyTo(out, 64 * 1024)
                }
            } ?: error("Could not open MediaStore output stream")
            val done = ContentValues().apply { put(MediaStore.MediaColumns.IS_PENDING, 0) }
            resolver.update(uri, done, null, null)
        } catch (t: Throwable) {
            // Never leave a half-written pending row behind.
            runCatching { resolver.delete(uri, null, null) }
            throw t
        } finally {
            staged.delete()
        }
        return uri
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
