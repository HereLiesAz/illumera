#!/usr/bin/env bash
set -euo pipefail

STREMIO_DIR="${1:?Usage: build-stremio-media.sh <stremio-media-dir> <output-dir>}"
OUT_DIR="${2:?Usage: build-stremio-media.sh <stremio-media-dir> <output-dir>}"

STREMIO_DIR="$(cd "$STREMIO_DIR" && pwd)"
mkdir -p "$OUT_DIR"
OUT_DIR="$(cd "$OUT_DIR" && pwd)"

ANDROID_SDK="${ANDROID_SDK_ROOT:-${ANDROID_HOME:-}}"
if [[ -z "$ANDROID_SDK" ]]; then
    echo "ANDROID_SDK_ROOT/ANDROID_HOME is not set" >&2
    exit 1
fi

SDKMANAGER="$ANDROID_SDK/cmdline-tools/latest/bin/sdkmanager"
if [[ ! -x "$SDKMANAGER" ]]; then
    SDKMANAGER="$(command -v sdkmanager || true)"
fi
if [[ -z "$SDKMANAGER" || ! -x "$SDKMANAGER" ]]; then
    echo "sdkmanager not found" >&2
    exit 1
fi

# Stremio/media at STREMIO_MEDIA_REF sets no ndkVersion, so AGP's default applies:
# AGP 9.0.1 (media3 1.11.1-stremio01) defaults to NDK 28.2.13676358. Keep the helper
# scripts and Gradle on that exact version; a different ndk.dir makes AGP fail with
# CXX1104 before any module builds. Re-check this whenever the fork's AGP changes.
NDK_VERSION="28.2.13676358"
CMAKE_VERSION="3.22.1"

echo "Installing native Android toolchain..."
yes | "$SDKMANAGER" --licenses >/dev/null 2>&1 || true
"$SDKMANAGER" "ndk;$NDK_VERSION" "cmake;$CMAKE_VERSION"

NDK_PATH="$ANDROID_SDK/ndk/$NDK_VERSION"
if [[ ! -d "$NDK_PATH" ]]; then
    echo "NDK $NDK_VERSION was not installed at $NDK_PATH" >&2
    exit 1
fi

sudo apt-get update
sudo apt-get install -y ninja-build nasm python3-pip
python3 -m pip install --user --upgrade "meson>=1.3,<2"
export PATH="$HOME/.local/bin:$PATH"

if ! command -v bazelisk >/dev/null 2>&1; then
    GOBIN="$HOME/.local/bin" go install github.com/bazelbuild/bazelisk@v1.26.0
fi

export ANDROID_NDK_HOME="$NDK_PATH"
export ANDROID_NDK_ROOT="$NDK_PATH"
printf 'sdk.dir=%s\nndk.dir=%s\n' "$ANDROID_SDK" "$NDK_PATH" > "$STREMIO_DIR/local.properties"

# Build the native extension prerequisites expected by Stremio's Media3 fork.
AV1_MAIN="$STREMIO_DIR/libraries/decoder_av1/src/main"
AV1_JNI="$AV1_MAIN/jni"
rm -rf "$AV1_JNI/cpu_features" "$AV1_JNI/dav1d"
git clone --depth 1 --branch v0.11.0 https://github.com/google/cpu_features.git "$AV1_JNI/cpu_features"
git clone --depth 1 --branch 1.5.4 https://github.com/videolan/dav1d.git "$AV1_JNI/dav1d"
chmod +x "$AV1_JNI/build_dav1d.sh"
"$AV1_JNI/build_dav1d.sh" "$AV1_MAIN" "$NDK_PATH" linux-x86_64

FFMPEG_MAIN="$STREMIO_DIR/libraries/decoder_ffmpeg/src/main"
FFMPEG_JNI="$FFMPEG_MAIN/jni"
rm -rf "$FFMPEG_JNI/ffmpeg"
git clone --depth 1 --branch n6.0.1 https://github.com/FFmpeg/FFmpeg.git "$FFMPEG_JNI/ffmpeg"
chmod +x "$FFMPEG_JNI/build_ffmpeg.sh"
"$FFMPEG_JNI/build_ffmpeg.sh" \
    "$FFMPEG_MAIN" \
    "$NDK_PATH" \
    linux-x86_64 \
    26 \
    vorbis opus flac alac mp3 aac ac3 eac3 truehd mlp dca

