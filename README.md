# Depth Cam

A standalone viewer for the **depth (time-of-flight) camera** that sits next to the
main lens on a number of Android phones and is normally invisible to apps: the
system camera app uses it only for portrait-mode blur, and CameraX hides
depth-only cameras entirely.

Depth Cam opens that sensor directly through Camera2, colorizes every `DEPTH16`
frame through a selectable close-to-far gradient and shows it fullscreen. You
can take photos, record video, and stream the live depth view to a relay server.

```
close ──────────────────────────────────────────────── far
███████████████████████████████████████████████████████
```

---

## Run it

No Android Studio, no SDK setup, nothing to install by hand. Open a terminal in
the project folder and run three things in order.

### Windows

1. **`download-tools.cmd`** — once per machine. Downloads the JDK, the Android
   SDK and Gradle into `tools\`; anything you already have is reused instead.
   Takes a few minutes the first time, seconds ever after.
2. **`build.cmd`** — builds the app. The APK lands in
   `out\depth-cam-debug.apk`.
3. **`build.cmd --install`** — deploys it to the phone: plug it in over USB,
   enable USB debugging, accept the prompt on the phone, run this.

### Linux / macOS

1. **`./download-tools.sh`** — once per machine. Downloads the JDK, the Android
   SDK and Gradle into `tools/`; anything you already have is reused instead.
   Needs `curl` and `unzip`.
2. **`./build.sh`** — builds the app. The APK lands in
   `out/depth-cam-debug.apk`.
3. **`./build.sh --install`** — deploys it to the phone: plug it in over USB,
   enable USB debugging, accept the prompt on the phone, run this.

No phone cable handy? Copy `out/depth-cam-debug.apk` to the device and tap it.
Details, options and release builds are under [Building](#building).

---

## What it does

* **Finds the depth sensor even when the OEM hides it.** Listed cameras are
  tried first, then depth sub-cameras of a logical multi-camera, then numeric
  camera ids 0–79 that are missing from the public list (how Huawei, newer
  One UI, OPPO and OnePlus builds tuck their ToF module away). Sources are
  tried in order until one delivers frames.
* **Adaptive colorization.** Each frame's real depth extent is measured and
  smoothed across frames, so the gradient always stretches over the depths
  actually in view instead of clumping at one end. Pixels with no reading are
  painted with the far end of the palette.
* **Ten palettes**, from rainbow hue ramps to plain black-and-white and a
  16-colour stepped palette. Long-press the palette button for a gallery of all
  of them with live gradient strips.
* **Photos and video** of the colorized view, written to `DCIM/DEPTHCAM` and
  visible in the normal gallery.
* **Live streaming** of the depth view as H.264 + AAC to a relay server.
* **Gravity-driven rotation.** The view is landscape-locked, but the button bar
  rotates to whichever edge is physically "down", and captures are saved
  world-upright. A manual rotation pins it for the session.

## Requirements

* Android 10 (API 29) or newer — media is written through `MediaStore`
  `RELATIVE_PATH`, which needs Q.
* A camera that exposes the `DEPTH16` format. Known-good families: Samsung
  DepthVision (S10 5G, Note10+, S20+/Ultra), Pixel 4 uDepth, LG, OPPO R17 /
  RX17 Pro, Huawei P30 Pro / Mate 30 Pro, Zebra rugged devices.
* Without such a sensor the app starts, searches, and reports
  `NO DEPTH CAMERA ON THIS DEVICE` — nothing crashes, there is just nothing to
  show.

Permissions: **camera** (required), **microphone** (optional — sound for
recordings and streams; declined simply means video-only), **internet** (only
used when streaming is switched on).

---

## Using the app

The screen is the viewfinder. A small button bar rides on the edge that is
currently "down".

| Button | Action |
| --- | --- |
| Gradient strip | Next palette. **Long press** opens the palette gallery. |
| Camera glyph | Take a photo (PNG). |
| Red circle | Start / stop recording (MP4). Turns into a square while recording. |
| `GALLERY` | Opens the phone's gallery app. |

Hardware keys, so you can shoot without looking at the screen:

| Key | Action |
| --- | --- |
| Volume Up | Take a photo. |
| Volume Up ×2 (quickly) | Rotate the view 90°, and pin rotation to manual until the app restarts. |
| Volume Down | Start / stop recording. |
| Volume Down, held ~1.2 s | Open **SETTINGS**. |
| Long press on the display (~0.8 s) | Open **SETTINGS**. |
| Back / either volume key, in settings | Back to the depth view. |

Saved files land in `DCIM/DEPTHCAM` as `DEPTHCAM_yyyyMMdd_HHmmss.png` /
`.mp4`. Video is 1920×1080 at 30 fps, 8 Mbps, with mono AAC audio when the
microphone permission was granted; the container carries the orientation the
shot was taken in, so players show it upright.

### Streaming

`SETTINGS → STREAM VIDEO` switches between `OFF` and `STREAM`, and holds the
relay server address and the stream key. Both fields show a status marker:
`OK` when the server accepted them, `BUSY` when another device is already
streaming under that key.

The stream is 854×480 H.264 at 30 fps, ~1.5 Mbps, plus AAC-LC audio at
44.1 kHz mono, 64 kbps. Settings persist across restarts, and a lost
connection reconnects on its own with backoff.

The server protocol is deliberately minimal — a plain TCP socket:

```
client -> STREAM <key>[ <token>]\n
server <- OK [token]      key accepted; the token identifies this device
          BUSY            another live stream holds that key
          BAD             malformed request or rejected key
