#!/bin/sh
#
# Downloads everything needed to build android-depth-cam on a machine that has
# nothing installed but curl and unzip:
#
#   * a JDK 17 (Eclipse Temurin)          -> tools/jdk
#   * the Android SDK command line tools  -> tools/cmdline-tools/latest
#   * Android SDK platform 35, build-tools 35.0.0 and platform-tools
#   * the Gradle 8.11.1 distribution the wrapper asks for
#
# Anything already present is reused: an existing JAVA_HOME with a suitable JDK
# and an existing Android SDK (ANDROID_SDK_ROOT / ANDROID_HOME / sdk.dir in
# local.properties / the usual per-user locations) are left where they are and
# only the missing SDK packages get installed. Every step is idempotent, so
# re-running the script is cheap.
#
# It writes local.properties (sdk.dir) and tools/toolchain.env, which build.sh
# reads. Nothing outside this directory is modified except the Android SDK that
# receives the missing packages.
#
# Usage:  ./download-tools.sh [--jdk <dir>] [--sdk <dir>]
#
#   --jdk <dir>  Use this JDK (17-23) instead of searching or downloading.
#   --sdk <dir>  Use this Android SDK; missing packages are installed into it.
#
# JAVA_HOME and ANDROID_SDK_ROOT / ANDROID_HOME are picked up automatically, so
# the flags are only needed for tools in unusual places.
#
set -eu

cd "$(dirname "$0")"

JDK_OVERRIDE=""
SDK_OVERRIDE=""
while [ $# -gt 0 ]; do
    case "$1" in
        --jdk) JDK_OVERRIDE="${2:-}"; shift 2 ;;
        --sdk) SDK_OVERRIDE="${2:-}"; shift 2 ;;
        -h|--help)
            sed -n '2,25p' "$0" | sed 's/^# \{0,1\}//'
            exit 0 ;;
        *) echo "Unknown argument: $1" >&2; exit 2 ;;
    esac
done

# Must match app/build.gradle.kts (compileSdk) and the gradle wrapper.
SDK_PLATFORM=35
BUILD_TOOLS=35.0.0
# JDK 17 is the version AGP 8.7 targets; Gradle 8.11 accepts 17 through 23.
JDK_MAJOR=17
JDK_MIN=17
JDK_MAX=23
# Android SDK command line tools 16.0 (rev 13114758).
CMDLINE_TOOLS_BUILD=13114758

TOOLS_DIR="$PWD/tools"

# ---------------------------------------------------------------- platform

case "$(uname -s)" in
    Linux*)               OS=linux;   SDK_OS=linux ;;
    Darwin*)              OS=mac;     SDK_OS=mac ;;
    MINGW*|MSYS*|CYGWIN*) OS=windows; SDK_OS=win ;;
    *) echo "Unsupported operating system: $(uname -s)" >&2; exit 1 ;;
esac

case "$(uname -m)" in
    x86_64|amd64)  ARCH=x64 ;;
    arm64|aarch64) ARCH=aarch64 ;;
    *) echo "Unsupported CPU architecture: $(uname -m)" >&2; exit 1 ;;
esac

need() {
    command -v "$1" >/dev/null 2>&1 ||
        { echo "Required tool '$1' is not in PATH." >&2; exit 1; }
}
need curl
need unzip
[ "$OS" = windows ] || need tar

# Windows-style path for the tools that are launched through a .bat wrapper.
winpath() {
    if [ "$OS" = windows ] && command -v cygpath >/dev/null 2>&1; then
        cygpath -m "$1"
    else
        printf '%s' "$1"
    fi
}

fetch() {
    echo "  fetching $(basename "$2")"
    curl -fL --progress-bar -o "$2.part" "$1"
    mv "$2.part" "$2"
}

mkdir -p "$TOOLS_DIR"

# --------------------------------------------------------------------- JDK

java_major() {
    [ -x "$1/bin/java" ] || [ -x "$1/bin/java.exe" ] || return 1
    "$1/bin/java" -version 2>&1 |
        sed -n '1s/.*version "\([0-9][0-9]*\).*/\1/p'
}

jdk_ok() {
    [ -n "${1:-}" ] && [ -d "$1" ] || return 1
    major=$(java_major "$1" 2>/dev/null) || return 1
    [ -n "$major" ] || return 1
    [ "$major" -ge "$JDK_MIN" ] && [ "$major" -le "$JDK_MAX" ]
}

