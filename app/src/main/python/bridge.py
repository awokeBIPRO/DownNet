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


def _build_opts(js_runtime=None, ffmpeg_dir=None):
    # Options are constructed directly (NOT via yt_dlp.parse_options, whose
    # return is a 4-tuple whose second element is an argparse Namespace —
    # taking it for the opts dict silently drops every flag we passed, which
    # is exactly how the JS runtime once went missing and YouTube throttled
    # to ~35 KB/s with no video formats).
    opts = {
        "skip_download": True,
        "quiet": True,
        "no_warnings": True,
        "noplaylist": True,
        "playlist_items": "1",
        "socket_timeout": 15,
    }
    if js_runtime:
        # Dict form straight out of yt-dlp's parse_options: REPLACES the
        # default {'deno': {}} rather than appending to it, so quickjs is the
        # only enabled runtime and deno's priority never matters.
        opts["js_runtimes"] = {"quickjs": {"path": js_runtime}}
    if ffmpeg_dir:
        opts["ffmpeg_location"] = ffmpeg_dir
    return opts


def extract(url, js_runtime=None, ffmpeg_dir=None):
    """Resolve a media URL to its stream variants (no download)."""
    import yt_dlp

    opts = _build_opts(js_runtime, ffmpeg_dir)
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
