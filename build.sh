#!/usr/bin/env bash
# Builds PocketHarness.apk from scratch — no Gradle, just aapt2/javac/d8/apksigner.
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
TC="$(cd "$ROOT/../toolchain" && pwd)"
SDK="$TC/sdk"
BT="$SDK/build-tools/34.0.0"
PLATFORM="$SDK/platforms/android-34/android.jar"
SRC="$ROOT/apk-project"
OUT="$ROOT/build"

export JAVA_HOME="$TC/jdk"
export PATH="$JAVA_HOME/bin:$PATH"

rm -rf "$OUT"
mkdir -p "$OUT/classes" "$OUT/dex"
cd "$OUT"

echo "[1/6] aapt2: compile + link resources & manifest"
"$BT/aapt2" compile --dir "$SRC/res" -o compiled.zip
mkdir -p gen
"$BT/aapt2" link \
    -o unsigned.apk \
    -I "$PLATFORM" \
    --manifest "$SRC/AndroidManifest.xml" \
    --min-sdk-version 24 --target-sdk-version 34 \
    --version-code 6 --version-name "0.5-gold" \
    --auto-add-overlay \
    --java gen \
    compiled.zip

echo "[2/6] javac"
find "$SRC/src" "$OUT/gen" -name '*.java' > sources.txt
javac --release 8 -classpath "$PLATFORM" -d classes @sources.txt

echo "[3/6] d8: dex"
find classes -name '*.class' > classlist.txt
"$BT/d8" --release --lib "$PLATFORM" --min-api 24 \
    --output dex $(tr '\n' ' ' < classlist.txt)
test -f dex/classes.dex

echo "[4/6] pack classes.dex into apk"
cp unsigned.apk full.apk
if command -v zip >/dev/null 2>&1; then
  (cd dex && zip -q -j ../full.apk classes.dex)
elif command -v python3 >/dev/null 2>&1; then
  python3 - <<'PY'
import zipfile
z = zipfile.ZipFile('full.apk', 'a')
z.write('dex/classes.dex', 'classes.dex')
z.close()
PY
else
  echo "need zip or python3"; exit 1
fi

echo "[5/6] zipalign"
"$BT/zipalign" -f -p 4 full.apk aligned.apk

echo "[6/6] sign (debug keystore)"
KS="$ROOT/debug.keystore"
if [ ! -f "$KS" ]; then
  keytool -genkeypair -keystore "$KS" -storepass android -keypass android \
    -alias androiddebugkey -keyalg RSA -keysize 2048 -validity 10000 \
    -dname "CN=Pocket Harness Debug,O=PH,C=IN" >/dev/null 2>&1
fi
"$BT/apksigner" sign --ks "$KS" --ks-pass pass:android --key-pass pass:android \
    --out "$ROOT/PocketHarness.apk" aligned.apk
"$BT/apksigner" verify "$ROOT/PocketHarness.apk"

echo ""
echo "BUILD OK → $ROOT/PocketHarness.apk"
ls -la "$ROOT/PocketHarness.apk"