find_jdk() {
    # Our own copy first, so repeated runs always agree with themselves.
    for candidate in "$TOOLS_DIR/jdk" "${JAVA_HOME:-}"; do
        if jdk_ok "$candidate"; then printf '%s' "$candidate"; return 0; fi
    done
    for candidate in \
        /usr/lib/jvm/temurin-17-jdk*     /usr/lib/jvm/java-17-openjdk* \
        /Library/Java/JavaVirtualMachines/*/Contents/Home \
        "$HOME/.sdkman/candidates/java/current"
    do
        if jdk_ok "$candidate"; then printf '%s' "$candidate"; return 0; fi
    done
    return 1
}

install_jdk() {
    url="https://api.adoptium.net/v3/binary/latest/$JDK_MAJOR/ga/$OS/$ARCH/jdk/hotspot/normal/eclipse"
    tmp="$TOOLS_DIR/.jdk-unpack"
    rm -rf "$tmp" "$TOOLS_DIR/jdk"
    mkdir -p "$tmp"
    if [ "$OS" = windows ]; then
        fetch "$url" "$TOOLS_DIR/jdk.zip"
        unzip -q "$TOOLS_DIR/jdk.zip" -d "$tmp"
        rm -f "$TOOLS_DIR/jdk.zip"
    else
        fetch "$url" "$TOOLS_DIR/jdk.tar.gz"
        tar -xzf "$TOOLS_DIR/jdk.tar.gz" -C "$tmp"
        rm -f "$TOOLS_DIR/jdk.tar.gz"
    fi
    # The archive holds a single jdk-<version> directory; on macOS the runtime
    # itself sits one level deeper.
    inner=$(find "$tmp" -mindepth 1 -maxdepth 1 -type d | head -1)
    [ -d "$inner/Contents/Home" ] && inner="$inner/Contents/Home"
    mv "$inner" "$TOOLS_DIR/jdk"
    rm -rf "$tmp"
    jdk_ok "$TOOLS_DIR/jdk" ||
        { echo "Downloaded JDK does not run." >&2; exit 1; }
    printf '%s' "$TOOLS_DIR/jdk"
}

echo "[1/3] JDK $JDK_MAJOR"
if [ -n "$JDK_OVERRIDE" ]; then
    jdk_ok "$JDK_OVERRIDE" ||
        { echo "--jdk $JDK_OVERRIDE is not a JDK $JDK_MIN-$JDK_MAX." >&2; exit 1; }
    JDK="$JDK_OVERRIDE"
    echo "  using $JDK (--jdk)"
elif JDK=$(find_jdk); then
    echo "  using $JDK (Java $(java_major "$JDK"))"
else
    echo "  no JDK $JDK_MIN-$JDK_MAX found, downloading Temurin $JDK_MAJOR"
    JDK=$(install_jdk)
    echo "  installed $JDK"
fi
export JAVA_HOME="$JDK"

# ------------------------------------------------------------- Android SDK

# sdk.dir out of local.properties, un-escaping the Java properties form
# (C\:\\Android\\Sdk) back into a plain path.
sdk_from_local_properties() {
    [ -f local.properties ] || return 1
    sed -n 's/^sdk\.dir=//p' local.properties | head -1 | tr -d '\r' |
        sed 's/\\:/:/g; s/\\\\/\//g; s/\\/\//g'
}

find_sdk() {
    for candidate in \
        "$TOOLS_DIR/android-sdk" \
        "${ANDROID_SDK_ROOT:-}" "${ANDROID_HOME:-}" \
        "$(sdk_from_local_properties || true)" \
        "$HOME/Android/Sdk" "$HOME/Library/Android/sdk" \
        "${LOCALAPPDATA:-}/Android/Sdk"
    do
        [ -n "$candidate" ] && [ -d "$candidate" ] || continue
        printf '%s' "$candidate"
        return 0
    done
    return 1
}

echo "[2/3] Android SDK"
if [ -n "$SDK_OVERRIDE" ]; then
    SDK_ROOT="$SDK_OVERRIDE"
    mkdir -p "$SDK_ROOT"
    echo "  using $SDK_ROOT (--sdk)"
elif SDK_ROOT=$(find_sdk); then
    echo "  using $SDK_ROOT"
else
    SDK_ROOT="$TOOLS_DIR/android-sdk"
    echo "  no SDK found, creating $SDK_ROOT"
    mkdir -p "$SDK_ROOT"
fi

have_package() {
    case "$1" in
        platform)       [ -f "$SDK_ROOT/platforms/android-$SDK_PLATFORM/android.jar" ] ;;
        build-tools)    [ -d "$SDK_ROOT/build-tools/$BUILD_TOOLS" ] ;;
        platform-tools) [ -x "$SDK_ROOT/platform-tools/adb" ] ||
                        [ -f "$SDK_ROOT/platform-tools/adb.exe" ] ;;
    esac
}

missing=""
for pkg in platform build-tools platform-tools; do
    have_package "$pkg" || missing="$missing $pkg"
done

if [ -n "$missing" ]; then
    echo "  missing:$missing"
    # The command line tools live in this project, never inside a shared SDK:
    # sdkmanager installs into whatever --sdk_root points at.
    CMDLINE="$TOOLS_DIR/cmdline-tools/latest"
    if [ ! -d "$CMDLINE/bin" ]; then
        url="https://dl.google.com/android/repository/commandlinetools-$SDK_OS-${CMDLINE_TOOLS_BUILD}_latest.zip"
        tmp="$TOOLS_DIR/.cmdline-unpack"
        rm -rf "$tmp" "$CMDLINE"
        mkdir -p "$tmp" "$TOOLS_DIR/cmdline-tools"
        fetch "$url" "$TOOLS_DIR/cmdline-tools.zip"
        unzip -q "$TOOLS_DIR/cmdline-tools.zip" -d "$tmp"
        rm -f "$TOOLS_DIR/cmdline-tools.zip"
        mv "$tmp/cmdline-tools" "$CMDLINE"
        rm -rf "$tmp"
    fi
    if [ "$OS" = windows ]; then
        SDKMANAGER="$CMDLINE/bin/sdkmanager.bat"
    else
        SDKMANAGER="$CMDLINE/bin/sdkmanager"
        chmod +x "$CMDLINE/bin/"* 2>/dev/null || true
    fi
    SDK_ARG=$(winpath "$SDK_ROOT")
    export JAVA_HOME
    echo "  accepting SDK licences"
    yes 2>/dev/null | "$SDKMANAGER" --sdk_root="$SDK_ARG" --licenses >/dev/null || true
    echo "  installing packages"
    "$SDKMANAGER" --sdk_root="$SDK_ARG" \
        "platform-tools" \
        "platforms;android-$SDK_PLATFORM" \
        "build-tools;$BUILD_TOOLS"
    for pkg in platform build-tools platform-tools; do
        have_package "$pkg" ||
            { echo "SDK package '$pkg' still missing after install." >&2; exit 1; }
    done
else
    echo "  platform $SDK_PLATFORM, build-tools $BUILD_TOOLS, platform-tools present"
fi

# ---------------------------------------------- project + toolchain records

# Forward slashes need no escaping in a Java properties file, on any OS.
SDK_PROP=$(winpath "$SDK_ROOT")
printf 'sdk.dir=%s\n' "$SDK_PROP" > local.properties

{
    echo "# Written by download-tools.sh - read by build.sh. Safe to delete."
    printf "JAVA_HOME='%s'\n" "$(winpath "$JDK")"
    printf "ANDROID_SDK_ROOT='%s'\n" "$SDK_ROOT"
} > "$TOOLS_DIR/toolchain.env"

if [ "$OS" = windows ]; then
    {
        echo "@rem Written by download-tools.sh - read by build.cmd. Safe to delete."
        printf 'set "JAVA_HOME=%s"\n' "$(winpath "$JDK" | tr '/' '\\')"
        printf 'set "ANDROID_SDK_ROOT=%s"\n' "$(winpath "$SDK_ROOT" | tr '/' '\\')"
    } > "$TOOLS_DIR/toolchain.cmd"
fi

# ------------------------------------------------------------------ Gradle

# The wrapper downloads the Gradle distribution named in
# gradle/wrapper/gradle-wrapper.properties; doing it here keeps the first
# build from stalling on a ~130 MB download.
echo "[3/3] Gradle distribution"
chmod +x gradlew 2>/dev/null || true
JAVA_HOME="$JDK" sh ./gradlew -Dorg.gradle.java.home="$JDK" --version >/dev/null
echo "  ready"

echo
echo "Toolchain ready:"
echo "  JDK          $JDK"
echo "  Android SDK  $SDK_ROOT"
echo "  Gradle       $(sed -n 's/.*gradle-\(.*\)-bin\.zip/\1/p' gradle/wrapper/gradle-wrapper.properties)"
echo
echo "Next:  ./build.sh"
