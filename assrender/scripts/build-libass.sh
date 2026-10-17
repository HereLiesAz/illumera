#!/bin/bash
#
# Cross-compile libass and its dependencies for Android.
# Dependencies: FreeType, FriBidi, HarfBuzz. libass is built without a system
# font provider; AssHandler adds system fonts at runtime (see ass_direct.c).
#
# Prerequisites:
#   - Android NDK (set ANDROID_NDK_HOME)
#
# Usage: ./build-libass.sh [arm64|arm]
#
set -e

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
PROJECT_DIR="$(dirname "$SCRIPT_DIR")"
BUILD_DIR="$PROJECT_DIR/build/libass"
API_LEVEL=21

# Dependency versions
FREETYPE_VERSION="2.13.3"
FRIBIDI_VERSION="1.0.16"
HARFBUZZ_VERSION="10.1.0"
LIBASS_VERSION="0.17.3"

# SHA-256 of each release tarball. Downloads are rejected on mismatch. When you
# bump a version, download the new tarball from the official release page, check
# it against the project's published signature/checksum, then update the hash.
FREETYPE_SHA256="5c3a8e78f7b24c20b25b54ee575d6daa40007a5f4eea2845861c3409b3021747"
FRIBIDI_SHA256="1b1cde5b235d40479e91be2f0e88a309e3214c8ab470ec8a2744d82a5a9ea05c"
HARFBUZZ_SHA256="6ce3520f2d089a33cef0fc48321334b8e0b72141f6a763719aaaecd2779ecb82"
LIBASS_SHA256="da7c348deb6fa6c24507afab2dee7545ba5dd5bbf90a137bfe9e738f7df68537"

