#!/bin/bash
#
# Build everything: libass + dependencies (FreeType, FriBidi, HarfBuzz)
# for all target Android architectures.
#
# Usage: ./build-all.sh [arm64|arm]
#
set -e

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"

echo "============================================"
echo "  assrender — Full Native Build"
echo "============================================"
echo ""

ARCH=${1:-""}

echo "Building libass + dependencies..."
bash "$SCRIPT_DIR/build-libass.sh" $ARCH

echo ""
echo "============================================"
echo "  Build complete!"
echo "  Prebuilt libraries are in: prebuilt/"
echo "============================================"
