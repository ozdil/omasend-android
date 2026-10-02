#!/usr/bin/env bash
# ==============================================================================
# OmaSend Otomatik Sürüm Artırma, Derleme ve Dağıtım Motoru
# Kullanım: ./auto-release.sh [patch|minor|major]
# Örnek: ./auto-release.sh patch
# ==============================================================================
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
GRADLE_FILE="$SCRIPT_DIR/app/build.gradle.kts"
DIST_DIR="/home/ozdil/Projects/dist"

cd "$SCRIPT_DIR"

BUMP_TYPE="${1:-patch}"

# 1. Mevcut sürüm kodlarını oku
CURRENT_CODE=$(grep -E 'versionCode\s*=' "$GRADLE_FILE" | awk '{print $3}')
CURRENT_NAME=$(grep -E 'versionName\s*=' "$GRADLE_FILE" | sed -E 's/.*"([^"]+)".*/\1/')

NEW_CODE=$((CURRENT_CODE + 1))

# Semver artırımı
IFS='.' read -r MAJOR MINOR PATCH <<< "$CURRENT_NAME"
if [ "$BUMP_TYPE" = "major" ]; then
    MAJOR=$((MAJOR + 1))
    MINOR=0
    PATCH=0
elif [ "$BUMP_TYPE" = "minor" ]; then
    MINOR=$((MINOR + 1))
    PATCH=0
else
    PATCH=$((PATCH + 1))
fi
NEW_NAME="${MAJOR}.${MINOR}.${PATCH}"

echo "========================================================"
echo "Sürüm Güncelleniyor: ${CURRENT_NAME} (kod: ${CURRENT_CODE}) -> ${NEW_NAME} (kod: ${NEW_CODE})"
echo "========================================================"

# 2. build.gradle.kts dosyasını güncelle
sed -i -E "s/versionCode\s*=\s*[0-9]+/versionCode = ${NEW_CODE}/" "$GRADLE_FILE"
sed -i -E "s/versionName\s*=\s*\"[^\"]+\"/versionName = \"${NEW_NAME}\"/" "$GRADLE_FILE"

# 3. İmzalı AAB ve APK paketlerini derle
echo "1/3: İmzalı Release APK derleniyor..."
"$SCRIPT_DIR/build-android.sh" apk

echo "2/3: İmzalı Release AAB derleniyor..."
"$SCRIPT_DIR/build-android.sh" bundle

# 4. dist dizinine dağıt
mkdir -p "$DIST_DIR"
cp "$SCRIPT_DIR/omasend-release.aab" "$DIST_DIR/omasend-release-v${NEW_NAME}.aab"
cp "$SCRIPT_DIR/omasend-release.apk" "$DIST_DIR/omasend-release-v${NEW_NAME}.apk"

echo "3/3: Paketler hazırlandı ve arşivlendi:"
echo "-> $DIST_DIR/omasend-release-v${NEW_NAME}.aab"
echo "-> $DIST_DIR/omasend-release-v${NEW_NAME}.apk"

# 5. Son değişiklikleri özetleyen sürüm notu dosyasını hazırla
NOTES_FILE="$DIST_DIR/release_notes_v${NEW_NAME}.txt"
cat <<EOF > "$NOTES_FILE"
<en-US>
What's new in v${NEW_NAME}:
• AirDrop-grade zero-click local P2P transfer
• Zen Radar v2 with fluid UI & LRA haptics
• Quantum-resilient BLAKE3 integrity & RAM zeroing
• Android Quick Share & direct RFCOMM OBEX support
• Low-latency Rust engine & targetSdk 36 compliance
• UI layout and accessory filtering bug fixes
</en-US>
EOF

echo "Sürüm notu kaydedildi: $NOTES_FILE"
echo "========================================================"
echo "Tamamlandı: v${NEW_NAME} (versionCode: ${NEW_CODE}) başarıyla üretildi."
echo "========================================================"
