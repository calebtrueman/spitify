#!/usr/bin/env bash
# Rebuilds the FFmpeg static libraries bundled in ffmpeg/src/main/jni/ffmpeg (LGPL build).
# Uses Media3's own build script at the same version as the app (1.11.1) and FFmpeg 6.0.
#   scripts/build-ffmpeg.sh [path/to/ndk]
set -euo pipefail
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
NDK="${1:-${ANDROID_NDK_HOME:-/opt/homebrew/share/android-commandlinetools/ndk/26.1.10909125}}"
HOST="$(uname -s | tr '[:upper:]' '[:lower:]')-x86_64"   # NDK prebuilt dir name (darwin-x86_64 works on Apple silicon too)
WORK="$(mktemp -d)"
DECODERS=(vorbis opus flac alac pcm_mulaw pcm_alaw mp3 amrnb amrwb aac ac3 eac3 dca mlp truehd)

git clone -q --depth 1 --branch 1.11.1 https://github.com/androidx/media.git "$WORK/media"
MODULE="$WORK/media/libraries/decoder_ffmpeg/src/main"
git clone -q --depth 1 --branch release/6.0 https://github.com/FFmpeg/FFmpeg.git "$MODULE/jni/ffmpeg"
(cd "$MODULE/jni" && ./build_ffmpeg.sh "$MODULE" "$NDK" "$HOST" 21 "${DECODERS[@]}")

DEST="$ROOT/ffmpeg/src/main/jni/ffmpeg"
rm -rf "$DEST"
rsync -am --include='*/' --include='*.h' --include='*.a' --exclude='*' "$MODULE/jni/ffmpeg/" "$DEST/"
rm -rf "$DEST/android-libs/x86"   # not shipped
rm -rf "$WORK"
echo "FFmpeg libraries updated in $DEST"