# --- Validate NDK ---
# Pick the newest installed NDK when ANDROID_NDK_HOME is not set. (A glob inside
# `[ -d ... ]` breaks as soon as more than one NDK is installed.)
latest_ndk() {
    local root
    for root in "$ANDROID_SDK_ROOT" "$ANDROID_HOME" "$HOME/Android/Sdk" "$LOCALAPPDATA/Android/Sdk"; do
        if [ -n "$root" ] && [ -d "$root/ndk" ]; then
            local found
            found=$(ls -d "$root"/ndk/*/ 2>/dev/null | sed 's|/$||' | sort -V | tail -1)
            if [ -n "$found" ]; then
                echo "$found"
                return 0
            fi
        fi
    done
    return 1
}

if [ -z "$ANDROID_NDK_HOME" ]; then
    ANDROID_NDK_HOME=$(latest_ndk) || {
        echo "ERROR: ANDROID_NDK_HOME not set and NDK not found"
        exit 1
    }
fi
echo "Using NDK: $ANDROID_NDK_HOME"
TOOLCHAIN="$ANDROID_NDK_HOME/toolchains/llvm/prebuilt/linux-x86_64"
if [ ! -d "$TOOLCHAIN" ]; then
    TOOLCHAIN="$ANDROID_NDK_HOME/toolchains/llvm/prebuilt/windows-x86_64"
fi
if [ ! -d "$TOOLCHAIN" ]; then
    TOOLCHAIN="$ANDROID_NDK_HOME/toolchains/llvm/prebuilt/darwin-x86_64"
fi

download_and_extract() {
    local url=$1
    local dest=$2
    local sha256=$3
    if [ ! -d "$dest" ]; then
        echo "Downloading $url..."
        mkdir -p "$(dirname "$dest")"
        local archive="$dest.archive"
        curl -fL "$url" -o "$archive"
        if ! echo "$sha256  $archive" | sha256sum -c - >/dev/null 2>&1; then
            echo "ERROR: SHA-256 mismatch for $url"
            echo "  expected: $sha256"
            echo "  actual:   $(sha256sum "$archive" | cut -d' ' -f1)"
            rm -f "$archive"
            exit 1
        fi
        mkdir -p "$dest"
        if echo "$url" | grep -q '\.tar\.xz'; then
            tar xJf "$archive" -C "$dest" --strip-components=1
        else
            tar xzf "$archive" -C "$dest" --strip-components=1
        fi
        rm "$archive"
    fi
}

build_arch() {
    local ARCH=$1
    local ANDROID_ABI
    local TARGET
    local HOST

    case $ARCH in
        arm64)
            ANDROID_ABI="arm64-v8a"
            TARGET="aarch64-linux-android"
            HOST="aarch64-linux-android"
            ;;
        arm)
            ANDROID_ABI="armeabi-v7a"
            TARGET="armv7a-linux-androideabi"
            HOST="arm-linux-androideabi"
            ;;
        *)
            echo "Unknown arch: $ARCH"
            exit 1
            ;;
    esac

    local PREFIX="$PROJECT_DIR/prebuilt/$ANDROID_ABI"
    mkdir -p "$PREFIX"

    export CC="${TOOLCHAIN}/bin/${TARGET}${API_LEVEL}-clang"
    # Android 15+ devices with 16 KB pages require 16 KB-aligned ELF segments (Play requirement).
    export LDFLAGS="-Wl,-z,max-page-size=16384"
    export CXX="${TOOLCHAIN}/bin/${TARGET}${API_LEVEL}-clang++"
    export AR="${TOOLCHAIN}/bin/llvm-ar"
    export RANLIB="${TOOLCHAIN}/bin/llvm-ranlib"
    export STRIP="${TOOLCHAIN}/bin/llvm-strip"
    export PKG_CONFIG_PATH="$PREFIX/lib/pkgconfig"
    export PATH="$TOOLCHAIN/bin:$PATH"

    echo "========================================="
    echo "Building libass stack for $ANDROID_ABI"
    echo "========================================="

    # --- FreeType ---
    echo "--- Building FreeType ---"
    local FREETYPE_SRC="$BUILD_DIR/freetype-src"
    download_and_extract \
        "https://download.savannah.gnu.org/releases/freetype/freetype-${FREETYPE_VERSION}.tar.gz" \
        "$FREETYPE_SRC" "$FREETYPE_SHA256"

    cd "$FREETYPE_SRC"
    make clean 2>/dev/null || true
    ./configure \
        --host=$HOST \
        --prefix="$PREFIX" \
        --enable-shared \
        --disable-static \
        --with-zlib=no \
        --with-bzip2=no \
        --with-png=no \
        --with-harfbuzz=no \
        --with-brotli=no \
        CFLAGS="-fPIC -Os"
    make -j$(nproc 2>/dev/null || echo 4)
    make install

    # --- FriBidi ---
    echo "--- Building FriBidi ---"
    local FRIBIDI_SRC="$BUILD_DIR/fribidi-src"
    download_and_extract \
        "https://github.com/fribidi/fribidi/releases/download/v${FRIBIDI_VERSION}/fribidi-${FRIBIDI_VERSION}.tar.xz" \
        "$FRIBIDI_SRC" "$FRIBIDI_SHA256"

    cd "$FRIBIDI_SRC"
    make clean 2>/dev/null || true
    ./configure \
        --host=$HOST \
        --prefix="$PREFIX" \
        --enable-shared \
        --disable-static \
        --disable-docs \
        CFLAGS="-fPIC -Os"
    make -j$(nproc 2>/dev/null || echo 4)
    make install

    # --- HarfBuzz ---
    echo "--- Building HarfBuzz ---"
    local HARFBUZZ_SRC="$BUILD_DIR/harfbuzz-src"
    download_and_extract \
        "https://github.com/harfbuzz/harfbuzz/releases/download/${HARFBUZZ_VERSION}/harfbuzz-${HARFBUZZ_VERSION}.tar.xz" \
        "$HARFBUZZ_SRC" "$HARFBUZZ_SHA256"

    cd "$HARFBUZZ_SRC"
    rm -rf build-$ARCH
    mkdir -p build-$ARCH && cd build-$ARCH
    cmake .. \
        -DCMAKE_TOOLCHAIN_FILE="$ANDROID_NDK_HOME/build/cmake/android.toolchain.cmake" \
        -DANDROID_SUPPORT_FLEXIBLE_PAGE_SIZES=ON \
        -DANDROID_ABI=$ANDROID_ABI \
        -DANDROID_PLATFORM=android-$API_LEVEL \
        -DCMAKE_INSTALL_PREFIX="$PREFIX" \
        -DCMAKE_PREFIX_PATH="$PREFIX" \
        -DCMAKE_FIND_ROOT_PATH="$PREFIX" \
        -DCMAKE_FIND_ROOT_PATH_MODE_INCLUDE=BOTH \
        -DCMAKE_FIND_ROOT_PATH_MODE_LIBRARY=BOTH \
        -DFREETYPE_INCLUDE_DIRS="$PREFIX/include/freetype2" \
        -DFREETYPE_LIBRARY="$PREFIX/lib/libfreetype.so" \
        -DBUILD_SHARED_LIBS=ON \
        -DHB_HAVE_FREETYPE=ON \
        -DHB_HAVE_GLIB=OFF \
        -DHB_HAVE_ICU=OFF \
        -DHB_HAVE_GOBJECT=OFF \
        -DHB_BUILD_UTILS=OFF \
        -DHB_BUILD_SUBSET=OFF
    make -j$(nproc 2>/dev/null || echo 4)
    make install

    # --- libass ---
    echo "--- Building libass ---"
    local LIBASS_SRC="$BUILD_DIR/libass-src"
    download_and_extract \
        "https://github.com/libass/libass/releases/download/${LIBASS_VERSION}/libass-${LIBASS_VERSION}.tar.gz" \
        "$LIBASS_SRC" "$LIBASS_SHA256"

    cd "$LIBASS_SRC"
    make clean 2>/dev/null || true
    ./configure \
        --host=$HOST \
        --prefix="$PREFIX" \
        --enable-shared \
        --disable-static \
        --disable-asm \
        --disable-fontconfig \
        --disable-require-system-font-provider \
        CFLAGS="-fPIC -Os -I$PREFIX/include -I$PREFIX/include/freetype2" \
        LDFLAGS="-Wl,-z,max-page-size=16384 -L$PREFIX/lib" \
        FREETYPE_CFLAGS="-I$PREFIX/include/freetype2" \
        FREETYPE_LIBS="-L$PREFIX/lib -lfreetype" \
        FRIBIDI_CFLAGS="-I$PREFIX/include/fribidi" \
        FRIBIDI_LIBS="-L$PREFIX/lib -lfribidi" \
        HARFBUZZ_CFLAGS="-I$PREFIX/include/harfbuzz" \
        HARFBUZZ_LIBS="-L$PREFIX/lib -lharfbuzz"
    make -j$(nproc 2>/dev/null || echo 4)
    make install

    # Drop debug symbols from the installed shared libraries (they ship in the AAR).
    "$STRIP" --strip-unneeded "$PREFIX"/lib/lib{ass,freetype,fribidi,harfbuzz}.so

    echo "libass stack installed to $PREFIX"
}

# Download sources
mkdir -p "$BUILD_DIR"

# Build requested arch(s)
if [ -n "$1" ]; then
    build_arch "$1"
else
    build_arch arm64
    build_arch arm
fi

echo "Done! libass build complete."
