"""DownNet's yt-dlp bridge.

Kotlin calls ``extract(url, ...)`` and gets a JSON string back. yt-dlp is used
ONLY for extraction (URL -> stream variants); downloading happens in Kotlin so
every platform flows through one queue, one progress pipeline, one resume
path.

Every function returns JSON — success and failure share one contract, so the
Kotlin side never has to catch Python exceptions:

    {"ok": true,  "info": {...}}      extract()
    {"ok": false, "error": "..."}

Runtime wiring:
  js_runtime  path to the bundled QuickJS binary (``libqjs.so`` in
              nativeLibraryDir). yt-dlp needs an external JS runtime for
              YouTube's challenge code; QuickJS-NG >= 0.12 is the only
              realistically bundleable one.
  ffmpeg_dir  filesDir/bin, where NativeBinaries laid ``ffmpeg`` / ``ffprobe``
              symlinks for --ffmpeg-location.
"""

import json
import sys


def extract(url, js_runtime=None, ffmpeg_dir=None):
    """Resolve a media URL to its stream variants (no download)."""
    import yt_dlp

    argv = [
        "--skip-download",
        "--no-playlist",
        "--playlist-items", "1",
        "--ignore-config",
        "--no-warnings",
        "--socket-timeout", "15",
    ]
    if js_runtime:
        # CLI flag form: runtime-name:path-to-binary. Passing the CLI flags
        # through yt_dlp.parse_options lets yt-dlp itself translate them into
        # whatever internal option shape this version expects.
        argv += ["--js-runtimes", "quickjs:" + js_runtime]
    if ffmpeg_dir:
        argv += ["--ffmpeg-location", ffmpeg_dir]
    argv += ["--", url]

    opts = _opts_from_argv(argv, yt_dlp)
    try:
        with yt_dlp.YoutubeDL(opts) as ydl:
            info = ydl.extract_info(url, download=False)
        return json.dumps({"ok": True, "info": _slim(info)})
    except Exception as exc:  # yt_dlp.utils.DownloadError and friends
        return json.dumps({"ok": False, "error": str(exc)})


def selfcheck(js_runtime=None):
    """Diagnostics for the Settings screen / startup smoke test."""
    import yt_dlp

    out = {
        "ok": True,
        "python": sys.version.split()[0],
        "yt_dlp": yt_dlp.version.__version__,
    }
    try:
        import yt_dlp_ejs
        out["yt_dlp_ejs"] = getattr(yt_dlp_ejs, "__version__", "present")
    except ImportError:
        out["yt_dlp_ejs"] = "absent"

    if js_runtime:
        try:
            import subprocess
            proc = subprocess.run(
                [js_runtime, "--version"], capture_output=True, timeout=20
            )
            out["qjs"] = proc.stdout.decode(errors="replace").strip() or "unknown"
        except Exception as exc:
            out["qjs"] = "failed: %s" % exc
    return json.dumps(out)


# --- internals -----------------------------------------------------------

def _opts_from_argv(argv, yt_dlp):
    """
    Preferred path: let yt-dlp's own CLI parser translate the flags (correct
    option keys across versions). Fallback: a hand-rolled minimal dict for the
    builds where the parse_options return shape differs.
    """
    try:
        parsed = yt_dlp.parse_options(argv)
        opts = parsed[1]
        if isinstance(opts, dict):
            return opts
    except Exception:
        pass
    opts = {
        "skip_download": True,
        "noplaylist": True,
        "playlist_items": "1",
        "ignoreconfig": True,
        "socket_timeout": 15,
    }
    if "--ffmpeg-location" in argv:
        opts["ffmpeg_location"] = argv[argv.index("--ffmpeg-location") + 1]
    return opts


def _slim(info):
    """Keep only what the Kotlin quality-picker and downloader need."""
    if info is None:
        return None
    def pick(fmt):
        keys = (
            "format_id", "format_note", "ext", "protocol", "url",
            "vcodec", "acodec", "width", "height", "fps", "tbr", "abr",
            "filesize", "filesize_approx", "language", "http_headers",
        )
        return {k: fmt.get(k) for k in keys if fmt.get(k) is not None}
    out = {}
    for key in (
        "id", "title", "description", "uploader", "channel", "duration",
        "thumbnail", "webpage_url", "extractor_key", "ext", "live_status",
    ):
        if info.get(key) is not None:
            out[key] = info[key]
    formats = info.get("formats")
    if formats:
        out["formats"] = [pick(f) for f in formats if f.get("url")]
    # Single-format results (some extractors) or image posts.
    if not out.get("formats") and info.get("url"):
        out["formats"] = [pick(info)]
    if info.get("entries"):
        out["entries"] = [
            e.get("webpage_url") for e in info["entries"] if e.get("webpage_url")
        ]
    return out
