#!/bin/bash
# Download local build dependencies without changing the system Java/SDK installation.
set -euo pipefail
APP_DIR="$(cd "$(dirname "$0")" && pwd)"
ROOT_DIR="$(dirname "$APP_DIR")"
SDK_DIR="$ROOT_DIR/tools/android-sdk"
JDK_DIR="$ROOT_DIR/tools/jdk"
ACCEPT=false
usage() {
    cat <<'HELP'
Usage: bash app/prepare_deps.sh [--accept-licenses]
Downloads Temurin JDK 17, Android command-line tools, Build Tools 34.0.0,
Android API 29 and platform-tools to tools/. Requires curl, python3, unzip, tar.
Supports macOS Intel/Apple Silicon and Linux x86_64 (glibc).
Proxy: HTTPS_PROXY=http://127.0.0.1:7897 bash app/prepare_deps.sh
HTTP and SOCKS5 proxies without authentication are supported.
Read https://developer.android.com/studio#terms before downloading.
--accept-licenses explicitly accepts Android SDK licenses without prompts.
HELP
}
case "${1:-}" in
    --help|-h) usage; exit 0 ;;
    --accept-licenses) ACCEPT=true ;;
    '') ;;
    *) usage >&2; exit 2 ;;
esac
[ "$#" -le 1 ] || { usage >&2; exit 2; }
for tool in curl python3 unzip tar; do
    command -v "$tool" >/dev/null || { echo "Missing prerequisite: $tool" >&2; exit 1; }
done
case "$(uname -s):$(uname -m)" in
    Darwin:arm64|Darwin:x86_64) OS=mac; SDK_OS=mac
        if [ "$(uname -m)" = arm64 ]; then ARCH=aarch64; else ARCH=x64; fi
        SDK_SHA=7bc5c72ba0275c80a8f19684fb92793b83a6b5c94d4d179fc5988930282d7e64 ;;
    Linux:x86_64) OS=linux; ARCH=x64; SDK_OS=linux
        SDK_SHA=2d2d50857e4eb553af5a6dc3ad507a17adf43d115264b1afc116f95c92e5e258 ;;
    *) echo 'Unsupported host: use macOS or Linux x86_64.' >&2; exit 1 ;;
esac
PROXY_URL="${https_proxy:-${HTTPS_PROXY:-${all_proxy:-${ALL_PROXY:-${http_proxy:-${HTTP_PROXY:-}}}}}}"
CURL_PROXY=()
SDK_PROXY=()
if [ -n "$PROXY_URL" ]; then
    # sdkmanager does not support authenticated or HTTPS-to-proxy connections.
    # Parse without printing the proxy URL, which might contain credentials.
    PROXY_PARTS="$(python3 - "$PROXY_URL" <<'PY'
import sys
from urllib.parse import urlsplit
try:
    p = urlsplit(sys.argv[1])
    if p.scheme not in ('http', 'socks5', 'socks5h') or not p.hostname or not p.port or p.username or p.password:
        raise ValueError()
    print('http' if p.scheme == 'http' else 'socks')
    print(p.hostname)
    print(p.port)
except ValueError:
    sys.exit('Proxy must be an http:// or socks5[h]:// URL with an explicit port and no authentication.')
PY
)"
    PROXY_TYPE="$(printf '%s\n' "$PROXY_PARTS" | sed -n '1p')"
    PROXY_HOST="$(printf '%s\n' "$PROXY_PARTS" | sed -n '2p')"
    PROXY_PORT="$(printf '%s\n' "$PROXY_PARTS" | sed -n '3p')"
    CURL_PROXY=(--proxy "$PROXY_URL")
    SDK_PROXY=(--proxy="$PROXY_TYPE" --proxy_host="$PROXY_HOST" --proxy_port="$PROXY_PORT")
fi
TMP_DIR="$(mktemp -d "${TMPDIR:-/tmp}/nova4-deps.XXXXXX")"
trap 'rm -rf "$TMP_DIR"' EXIT
fetch() {
    curl --fail --location --retry 3 --connect-timeout 20 --max-time 1800 \
        ${CURL_PROXY[@]+"${CURL_PROXY[@]}"} --output "$2" "$1"
}
verify_sha() {
    python3 - "$1" "$2" <<'PY'
import hashlib, sys
h = hashlib.sha256()
with open(sys.argv[1], 'rb') as f:
    for block in iter(lambda: f.read(1024 * 1024), b''):
        h.update(block)
if h.hexdigest() != sys.argv[2]:
    sys.exit('SHA-256 mismatch: refusing to extract download')
PY
}
mkdir -p "$ROOT_DIR/tools" "$SDK_DIR/cmdline-tools"
if [ ! -x "$JDK_DIR/bin/java" ]; then
    echo 'Downloading Temurin JDK 17 metadata and archive...'
    fetch "https://api.adoptium.net/v3/assets/latest/17/hotspot?architecture=$ARCH&image_type=jdk&os=$OS&vendor=eclipse" "$TMP_DIR/jdk.json"
    python3 - "$TMP_DIR/jdk.json" > "$TMP_DIR/jdk-package" <<'PY'