```

and then, per frame:

| bytes | meaning |
| --- | --- |
| 4 | payload length, big endian |
| 1 | flags: bit 0 = video keyframe, bit 1 = audio |
| 8 | presentation timestamp in milliseconds, big endian |
| n | payload: Annex-B H.264 NALs, or one ADTS-framed AAC frame |

SPS/PPS is prepended to every keyframe, so a viewer can join mid-stream. The
`token` the server hands back on `OK` is stored on the device and presented on
the next connection, which lets a restarted app reclaim its own key from a
stale session. It is never shown in the UI.

`host`, `host:port`, `http://host` and `tcp://host` are all accepted as the
server address; without a port, 80 is used.

---

## Building

The three commands are in [Run it](#run-it); this section is what they do and
how to steer them.

`download-tools` needs `curl` and `unzip` on Unix (PowerShell does the work on
Windows) and about 1.5 GB of disk. It fetches:

* **JDK 17** (Eclipse Temurin) into `tools/jdk`
* **Android SDK command line tools** into `tools/cmdline-tools/latest`
* **Android SDK** platform 35, build-tools 35.0.0 and platform-tools
* the **Gradle 8.11.1** distribution the wrapper asks for

The script writes `local.properties` and `tools/toolchain.env` /
`tools/toolchain.cmd`, which the build scripts read. Re-running it is cheap and
idempotent.

### Already have the tools?

Then almost nothing is downloaded. Before fetching anything the script looks
for what is already installed, and only fills the gaps:

* **JDK** — `tools/jdk`, then `JAVA_HOME`, then the usual system locations
  (`/usr/lib/jvm/...`, `/Library/Java/JavaVirtualMachines/...`, SDKMAN). Any
  JDK between 17 and 23 is accepted as is.
* **Android SDK** — `tools/android-sdk`, then `ANDROID_SDK_ROOT`,
  `ANDROID_HOME`, `sdk.dir` from an existing `local.properties`, then
  `~/Android/Sdk`, `~/Library/Android/sdk` or `%LOCALAPPDATA%\Android\Sdk`.
  An SDK found this way is used where it is; only the missing packages
  (platform 35, build-tools 35.0.0, platform-tools) are installed into it, and
  nothing else about it is touched. A self-contained SDK under
  `tools/android-sdk` is created only when no SDK exists anywhere.

If your tools live somewhere unusual — an Android Studio SDK outside the
default path, say — point at them directly:

```bash
./download-tools.sh --jdk /opt/jdk-17 --sdk /opt/android-sdk
```

```cmd
download-tools.cmd --jdk C:\tools\jdk-17 --sdk C:\tools\android-sdk
```

With a complete toolchain already in place the script does nothing but confirm
it and write the two config files — and you can skip it entirely if
`local.properties` already points at your SDK and `JAVA_HOME` is a JDK 17-23.

### build.sh / build.cmd

```
build.sh [debug|release] [--install] [--clean]
```

| | |
| --- | --- |
| `debug` (default) | Signed with the local debug key — installable as is. |
| `release` | Optimised, but **unsigned**. |
| `--install` | `adb install -r` the debug APK onto the connected device. |
| `--clean` | Wipe previous build output first. |

The APK is copied to `out/depth-cam-debug.apk` or
`out/depth-cam-release-unsigned.apk`; Gradle's own copies stay under
`app/build/outputs/apk/`.

Installing by hand:

```bash
adb install -r out/depth-cam-debug.apk
```

### Signing a release build

Release builds have no signing config, so they come out unsigned. To sign one
with your own key:

```bash
tools/android-sdk/build-tools/35.0.0/apksigner sign \
    --ks my-release.jks --out depth-cam.apk \
    out/depth-cam-release-unsigned.apk
```

(Use your SDK's `build-tools/35.0.0/apksigner` if the toolchain script reused an
existing SDK.)

### Gradle directly

The wrapper works on its own once the toolchain is in place:

```bash
./gradlew assembleDebug
```

Gradle 8.11 runs on JDK 17 through 23 and AGP 8.7 needs at least 17, so keep
`JAVA_HOME` inside that range — a newer JDK (24+) will refuse to start the
daemon. The build scripts pass the right one in explicitly, which is why they
work even when the shell's default `java` is too new.

---

## Layout

```
app/src/main/java/com/example/depthcam/
    MainActivity.kt    UI, controls, rotation, capture, settings wiring
    DepthCamera.kt     Camera2 DEPTH16 access, source discovery, palettes (LUTs)
    DepthRecorder.kt   MediaRecorder capture of the composited view -> MP4
    VideoStreamer.kt   H.264 + AAC encoding and the relay-server protocol
    SettingsView.kt    Custom-drawn settings page
    StreamState.kt     Shared streaming state
download-tools.sh/.cmd  Toolchain fetcher
build.sh/.cmd           Build + APK
```

The whole UI is built in code — no layout XML, no Compose, two AndroidX
dependencies (`core-ktx`, `activity-ktx`). The APK is about 6 MB.

## How the depth view is produced

`DEPTH16` packs a millimetre distance in the low 13 bits of each 16-bit sample,
with `0` meaning "no reading". Every frame is scanned once for its real
near/far extent, which is smoothed frame-to-frame (α = 0.25, with a 150 mm
floor on the span so a flat scene cannot flicker), and then mapped through a
256-entry lookup table into an ARGB bitmap. Recording and streaming draw the
same composited scene onto the encoder's input surface on every other
Choreographer frame, which gives a steady ~30 fps.
