# Claude Panel (Meta Quest)

A floating 2D panel that chats with Claude and can attach a snapshot of what
you're seeing (passthrough or the app you're in) to each message.

- **View:** Android MediaProjection. Horizon OS asks for consent once per launch.
  Frames are only sent when you send a message with "Send my view" ticked.
- **Mic:** the headset's built-in speech recogniser (Meta's on-device ASR).
- **Spoken replies:** eSpeak NG (sideloaded from F-Droid; Meta's own TTS engine
  ships with no voices). Other installed engines are tried only if eSpeak is missing.
- **Canvas:** a second, separately movable window. Claude calls a `show_canvas`
  tool with a self-contained HTML page (SVG sketches, circuits, Mermaid diagrams,
  KaTeX maths, charts, small interactive demos) and it renders in a sandboxed
  WebView. The "Canvas" button reopens it.
- **Claude:** official Anthropic Java SDK, streaming, default model `claude-opus-5`
  with the server-side refusal fallback enabled. The API key is stored in the
  app's private storage on the headset.

Verified on a Quest 3S (Horizon OS, Android 14).

## Build & install

```sh
export JAVA_HOME=/opt/homebrew/opt/openjdk@17/libexec/openjdk.jdk/Contents/Home
./gradlew assembleDebug --project-cache-dir ~/.gradle/project-caches/claude-vr-panel
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

`--project-cache-dir` is needed because the repo lives on a network share.

## Entering the API key without the VR keyboard

Open Settings in the panel, tap into the API key field, then from a Mac terminal:

```sh
adb shell input text 'sk-ant-...'
```

## Limits

- While an immersive VR app runs, Horizon OS hides every 2D panel, this one
  included. Capture keeps working in the background, but you can't see or
  type into the panel until the immersive app exits.
- DRM-protected video captures as black.

## Next

- Background voice mode for use inside immersive apps (wake word -> snapshot ->
  Claude -> spoken reply).
