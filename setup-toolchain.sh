#!/usr/bin/env bash
# Fetches a self-contained Android build toolchain into ./toolchain/
# (JDK 17 + Android cmdline-tools + platform android-34 + build-tools 34.0.0)
set -euo pipefail

TC="$(cd "$(dirname "${BASH_SOURCE[0]}")/../toolchain" && pwd)"
mkdir -p "$TC"
cd "$TC"
export PATH="$TC/jdk/bin:$PATH"

log() { echo "[setup] $(date +%H:%M:%S) $*"; }

ARCH="$(uname -m)"
case "$ARCH" in
  x86_64) JDK_URL="https://api.adoptium.net/v3/binary/latest/17/ga/linux/x64/jdk/hotspot/normal/eclipse" ;;
  aarch64) JDK_URL="https://api.adoptium.net/v3/binary/latest/17/ga/linux/aarch64/jdk/hotspot/normal/eclipse" ;;
  *) echo "unsupported arch $ARCH"; exit 1 ;;
esac

if [ ! -x "$TC/jdk/bin/javac" ]; then
  log "downloading JDK 17 ($ARCH)..."
  curl -sSL -o jdk.tar.gz "$JDK_URL"
  log "extracting JDK..."
  mkdir -p jdk && tar -xzf jdk.tar.gz -C jdk --strip-components=1
  rm jdk.tar.gz
fi
"$TC/jdk/bin/java" -version 2>&1 | head -1 || true

CMDLINE_TOOLS_ZIP="commandlinetools-linux-11076708_latest.zip"
if [ ! -x "$TC/sdk/cmdline-tools/latest/bin/sdkmanager" ]; then
  log "downloading Android cmdline-tools..."
  curl -sSL -o ctools.zip "https://dl.google.com/android/repository/$CMDLINE_TOOLS_ZIP"
  mkdir -p sdk/cmdline-tools
  unzip -q ctools.zip -d sdk/cmdline-tools
  mv sdk/cmdline-tools/cmdline-tools sdk/cmdline-tools/latest
  rm ctools.zip
fi

export ANDROID_HOME="$TC/sdk"
log "accepting licenses..."
yes | "$TC/sdk/cmdline-tools/latest/bin/sdkmanager" --licenses > /dev/null 2>&1 || true

if [ ! -f "$TC/sdk/platforms/android-34/android.jar" ]; then
  log "installing platforms;android-34 + build-tools;34.0.0 (this is the big one)..."
  yes | "$TC/sdk/cmdline-tools/latest/bin/sdkmanager" --install \
    "platforms;android-34" "build-tools;34.0.0"
fi

log "verifying pieces..."
test -x "$TC/jdk/bin/javac"
test -f "$TC/sdk/platforms/android-34/android.jar"
test -x "$TC/sdk/build-tools/34.0.0/aapt2"
test -x "$TC/sdk/build-tools/34.0.0/apksigner"

log "TOOLCHAIN READY ✔"
