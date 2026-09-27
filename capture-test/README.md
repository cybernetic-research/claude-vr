# MediaProjection capture test (Meta Quest)

A small 2D Android app that answers one question: **can a sideloaded app on
Horizon OS capture what the user sees through Android's MediaProjection API?**
The answer decides the architecture of the Claude VR panel:

- **Works:** everything runs on the headset (panel + mic + screen frames + Claude API).
- **Blocked:** fall back to on-device localhost ADB (`screencap`), then to a PC companion running scrcpy.

No dependencies; plain Android framework APIs only.

## Build

Needs JDK 17 and the Android SDK (platform 35). Either open the folder in
Android Studio, or from the command line:

```sh
export JAVA_HOME=/opt/homebrew/opt/openjdk@17/libexec/openjdk.jdk/Contents/Home
./gradlew assembleDebug --project-cache-dir ~/.gradle/project-caches/claude-vr-capture-test
# -> app/build/outputs/apk/debug/app-debug.apk
```

`--project-cache-dir` is needed because this repo lives on a network share
(`/Volumes/Public`), which doesn't support the file locks Gradle uses. SDK path
is in `local.properties` (not committed).

## Install

Quest in developer mode, connected over USB (or wireless ADB):

```sh
adb install -r app/build/outputs/apk/debug/app-debug.apk
adb logcat -s ClaudeVRCapture     # watch results live
```

On the headset: App Library → filter **Unknown sources** → *Claude VR Capture Test*.

## Tests to run

| # | Do this | What it tells us |
|---|---------|------------------|
| 1 | Tap **Request capture** | Does a consent prompt appear at all? The log says `RESULT: ...` if the OS refuses. |
| 2 | Allow, then **Grab frame now** | Is the frame real or all black? What resolution is the "display"? |
| 3 | Open the browser as a second panel beside this one, **Grab in 15s** | Does capture include other 2D apps? |
| 4 | **Grab in 15s**, then launch an immersive VR game | Does capture keep running (and see the game) while our panel is hidden? |
| 5 | Check any frame taken in passthrough | Is the real room visible, or blanked out? |

Every frame gets a quick check (percent of non-black pixels) in the log.
Pull the saved frames to look at them:

```sh
adb pull /sdcard/Android/data/dev.claudevr.capturetest/files/frames/ ./frames
```

Report back the `adb logcat -s ClaudeVRCapture` output plus a couple of frames.
