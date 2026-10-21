#!/usr/bin/env bash
set -euo pipefail

PROJECT_DIR="/root/moonbear-k4-auto"
BUILD_DIR="$PROJECT_DIR/build"
DIST_DIR="$PROJECT_DIR/dist"
LIBS_DIR="$PROJECT_DIR/libs"
SDK_DIR="/usr/lib/android-sdk"
ANDROID_JAR="$SDK_DIR/platforms/android-35/android.jar"
BUILD_TOOLS="$SDK_DIR/build-tools/35.0.1"
AAPT2="$BUILD_TOOLS/aapt2"
D8="$BUILD_TOOLS/d8"
ZIPALIGN="$BUILD_TOOLS/zipalign"
APKSIGNER="$BUILD_TOOLS/apksigner"

CLASSPATH="$ANDROID_JAR:$(ls "$LIBS_DIR"/*.jar | tr '
' ':')"

rm -rf "$BUILD_DIR"
mkdir -p "$BUILD_DIR/compiled_res" "$BUILD_DIR/gen" "$BUILD_DIR/classes" "$BUILD_DIR/dex" "$DIST_DIR"

echo "[1/6] Compiling resources with aapt2..."
"$AAPT2" compile --dir "$PROJECT_DIR/res" -o "$BUILD_DIR/compiled_res/res.zip"

echo "[2/6] Linking resources and generating R.java..."
"$AAPT2" link \
  -I "$ANDROID_JAR" \
  --manifest "$PROJECT_DIR/AndroidManifest.xml" \
  --java "$BUILD_DIR/gen" \
  --min-sdk-version 26 \
  --target-sdk-version 35 \
  --version-code 90 \
  --version-name "5.0.0-k4gt" \
  -A "$PROJECT_DIR/assets" \
  -o "$BUILD_DIR/unaligned.apk" \
  "$BUILD_DIR/compiled_res/res.zip"

echo "[3/6] Compiling Java sources with javac..."
find "$PROJECT_DIR/src" "$BUILD_DIR/gen" -name "*.java" > "$BUILD_DIR/sources.txt"
javac \
  --release 11 \
  -classpath "$CLASSPATH" \
  -d "$BUILD_DIR/classes" \
  @"$BUILD_DIR/sources.txt"

echo "[4/6] Converting JVM bytecode + Jetpack MediaBrowserServiceCompat + VersionedParcelable to DEX..."
find "$BUILD_DIR/classes" -name "*.class" > "$BUILD_DIR/classfiles.txt"
"$D8" \
  --lib "$ANDROID_JAR" \
  --min-api 26 \
  --output "$BUILD_DIR/dex" \
  $(cat "$BUILD_DIR/classfiles.txt") \
  $(ls "$LIBS_DIR"/*.jar)

echo "[5/6] Adding classes.dex to APK and zipaligning..."
(cd "$BUILD_DIR/dex" && zip -q "$BUILD_DIR/unaligned.apk" classes*.dex)
mkdir -p "$BUILD_DIR/nativelib/lib/arm64-v8a"
cp "$PROJECT_DIR"/jni/arm64-v8a/*.so "$BUILD_DIR/nativelib/lib/arm64-v8a/"
(cd "$BUILD_DIR/nativelib" && zip -q -0 "$BUILD_DIR/unaligned.apk" lib/arm64-v8a/*.so)
"$ZIPALIGN" -p -f 4 "$BUILD_DIR/unaligned.apk" "$BUILD_DIR/aligned.apk"

KEYSTORE="$PROJECT_DIR/moonbear-release.jks"
# Password comes from Bitwarden at build time — never committed, never in this script.
KS_PASS="$("$PROJECT_DIR/get_ks_pass.sh")"
if [ -z "$KS_PASS" ]; then
  echo "ERROR: could not obtain the keystore password" >&2
  exit 1
fi
echo "[6/6] Signing APK (v1+v2+v3) and verifying..."
"$APKSIGNER" sign \
  --ks "$KEYSTORE" \
  --ks-key-alias moonbear_k4 \
  --ks-pass "pass:$KS_PASS" \
  --key-pass "pass:$KS_PASS" \
  --v1-signing-enabled true \
  --v2-signing-enabled true \
  --v3-signing-enabled true \
  --out "$DIST_DIR/moonbear-k4-auto.apk" \
  "$BUILD_DIR/aligned.apk"

"$APKSIGNER" verify --verbose "$DIST_DIR/moonbear-k4-auto.apk"
ls -lh "$DIST_DIR/moonbear-k4-auto.apk"
