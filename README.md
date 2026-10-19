# TradeLens Local

TradeLens Local is a native Android, read-only companion that summarizes visible trading-screen text with local Gemma inference. It is educational, not financial advice, and it never places or automates trades.

## 10-line architecture summary

1. Native Android app built with Kotlin, Gradle Kotlin DSL, Jetpack Compose, and Android XML overlays.
2. `ScreenReaderService` reads accessibility text only from the configured trading-app package.
3. Accessibility text is capped at 4,000 characters, deduplicated by hash, debounced, and rate-limited to one inference per 10 seconds.
4. Floating `OverlayService` is user-started, foreground-notified, draggable, and shows results for 20 seconds.
5. Manual overlay analysis requests Android MediaProjection consent and uses bundled ML Kit text recognition for one screenshot.
6. MediaPipe Tasks GenAI 0.10.14 loads a local Gemma 2B int4 model and runs inference without a cloud AI service.
7. MediaPipe 0.10.14 does not expose a preferred CPU/GPU option in its Android `LlmInferenceOptions`; model format determines delegate behavior.
8. The GPU preference is enabled only when an EGL-backed `GL_RENDERER` contains `Adreno`; for Mali and PowerVR, use a CPU-compatible model.
9. Room stores analysis history and DataStore stores settings; both remain in the app's private on-device storage.
10. OkHttp is used only for a user-started model download; imports use Android's local document picker, and no analytics or telemetry are included.

## Requirements and safety

- Android 8.0 (API 26) or newer; target SDK 34; Java 17 for builds.
- Intended device: iQOO Z10 Turbo, Dimensity 8400, Mali-G720, 12 GB RAM, Android 15.
- No auto-trading, accessibility actions, or interaction with the trading app.
- The accessibility reader is read-only and filters to the exact package name entered in settings.
- The disclaimer must be accepted before screen analysis is enabled.
- Local inference and OCR do not send screen contents to a server.
- The only app network request is a model download initiated by the user. The default Hugging Face model URL may be gated and can require the user's own accepted license session or an alternate authorized download URL.
- All saved analysis history and settings are local. Uninstalling the app removes its private files.

### Backend note for the requested MediaPipe version

The Android API in `com.google.mediapipe:tasks-genai:0.10.14` provides model path, token limit, Top K, and temperature settings, but it does not provide a `preferredBackend` builder option. TradeLens therefore uses the SDK's model/runtime behavior and does not pretend to switch a backend that the API does not expose. The GPU control stays disabled unless `GL_RENDERER` contains `Adreno`. On the target iQOO Z10 Turbo, which uses Mali, keep GPU disabled. If a downloaded model crashes on that device, use a compatible CPU-format model URL or import a compatible CPU model; the default `gpu-int4` filename may not be appropriate for every runtime.

## Build & install

### Build flow from Replit

1. Edit the Android project in Replit.
2. Push the repository to GitHub.
3. In GitHub, open the **Actions** tab and run **Build TradeLens Local APK** (or push to `main`).
4. Open the successful workflow run, download the `tradelens-local-debug` artifact, and extract the APK.
5. Transfer the APK to the phone, allow installation from that source when Android asks, and sideload it.

The action builds `app-debug.apk` on pushes to `main`, workflow dispatches, and `v*` tags. A `v*` tag also creates an unsigned release APK artifact. Unsigned release APKs cannot update a differently signed installation; use the debug artifact for the normal sideload flow.

To build locally with Android SDK and Java 17 installed:

```bash
chmod +x ./gradlew
./gradlew assembleDebug --no-daemon
```

The debug APK is at `app/build/outputs/apk/debug/app-debug.apk`.

### First launch

1. Read and explicitly accept the educational-use disclaimer.
2. Set the trading app's exact Android package name.
3. Enable the TradeLens accessibility service: **Settings > Accessibility > TradeLens > On**.
4. Allow the floating window permission and notifications when prompted.
5. Keep **Enable analysis** on and start the floating overlay.
6. Download or import a compatible local Gemma model. The default model download is about **1.5 GB**; Wi-Fi is recommended.
7. In the target trading app, the accessibility reader analyzes changed text. To run OCR, tap the floating **Analyze** bubble and approve Android's one-time screen-capture prompt; after approval TradeLens returns to the previous app and captures one frame.

### OriginOS background settings (iQOO / Vivo)

OriginOS may stop background services after roughly 10 minutes. Set all of these:

- **Settings > Battery > Background power consumption > TradeLens > Unrestricted**
- **Settings > Apps > TradeLens > Battery > Unrestricted**
- **Settings > Apps > TradeLens > Auto-start > On**
- Lock TradeLens in recent apps by swiping down on its app card.

## Troubleshooting

- **Model fails to load:** confirm the download/import is a MediaPipe-compatible model, the Hugging Face license is accepted, and the file is complete. If the GPU-format model crashes, leave the GPU preference disabled and use a CPU-compatible model URL or import. The MediaPipe 0.10.14 Android API does not expose an explicit backend switch.
- **Slow inference:** CPU performance varies by model and device. On the target class, expect roughly 6–12 tokens/second and about 25–50 seconds per analysis; the app enforces a 60-second limit.
- **JSON errors:** set temperature to 0.1. The app retries once with a JSON-only reminder, then shows the raw model response and a parse-error notice.
- **Accessibility not firing:** check that the configured package name exactly matches the trading app and that the service is enabled. Apply the OriginOS battery and auto-start settings above.
- **Overlay does not appear:** grant “Display over other apps,” start the floating overlay from TradeLens, and allow its persistent notification.
- **Manual OCR is unavailable:** tap the floating button while the trading app is visible and accept the Android screen-capture prompt. TradeLens briefly opens for consent, then returns to the trading app before capturing one frame.
- **APK build fails:** use JDK 17, ensure `gradlew` is executable (`chmod +x ./gradlew`), and verify Android SDK platform 35 is installed. GitHub Actions builds the APK if the Replit environment has no Android SDK.
- **Download fails or pauses:** check the URL and license access. A fresh HTTP error or invalid `Content-Length` removes the partial file; an interrupted transfer can be retried with a Range request when the partial file remains.

## Local model and inference settings

- Default model URL: `https://huggingface.co/google/gemma-2b-it-gpu-int4/resolve/main/gemma2-2b-it-gpu-int4.bin`
- Default filename in app-private storage: `gemma2-2b-it-gpu-int4.bin`
- Default generation: maximum 256 tokens, Top K 40, temperature 0.3.
- Configurable ranges: maximum tokens 64–512, Top K 1–64, temperature 0.0–1.5.
- The downloader uses OkHttp, reports transfer progress, supports HTTP Range resume, checks the server's content length, and removes failed/incomplete files. It tests model loading before setting the model status to Ready.
- Import uses Android's document picker and copies the file into app-private storage before validation.
