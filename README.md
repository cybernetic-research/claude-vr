# Claude VR

<p align="center">
  <a href="https://youtu.be/oHARg-uKmbM"><img src="https://img.youtube.com/vi/oHARg-uKmbM/maxresdefault.jpg" width="640" alt="Watch the demo on YouTube: Claude identifies a circuit board and helps in a VR game"></a><br>
  <b><a href="https://youtu.be/oHARg-uKmbM">▶ Watch the demo (4 min)</a></b>: Claude identifies a buck converter, draws the wiring to an ESP32, and helps out mid-game in Vex Mage.
</p>

Claude, floating in a Meta Quest headset. **Claude Panel** is a sideloaded Android app for Horizon OS that:

- **Sees what you see.** Each message can include a snapshot of your view, whether that's passthrough of your room or the VR app you're in, captured with Android's standard MediaProjection API.
- **Listens and talks.** Mic input uses the headset's on-device speech recogniser; replies are read aloud with eSpeak NG.
- **Sketches.** Claude can draw circuits, diagrams, maths and small interactive demos in a separate canvas window that you can move around your space.
- **Is just a panel.** It's a movable 2D window that sits beside your other apps.

Built and tested on a Quest 3S (Horizon OS, Android 14).

## How it works

```
Quest 3S
 ├─ Claude Panel (2D window)      chat, keyboard, mic, "Send my view"
 │    ├─ CaptureService           MediaProjection → newest frame → JPEG on send
 │    ├─ Voice                    SpeechRecognizer (Meta on-device ASR) + eSpeak TTS
 │    └─ ClaudeChat               Anthropic Java SDK, streaming, show_canvas tool
 └─ Claude Canvas (2nd window)    sandboxed WebView rendering Claude's HTML/SVG
                     │
                     └──────────► Claude API (api.anthropic.com)
```

Nothing runs on a PC; the headset talks to the Claude API directly.

## Repo layout

| Folder | What |
|---|---|
| [`panel/`](panel) | The app. Build, install and usage notes are in its README. |
| [`capture-test/`](capture-test) | The small experiment that proved a sideloaded app can capture the Quest's view. Kept for reference. |

## Quick start

You need a Quest in developer mode, `adb`, JDK 17, the Android SDK (`ANDROID_HOME` set, or `sdk.dir` in `local.properties`) and an [Anthropic API key](https://console.anthropic.com/).

```sh
cd panel
./gradlew assembleDebug
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

For spoken replies, also install [eSpeak NG from F-Droid](https://f-droid.org/packages/com.reecedunn.espeak/) and open it once so it unpacks its voices. Meta's built-in TTS engine ships with no voices.

On first launch, enter your API key in Settings. Typing it on the VR keyboard is painful, so tap into the field and run `adb shell input text 'sk-ant-...'` from your computer. Then allow screen capture when Horizon OS asks. The first mic tap downloads Meta's speech model, which takes about a minute.

## Things to know

- **Cost:** it uses your own API key and you pay per token. Each attached view is roughly 1.5k input tokens. The default model is `claude-opus-5`; change the model and thinking effort in Settings.
- **Privacy:** your view is only captured and sent to Anthropic when you send a message with "Send my view" ticked. The API key stays in the app's private storage on the headset.
- **Immersive apps:** Horizon OS hides all 2D panels while a full VR app runs, so the panel isn't visible then. Capture keeps working in the background; a voice-only mode for that case is next.
- **Protected video** (Netflix etc.) captures as black.

## Disclaimer

An independent hobby project, not affiliated with or endorsed by Anthropic or Meta. Claude is a trademark of Anthropic; Meta Quest and Horizon OS are trademarks of Meta. The icon and splash art are original.
