#!/usr/bin/env bash
# Cross-builds the three CLI binaries DownNet exec()s on-device:
#
#   libffmpeg.so / libffprobe.so   FFmpeg CLI — mux/remux only (yt-dlp's
#                                  video+audio merge, X HLS remux). Built with
#                                  NO network support: Kotlin fetches every
#                                  byte; ffmpeg only ever touches local files.
#   libqjs.so                      QuickJS-NG — yt-dlp's JS runtime for
#                                  YouTube challenges (--js-runtimes).
#
# Why packaged as .so: the installer extracts them into nativeLibraryDir, the
# only location an app may exec() from (app-data dirs are W^X-blocked since
# API 29). At runtime NativeBinaries lays a filesDir/bin symlink farm with the
# ffmpeg/ffprobe names yt-dlp expects.
#
# 16 KB page alignment via explicit linker flags (NDK r27; r28 does this by
# default). Upstream FFmpeg, LGPL config — no GPL components enabled.
#
# Recipe adapted from BitChord's native/ffmpeg/build.sh. Runs on CI
# (ubuntu-latest); expects an Android NDK at $ANDROID_NDK or the pinned
# default. Never run from inside the checked-out tree of an untrusted source.
set -euo pipefail

FFMPEG_URL=https://github.com/FFmpeg/FFmpeg.git
FFMPEG_TAG=n8.1.3
QJS_URL=https://github.com/quickjs-ng/quickjs
QJS_TAG=v0.18.0
API=29

HERE="$(cd "$(dirname "$0")" && pwd)"
REPO="$(cd "$HERE/.." && pwd)"
WORK="${WORK:-$REPO/build/native}"
NDK="${ANDROID_NDK:-${ANDROID_HOME:-}/ndk/27.0.12077973}"
NDK="$(cd "$NDK" && pwd)"
case "$(uname -s)" in
    MINGW*|MSYS*|CYGWIN*) HOST=windows-x86_64; EXE=.exe ;;
    Darwin)               HOST=darwin-x86_64;  EXE= ;;
    *)                    HOST=linux-x86_64;   EXE= ;;
esac
TC="$NDK/toolchains/llvm/prebuilt/$HOST/bin"
JOBS="${JOBS:-$(nproc 2>/dev/null || echo 8)}"

# Remux/mux feature set. Parsers matter for -c copy (no decoders needed);
# aac_adtstoasc is what turns X's MPEG-TS segments into MP4.
FFMPEG_DEMUXERS="mov,matroska,mpegts,mpegtsraw,concat,aac,flac,wav,adts,hls,mp3,ogg,webm_dash_manifest"
FFMPEG_MUXERS="mp4,matroska,adts,ipod"
FFMPEG_PARSERS="h264,hevc,av1,vp8,vp9,mpeg4video,mpegvideo,aac,opus,vorbis,mpegaudio,flac,ac3"
FFMPEG_BSF="aac_adtstoasc,extract_extradata,h264_mp4toannexb,vp9_superframe,vp9_raw_reorder"

fetch() {  # fetch <url> <tag> <dest>
    local url=$1 tag=$2 dest=$3
    if [ ! -d "$dest/.git" ]; then
        mkdir -p "$WORK"
        git -c core.autocrlf=false clone "$url" "$dest"
    fi
    git -C "$dest" -c core.autocrlf=false fetch --depth 1 origin "$tag" 2>/dev/null || true
    git -C "$dest" -c advice.detachedHead=false checkout -q "$tag"
}

