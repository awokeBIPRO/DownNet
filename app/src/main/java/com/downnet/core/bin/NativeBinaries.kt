package com.downnet.core.bin

import android.content.Context
import java.nio.file.Files
import java.io.File

/**
 * The three CLI binaries (libffmpeg.so, libffprobe.so, libqjs.so) live in
 * nativeLibraryDir — the only place Android lets an app exec() from since
 * API 29 (W^X). ffmpeg and ffprobe are repackaged as .so purely so the
 * installer extracts them; their real names are restored at runtime through
 * a symlink farm, because yt-dlp's --ffmpeg-location expects binaries named
 * exactly `ffmpeg` / `ffprobe` in one directory.
 *
 * Symlinks (not copies): copying into app data would make them un-executable.
 */
object NativeBinaries {

    private const val FFMPEG = "libffmpeg.so"
    private const val FFPROBE = "libffprobe.so"
    private const val QJS = "libqjs.so"

    @Volatile
    private var binDir: File? = null

    /** filesDir/bin with ffmpeg + ffprobe symlinks, created idempotently. */
    fun ensureBin(context: Context): File {
        binDir?.let { return it }
        synchronized(this) {
            binDir?.let { return it }
            val dir = File(context.filesDir, "bin").apply { mkdirs() }
            val libDir = File(context.applicationInfo.nativeLibraryDir)
            link(File(libDir, FFMPEG), File(dir, "ffmpeg"))
            link(File(libDir, FFPROBE), File(dir, "ffprobe"))
            return dir
        }
    }

    /** QuickJS keeps its .so name; yt-dlp takes the exact path via --js-runtimes. */
    fun qjs(context: Context): File =
        File(context.applicationInfo.nativeLibraryDir, QJS)

    /** The ffmpeg/ffprobe binaries, by their packaged .so names. */
    fun ffmpeg(context: Context): File =
        File(context.applicationInfo.nativeLibraryDir, FFMPEG)

    fun ffprobe(context: Context): File =
        File(context.applicationInfo.nativeLibraryDir, FFPROBE)

    /**
     * Names of binaries that are missing or not executable after install.
     * Checked at startup — a silent packaging regression here would otherwise
     * surface as a baffling extraction failure much later.
     */
    fun selfCheck(context: Context): List<String> {
        val libDir = File(context.applicationInfo.nativeLibraryDir)
        return listOf(FFMPEG, FFPROBE, QJS)
            .filterNot { File(libDir, it).canExecute() }
    }

    private fun link(target: File, linkPath: File) {
        val current = runCatching {
            linkPath.exists() && Files.readSymbolicLink(linkPath.toPath()) == target.toPath()
        }.getOrDefault(false)
        if (current) return
        runCatching {
            if (linkPath.exists()) linkPath.delete()
            Files.createSymbolicLink(linkPath.toPath(), target.toPath())
        }
        // A failed symlink falls back to passing the .so path directly; the
        // bridge handles that (less ideal for ffprobe discovery, still works).
    }
}
