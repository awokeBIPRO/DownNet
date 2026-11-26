# DownNet

Personal, sideloaded Android downloader: share or paste a link from any app
(YouTube, X, Instagram, ~1000 more via embedded yt-dlp) and the media lands in
your gallery. No Play Store — it updates itself straight from this repo's
GitHub Releases.

## How it's built

- **Kotlin + Jetpack Compose** (Material 3, dynamic color), minSdk 29.
- **Extraction**: embedded **yt-dlp** (via Chaquopy/Python 3.12) for YouTube
  and unknown sites, with fast Kotlin-native extractors for X/Instagram
  planned on top. yt-dlp only *extracts* — all downloading happens in one
  Kotlin/OkHttp pipeline.
- **Native binaries** (`ffmpeg`, `ffprobe`, `qjs`) are cross-built by
  `native/build-binaries.sh` and shipped as `jniLibs` `.so` files, the only
  location Android lets an app `exec()` from.
- **All builds happen in CI** (GitHub Actions). Push to `main` → debug APK
  artifact; tag → signed release + `app-release.json`, which the app's
  in-app updater consumes.

## Building

Never built locally. CI is the build oracle:

- Debug APK: any push to `main` (or a PR) produces `downnet-debug-apk`.
- Release: `git tag v<version> && git push --tags` (release workflow lands
  with the self-update milestone).

`keystore.properties` at the repo root (gitignored) unlocks release signing;
without it, release builds come out unsigned instead of failing.