IAMF_MAIN="$STREMIO_DIR/libraries/decoder_iamf/src/main"
IAMF_JNI="$IAMF_MAIN/jni"
rm -rf "$IAMF_JNI/iamf_tools"
mkdir -p "$IAMF_JNI/iamf_tools"
git -C "$IAMF_JNI/iamf_tools" init
git -C "$IAMF_JNI/iamf_tools" remote add origin https://github.com/AOMediaCodec/iamf-tools.git
git -C "$IAMF_JNI/iamf_tools" fetch --depth=1 origin e552e7d6d8b55530d201fd2764ca7e12d2c66cd6
git -C "$IAMF_JNI/iamf_tools" checkout --detach FETCH_HEAD
chmod +x "$IAMF_JNI/build_iamf_tools.sh"
"$IAMF_JNI/build_iamf_tools.sh" "$IAMF_MAIN" "$NDK_PATH"

MPEGH_JNI="$STREMIO_DIR/libraries/decoder_mpegh/src/main/jni"
rm -rf "$MPEGH_JNI/libmpegh"
git clone --depth 1 --branch r3.0.2 https://github.com/Fraunhofer-IIS/mpeghdec.git "$MPEGH_JNI/libmpegh"

chmod +x "$STREMIO_DIR/gradlew"
(
    cd "$STREMIO_DIR"
    ./gradlew \
        :lib-exoplayer:assembleRelease \
        :lib-decoder-av1:assembleRelease \
        :lib-decoder-ffmpeg:assembleRelease \
        :lib-decoder-iamf:assembleRelease \
        :lib-decoder-mpegh:assembleRelease \
        --no-daemon
)

copy_release_aar() {
    local module_path="$1"
    local output_name="$2"
    local aar=""
    # Stremio/media's root gradle.properties sets `buildDir=buildout`, which
    # Gradle applies as a project property to every subproject, relocating
    # each module's output directory from the default <module>/build to
    # <module>/buildout. Check both so this keeps working if that ever changes.
    # Each candidate dir is checked with `-d` first: under `set -e -o
    # pipefail`, calling `find` on a nonexistent path makes the whole
    # pipeline (and thus this script) exit immediately, even when the
    # *other* candidate path exists and contains the AAR.
    local candidate
    for candidate in \
        "$STREMIO_DIR/$module_path/buildout/outputs/aar" \
        "$STREMIO_DIR/$module_path/build/outputs/aar"; do
        if [[ -d "$candidate" ]]; then
            aar="$(find "$candidate" -maxdepth 1 -type f -name '*-release.aar' | head -1)"
            [[ -n "$aar" ]] && break
        fi
    done
    if [[ -z "$aar" || ! -s "$aar" ]]; then
        echo "No release AAR produced for $module_path" >&2
        exit 1
    fi
    cp "$aar" "$OUT_DIR/$output_name"
}

rm -f "$OUT_DIR"/*.aar
copy_release_aar "libraries/exoplayer" "lib-exoplayer-release.aar"
copy_release_aar "libraries/decoder_av1" "lib-decoder-av1-release.aar"
copy_release_aar "libraries/decoder_ffmpeg" "lib-decoder-ffmpeg-release.aar"
copy_release_aar "libraries/decoder_iamf" "lib-decoder-iamf-release.aar"
copy_release_aar "libraries/decoder_mpegh" "lib-decoder-mpegh-release.aar"

for aar in \
    lib-exoplayer-release.aar \
    lib-decoder-av1-release.aar \
    lib-decoder-ffmpeg-release.aar \
    lib-decoder-iamf-release.aar \
    lib-decoder-mpegh-release.aar; do
    test -s "$OUT_DIR/$aar"
done

sha256sum "$OUT_DIR"/*.aar
