#!/usr/bin/env bash
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"

if [ -z "${JAVA_HOME:-}" ]; then
    if [ -d "$HOME/.local/share/mise/installs/java/21.0.2" ]; then
        export JAVA_HOME="$HOME/.local/share/mise/installs/java/21.0.2"
    fi
fi

echo "Building OmaSend Android with strict dependency verification..."
cd "$SCRIPT_DIR"

MODE="${1:-debug}"

if [ "$MODE" = "bundle" ] || [ "$MODE" = "release" ]; then
    ./gradlew bundleRelease --dependency-verification=strict
    AAB_SRC="$SCRIPT_DIR/app/build/outputs/bundle/release/app-release.aab"
    AAB_DEST="$SCRIPT_DIR/omasend-release.aab"
    if [ -f "$AAB_SRC" ]; then
        cp "$AAB_SRC" "$AAB_DEST"
        echo "Release bundle created: $AAB_DEST"
    else
        echo "Error: Release bundle output not found at $AAB_SRC" >&2
        exit 1
    fi
elif [ "$MODE" = "apk" ]; then
    ./gradlew assembleRelease --dependency-verification=strict
    APK_SRC="$SCRIPT_DIR/app/build/outputs/apk/release/app-release.apk"
    APK_DEST="$SCRIPT_DIR/omasend-release.apk"
    if [ -f "$APK_SRC" ]; then
        cp "$APK_SRC" "$APK_DEST"
        echo "Release APK created: $APK_DEST"
    else
        echo "Error: Release APK output not found at $APK_SRC" >&2
        exit 1
    fi
else
    ./gradlew assembleDebug --dependency-verification=strict
    APK_SRC="$SCRIPT_DIR/app/build/outputs/apk/debug/app-debug.apk"
    APK_DEST="$SCRIPT_DIR/omasend-debug.apk"
    if [ -f "$APK_SRC" ]; then
        cp "$APK_SRC" "$APK_DEST"
        echo "Debug APK created: $APK_DEST"
    else
        echo "Error: Debug APK output not found at $APK_SRC" >&2
        exit 1
    fi
fi