import json, sys
with open(sys.argv[1]) as f:
    package = json.load(f)[0]['binary']['package']
if not package['link'].startswith('https://'):
    sys.exit('Expected an HTTPS JDK download')
print(package['link'])
print(package['checksum'])
PY
    fetch "$(sed -n '1p' "$TMP_DIR/jdk-package")" "$TMP_DIR/jdk.tar.gz"
    verify_sha "$TMP_DIR/jdk.tar.gz" "$(sed -n '2p' "$TMP_DIR/jdk-package")"
    mkdir "$TMP_DIR/jdk"
    tar -xzf "$TMP_DIR/jdk.tar.gz" -C "$TMP_DIR/jdk"
    JAVA_BIN="$(find "$TMP_DIR/jdk" -type f -path '*/bin/java' -print -quit)"
    [ -n "$JAVA_BIN" ] || { echo 'JDK archive has no bin/java' >&2; exit 1; }
    [ ! -e "$JDK_DIR" ] || { echo 'Incomplete tools/jdk exists; move it aside and retry.' >&2; exit 1; }
    mv "$(dirname "$(dirname "$JAVA_BIN")")" "$JDK_DIR"
fi
export JAVA_HOME="$JDK_DIR"
export PATH="$JAVA_HOME/bin:$PATH"
CMDLINE_DIR="$SDK_DIR/cmdline-tools/11076708"
if [ ! -x "$CMDLINE_DIR/bin/sdkmanager" ]; then
    if ! "$ACCEPT"; then
        printf 'Read https://developer.android.com/studio#terms. Accept SDK terms and download? [y/N] '
        read -r answer
        case "$answer" in y|Y|yes|YES) ;; *) echo 'Cancelled.'; exit 1 ;; esac
    fi
    fetch "https://dl.google.com/android/repository/commandlinetools-$SDK_OS-11076708_latest.zip" "$TMP_DIR/cmdline.zip"
    verify_sha "$TMP_DIR/cmdline.zip" "$SDK_SHA"
    unzip -q "$TMP_DIR/cmdline.zip" -d "$TMP_DIR/cmdline"
    [ ! -e "$CMDLINE_DIR" ] || { echo 'Incomplete command-line tools exist; move them aside and retry.' >&2; exit 1; }
    mv "$TMP_DIR/cmdline/cmdline-tools" "$CMDLINE_DIR"
fi
SDKMANAGER="$CMDLINE_DIR/bin/sdkmanager"
if "$ACCEPT"; then
    # sdkmanager may close stdin early; inspect its exit status, not yes's SIGPIPE.
    set +o pipefail
    yes | "$SDKMANAGER" --sdk_root="$SDK_DIR" ${SDK_PROXY[@]+"${SDK_PROXY[@]}"} --licenses
    license_status=${PIPESTATUS[1]}
    set -o pipefail
    [ "$license_status" -eq 0 ] || exit "$license_status"
else
    "$SDKMANAGER" --sdk_root="$SDK_DIR" ${SDK_PROXY[@]+"${SDK_PROXY[@]}"} --licenses
fi
"$SDKMANAGER" --sdk_root="$SDK_DIR" ${SDK_PROXY[@]+"${SDK_PROXY[@]}"} \
    'build-tools;34.0.0' 'platforms;android-29' 'platform-tools'
for file in build-tools/34.0.0/aapt2 build-tools/34.0.0/d8 build-tools/34.0.0/zipalign \
    build-tools/34.0.0/apksigner platforms/android-29/android.jar platform-tools/adb; do
    [ -f "$SDK_DIR/$file" ] || { echo "Missing downloaded dependency: $file" >&2; exit 1; }
done
"$JAVA_HOME/bin/java" -version
"$SDK_DIR/build-tools/34.0.0/aapt2" version
"$SDK_DIR/platform-tools/adb" version
echo 'Dependencies ready. Build: bash app/build.sh'
echo 'ADB: tools/android-sdk/platform-tools/adb'