build_ffmpeg_abi() {
    local abi=$1 triple=$2 arch=$3
    local src="$WORK/ffmpeg"
    echo "==> ffmpeg $abi"
    fetch "$FFMPEG_URL" "$FFMPEG_TAG" "$src"
    cd "$src"
    # In-tree on purpose: out-of-tree builds bake absolute source paths into
    # the .d files. One tree, cleaned between ABIs.
    [ -f ffbuild/config.mak ] && make -s distclean >/dev/null 2>&1 || true
    ./configure \
        --enable-cross-compile --target-os=android --arch="$arch" \
        --host-cc="${HOST_CC:-cc}" \
        --cc="$TC/clang$EXE" --cxx="$TC/clang++$EXE" \
        --ar="$TC/llvm-ar$EXE" --nm="$TC/llvm-nm$EXE" \
        --ranlib="$TC/llvm-ranlib$EXE" --strip="$TC/llvm-strip$EXE" \
        --extra-cflags="--target=$triple$API -fPIC -O2 $CFLAGS_ABI" \
        --extra-ldflags="--target=$triple$API -Wl,-z,max-page-size=16384" \
        --enable-static --disable-shared --enable-pic \
        --disable-autodetect --disable-doc --disable-avdevice \
        --disable-network --disable-iconv --disable-x86asm \
        --disable-debug --enable-small \
        --disable-everything \
        --enable-protocol=file \
        --enable-demuxer="$FFMPEG_DEMUXERS" \
        --enable-muxer="$FFMPEG_MUXERS" \
        --enable-parser="$FFMPEG_PARSERS" \
        --enable-bsf="$FFMPEG_BSF" \
        --enable-filter="null,anull,aformat,aresample,scale" \
        ${EXTRA_CONFIGURE:-}
    make -j"$JOBS" ffmpeg ffprobe
    local out="$REPO/app/src/main/jniLibs/$abi"
    mkdir -p "$out"
    cp ffmpeg "$out/libffmpeg.so"
    cp ffprobe "$out/libffprobe.so"
    "$TC/llvm-strip$EXE" --strip-unneeded "$out/libffmpeg.so" "$out/libffprobe.so"
    echo "    -> $out/libffmpeg.so ($(wc -c < "$out/libffmpeg.so") bytes)"
}

build_qjs_abi() {
    local abi=$1
    local src="$WORK/quickjs"
    echo "==> quickjs $abi"
    fetch "$QJS_URL" "$QJS_TAG" "$src"
    cd "$src"
    rm -rf "build/$abi"
    # quickjs-ng builds through CMake — its Makefile has no qjs target (make
    # falls back to a builtin link rule with no libquickjs, which fails).
    # The NDK toolchain file wires up the cross compiler.
    cmake -B "build/$abi" -S . \
        -DCMAKE_TOOLCHAIN_FILE="$NDK/build/cmake/android.toolchain.cmake" \
        -DANDROID_ABI="$abi" \
        -DANDROID_PLATFORM="android-$API" \
        -DCMAKE_BUILD_TYPE=MinSizeRel \
        -DCMAKE_EXE_LINKER_FLAGS="-Wl,-z,max-page-size=16384" \
        ${EXTRA_CMAKE:-}
    if ! cmake --build "build/$abi" --target qjs -j"$JOBS"; then
        # Older/newer trees may not expose a qjs target; build everything.
        cmake --build "build/$abi" -j"$JOBS"
    fi
    local qjs_bin
    qjs_bin="$(find "build/$abi" -type f -name qjs | head -1)"
    if [ -z "$qjs_bin" ]; then
        echo "qjs binary not found after build" >&2
        exit 1
    fi
    local out="$REPO/app/src/main/jniLibs/$abi"
    mkdir -p "$out"
    cp "$qjs_bin" "$out/libqjs.so"
    "$TC/llvm-strip$EXE" --strip-unneeded "$out/libqjs.so"
    echo "    -> $out/libqjs.so ($(wc -c < "$out/libqjs.so") bytes)"
}

ABIS="${ABIS:-arm64-v8a x86_64}"
for abi in $ABIS; do
    case $abi in
        arm64-v8a)
            CFLAGS_ABI="" build_ffmpeg_abi arm64-v8a aarch64-linux-android arm64
            build_qjs_abi arm64-v8a
            ;;
        x86_64)
            CFLAGS_ABI="" build_ffmpeg_abi x86_64 x86_64-linux-android x86_64
            build_qjs_abi x86_64
            ;;
        *) echo "unknown ABI $abi" >&2; exit 1 ;;
    esac
done
echo "All binaries built."
