#!/usr/bin/env bash
# Bypasses sdkmanager entirely: pulls platform + build-tools zips directly.
set -euo pipefail
ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
TC="$(cd "$ROOT/../toolchain" && pwd)"
SDK="$TC/sdk"
mkdir -p "$SDK/platforms" "$SDK/build-tools" /tmp/sdkdl
cd /tmp/sdkdl

echo "[fetch] $(date +%H:%M:%S) downloading platform-34..."
curl -sSL --retry 3 -o p34.zip https://dl.google.com/android/repository/platform-34-ext7_r03.zip
unzip -q p34.zip -d p34x
src="$(find p34x -maxdepth 1 -mindepth 1 -type d | head -1)"
test -f "$src/android.jar" || { echo "no android.jar in $src"; ls "$src"; exit 1; }
rm -rf "$SDK/platforms/android-34" && mv "$src" "$SDK/platforms/android-34"

echo "[fetch] $(date +%H:%M:%S) downloading build-tools r34..."
curl -sSL --retry 3 -o bt34.zip https://dl.google.com/android/repository/build-tools_r34-linux.zip
unzip -q bt34.zip -d bt34x
src="$(find bt34x -maxdepth 1 -mindepth 1 -type d | head -1)"
test -e "$src/aapt2" || { echo "no aapt2 in $src"; ls "$src"; exit 1; }
rm -rf "$SDK/build-tools/34.0.0" && mv "$src" "$SDK/build-tools/34.0.0"

rm -rf /tmp/sdkdl
chmod +x "$SDK"/build-tools/34.0.0/* 2>/dev/null || true

echo "[fetch] VERIFY:"
ls -la "$SDK/platforms/android-34/android.jar"
"$TC/jdk/bin/java" -version 2>&1 | head -1
ls "$SDK/build-tools/34.0.0/" | grep -E '^(aapt2|d8|apksigner|zipalign)$'
echo "[fetch] SDK PIECES READY ✔"
