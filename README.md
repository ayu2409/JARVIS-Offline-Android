# JARVIS Offline for Android — v2.0

An offline-first Android personal assistant. It has no API-key fields, no internet permission, no cloud backend, and no account requirement.

## Features in this build
- Futuristic Jetpack Compose dashboard, chat, notes and settings.
- Local commands for time, date, saving/searching notes, and opening selected installed apps / Android Settings.
- Android text-to-speech for spoken replies (voice availability depends on installed language data).
- On-device speech recognition for push-to-talk where supported.
- Optional on-device generative AI using a compatible MediaPipe `.task` model imported from phone storage.
- Optional “Hey JARVIS” foreground wake-word service using Android's on-device speech recognizer only. It keeps a persistent notification and requires microphone permission. After hearing the wake phrase, tap the notification to continue speaking.
- Notes and chat history stored in the app's private SQLite database.

## Important limitations
- This source project is not itself an APK. This workspace does not contain Android SDK/Gradle, so it cannot produce or verify an installable APK here.
- The wake-word listener requires Android 12+ and an installed on-device speech recognition service with an offline English (India) language pack. Some manufacturers do not provide one. Android may stop microphone services due to battery policies. The service is intentionally user-started and displays a persistent notification.
- The wake phrase is English (“Hey JARVIS”). After activation, command recognition asks compatible Android 14+ speech services to detect English (India) or Malayalam (India); actual mixed-language transcription depends on the phone speech service. TTS tries Malayalam voice for Malayalam text and falls back if unavailable.
- The app does not include a large language model. To enable local generative AI, import a compatible MediaPipe `.task` model through Settings. Model files can be hundreds of MB or larger, are not bundled in the APK, and must be obtained separately. Google currently marks the MediaPipe LLM Inference API as maintenance-only and recommends LiteRT-LM for new projects; this MVP uses the documented MediaPipe runtime as a compatibility path.
- Without a model, the built-in offline command engine remains available, but it is not a general-purpose LLM.
- Android restricts arbitrary control of other apps and protected settings. JARVIS only launches supported apps/settings and uses OS-approved intents.

## Build without owning a computer
This repository includes a GitHub Actions workflow at `.github/workflows/android-apk.yml`. You can upload the project to a GitHub repository from your phone's browser, then run the workflow to build a debug APK in GitHub's cloud runner and download it to your phone from the workflow Artifacts section. GitHub account and internet access are needed for this cloud build; no API keys are needed. Do not add secrets.

## Build locally (if Android Studio is available)
- Android Gradle Plugin 8.7.3
- Kotlin 2.0.21
- Java 17
- compileSdk 35, minSdk 26, targetSdk 35

Open the `JARVIS` folder in Android Studio, allow Gradle sync, and build `app` → `assembleDebug`.

## Privacy
- No INTERNET permission is declared.
- No API keys or provider configuration are present.
- Microphone use is opt-in. Wake listening runs as a foreground service with a persistent notification.
- Local notes and chat history remain in app-private storage and are removed if app data is cleared/uninstalled.
