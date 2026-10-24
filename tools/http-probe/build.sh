#!/bin/bash
set -euo pipefail
TOOL_DIR="$(cd "$(dirname "$0")" && pwd)"
ROOT_DIR="$(cd "$TOOL_DIR/../.." && pwd)"
SDK_DIR="$ROOT_DIR/tools/android-sdk"
if [ -d "$ROOT_DIR/tools/jdk" ]; then
    export JAVA_HOME="$ROOT_DIR/tools/jdk"
    export PATH="$JAVA_HOME/bin:$PATH"
fi
D8="$SDK_DIR/build-tools/34.0.0/d8"
ANDROID_JAR="$SDK_DIR/platforms/android-29/android.jar"
[ -x "$D8" ] && [ -f "$ANDROID_JAR" ] || {
    echo 'Run bash app/prepare_deps.sh first.' >&2; exit 1;
}
for class in GoogleHttpProbe GoogleProbeProxy; do
    OUT="$TOOL_DIR/build/$class"
    mkdir -p "$OUT/classes" "$OUT/dex"
    javac --release 8 -encoding UTF-8 -d "$OUT/classes" "$TOOL_DIR/$class.java"
    "$D8" --min-api 26 --lib "$ANDROID_JAR" --output "$OUT/dex" "$OUT/classes/"*.class
    jar cf "$TOOL_DIR/build/$class.jar" -C "$OUT/dex" classes.dex
done
echo 'DEX JARs written to tools/http-probe/build/'
