#!/bin/bash
set -euo pipefail
APP_DIR="$(cd "$(dirname "$0")" && pwd)"
SDK_DIR="$APP_DIR/../tools/android-sdk"
TOOLS_DIR="$SDK_DIR/android-14"
ANDROID_JAR="$SDK_DIR/android-10/android.jar"
BUILD_DIR="$APP_DIR/build"
mkdir -p "$BUILD_DIR/classes" "$BUILD_DIR/dex" "$BUILD_DIR/generated" "$APP_DIR/signing"
"$TOOLS_DIR/aapt2" compile --dir "$APP_DIR/res" -o "$BUILD_DIR/resources.zip"
"$TOOLS_DIR/aapt2" link -o "$BUILD_DIR/base.apk" --manifest "$APP_DIR/AndroidManifest.xml" \
    -I "$ANDROID_JAR" --java "$BUILD_DIR/generated" "$BUILD_DIR/resources.zip"
find "$APP_DIR/src" "$BUILD_DIR/generated" -name '*.java' -print > "$BUILD_DIR/sources.list"
javac --release 8 -encoding UTF-8 -classpath "$ANDROID_JAR" -d "$BUILD_DIR/classes" @"$BUILD_DIR/sources.list"
find "$BUILD_DIR/classes" -name '*.class' -print > "$BUILD_DIR/classes.list"
"$TOOLS_DIR/d8" --min-api 26 --lib "$ANDROID_JAR" --output "$BUILD_DIR/dex" @"$BUILD_DIR/classes.list"
cp "$BUILD_DIR/base.apk" "$BUILD_DIR/unsigned.apk"
(cd "$BUILD_DIR/dex" && zip -q -u "$BUILD_DIR/unsigned.apk" classes*.dex)
"$TOOLS_DIR/zipalign" -f 4 "$BUILD_DIR/unsigned.apk" "$BUILD_DIR/aligned.apk"
if [ ! -f "$APP_DIR/signing/release.jks" ]; then
    umask 077
    openssl rand -hex 24 > "$APP_DIR/signing/password.txt"
    keytool -genkeypair -keystore "$APP_DIR/signing/release.jks" \
        -storepass:file "$APP_DIR/signing/password.txt" -keypass:file "$APP_DIR/signing/password.txt" \
        -alias nova4 -keyalg RSA -keysize 3072 -validity 3650 \
        -dname "CN=Nova4 Google Probe Local Build"
fi
"$TOOLS_DIR/apksigner" sign --ks "$APP_DIR/signing/release.jks" --ks-key-alias nova4 \
    --ks-pass "file:$APP_DIR/signing/password.txt" \
    --out "$APP_DIR/Google联网修复.apk" "$BUILD_DIR/aligned.apk"
"$TOOLS_DIR/apksigner" verify --verbose "$APP_DIR/Google联网修复.apk"
"$TOOLS_DIR/aapt2" dump badging "$APP_DIR/Google联网修复.apk" | sed -n '1,7p'
