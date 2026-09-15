#!/bin/sh
#
# Builds the Depth Cam APK and copies it to out/.
#
# Usage:  ./build.sh [debug|release] [--install] [--clean]
#
#   debug     (default) APK signed with the local debug key - installable as is.
#   release   Optimised but UNSIGNED - sign it before installing (see README).
#   --install adb install -r the freshly built debug APK.
#   --clean   Wipe previous build output first.
#
# The toolchain comes from tools/toolchain.env when download-tools.sh has been
# run; otherwise the usual JAVA_HOME / ANDROID_SDK_ROOT locations are tried.
#
set -eu

cd "$(dirname "$0")"

BUILD_TYPE=debug
INSTALL=no
CLEAN=no

for arg in "$@"; do
    case "$arg" in
        debug|release) BUILD_TYPE="$arg" ;;
        --install)     INSTALL=yes ;;
        --clean)       CLEAN=yes ;;
        -h|--help)
            sed -n '2,15p' "$0" | sed 's/^# \{0,1\}//'
            exit 0 ;;
        *) echo "Unknown argument: $arg" >&2; exit 2 ;;
    esac
done

case "$BUILD_TYPE" in
    debug)
        TASK=assembleDebug
        APK=app/build/outputs/apk/debug/app-debug.apk
        OUT=out/depth-cam-debug.apk ;;
    release)
        TASK=assembleRelease
        APK=app/build/outputs/apk/release/app-release-unsigned.apk
        OUT=out/depth-cam-release-unsigned.apk ;;
esac

# ---------------------------------------------------------------- toolchain

java_major() {
    [ -n "${1:-}" ] && [ -d "$1" ] || return 1
    "$1/bin/java" -version 2>&1 | sed -n '1s/.*version "\([0-9][0-9]*\).*/\1/p'
}

jdk_ok() {
    major=$(java_major "${1:-}" 2>/dev/null) || return 1
    [ -n "$major" ] || return 1
    [ "$major" -ge 17 ] && [ "$major" -le 23 ]
}

# What download-tools recorded wins, as long as it is still there; otherwise
# fall back to the environment and then to the usual places.
ENV_JAVA_HOME="${JAVA_HOME:-}"
if [ -f tools/toolchain.env ]; then
    . ./tools/toolchain.env
fi
jdk_ok "${JAVA_HOME:-}" || JAVA_HOME="$ENV_JAVA_HOME"

if ! jdk_ok "${JAVA_HOME:-}"; then
    for candidate in "$PWD/tools/jdk" \
                     /usr/lib/jvm/java-17-openjdk* /usr/lib/jvm/temurin-17-jdk* \
                     /Library/Java/JavaVirtualMachines/*/Contents/Home
    do
        if jdk_ok "$candidate"; then JAVA_HOME="$candidate"; break; fi
    done
fi

if ! jdk_ok "${JAVA_HOME:-}"; then
    echo "No JDK 17-23 found. Run ./download-tools.sh first." >&2
    exit 1
fi
export JAVA_HOME

if [ ! -f local.properties ] && [ -z "${ANDROID_SDK_ROOT:-}" ] &&
   [ -z "${ANDROID_HOME:-}" ]; then
    echo "No Android SDK configured. Run ./download-tools.sh first." >&2
    exit 1
fi

# ------------------------------------------------------------------- build

echo "Building $BUILD_TYPE with Java $(java_major "$JAVA_HOME") ($JAVA_HOME)"
chmod +x gradlew 2>/dev/null || true

[ "$CLEAN" = yes ] && sh ./gradlew -Dorg.gradle.java.home="$JAVA_HOME" clean

sh ./gradlew -Dorg.gradle.java.home="$JAVA_HOME" "$TASK"

[ -f "$APK" ] || { echo "Expected APK not found at $APK" >&2; exit 1; }

mkdir -p out
cp "$APK" "$OUT"

SIZE=$(ls -l "$OUT" | awk '{printf "%.1f MB", $5/1048576}')
echo
echo "APK:  $OUT  ($SIZE)"
[ "$BUILD_TYPE" = release ] &&
    echo "Note: release APKs are unsigned - see README.md before installing."

# ----------------------------------------------------------------- install

if [ "$INSTALL" = yes ]; then
    if [ "$BUILD_TYPE" = release ]; then
        echo "Refusing to install an unsigned release APK." >&2
        exit 1
    fi
    ADB=adb
    for candidate in "${ANDROID_SDK_ROOT:-}/platform-tools/adb" \
                     "${ANDROID_HOME:-}/platform-tools/adb"
    do
        [ -x "$candidate" ] && { ADB="$candidate"; break; }
    done
    echo
    echo "Installing..."
    "$ADB" install -r "$OUT"
fi
