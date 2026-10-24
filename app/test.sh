#!/bin/bash
set -euo pipefail
APP_DIR="$(cd "$(dirname "$0")" && pwd)"
if [ -d "$APP_DIR/../tools/jdk" ]; then
    export JAVA_HOME="$(cd "$APP_DIR/../tools/jdk" && pwd)"
    export PATH="$JAVA_HOME/bin:$PATH"
fi
TEST_DIR="$(mktemp -d "${TMPDIR:-/tmp}/nova4-proxy-tests.XXXXXX")"
trap 'rm -rf "$TEST_DIR"' EXIT
javac --release 8 -encoding UTF-8 -d "$TEST_DIR" \
    "$APP_DIR/src/cn/kingour/googleprobe/LoopbackProxy.java" \
    "$APP_DIR/tests/cn/kingour/googleprobe/ProxySettings.java" \
    "$APP_DIR/tests/cn/kingour/googleprobe/LoopbackProxyTest.java"
java -cp "$TEST_DIR" cn.kingour.googleprobe.LoopbackProxyTest
