# Handoff note: Bubble Translate

This note is for Claude Code picking the project up on Zim's computer. Read it
fully, then carry on from "What to do next".

## What this is

An Android app (Kotlin). A blue bubble floats on top of any app. Tapping it:

1. takes a picture of the screen (MediaProjection),
2. reads the text out of the picture (ML Kit text recognition, OCR),
3. detects the language (ML Kit language ID),
4. translates each text block into the chosen language (ML Kit on-device translate),
5. draws each translation over the spot the original text was in.

Tapping the screen closes the translation. The user wants it for reading
comics, novels and documents in other languages.

## State right now

- All source code is written. Nothing has been built into an APK yet.
- It has NEVER run on a phone.
- Checked: all Kotlin compiles with kotlinc 2.0.21 against the real
  android.jar (API 34).
- Not checked: the ML Kit calls. They were compiled against hand-written
  stand-ins, because the real libraries could not be downloaded where the
  code was written. Treat every ML Kit signature as unverified.
- Not checked: resources (no aapt run), the Gradle build, the GitHub workflow.
- The folder has no git history in the zip. Start a fresh repo.

## What to do next

1. Create a GitHub repo called `bubble-translate` on Zim's account and push
   this folder to `main`. Public is easier: he can download the APK on his
   phone without logging in.
2. The push triggers `.github/workflows/build.yml`. Watch the run
   (`gh run watch`), read the log on failure (`gh run view --log-failed`),
   fix, push again. Repeat until it is green.
3. On success the workflow puts `BubbleTranslate.apk` on the Releases page
   under the tag `latest`. Give Zim that link.
4. Walk him through installing it (allow "install unknown apps" for his
   browser) and the test list below. Fix what he reports.

To build on the computer instead of GitHub: there is no Gradle wrapper in the
repo. Either open the folder in Android Studio, or run
`gradle wrapper --gradle-version 8.11.1` once and then `./gradlew assembleDebug`.
Needs JDK 17 and the Android SDK (platform 34).

## Where the first build is most likely to break

- `Lang.kt`: `TranslateRemoteModel.Builder(lang).build()`,
  `RemoteModelManager.getInstance().isModelDownloaded(...)`,
  `TranslateLanguage.fromLanguageTag(...)`, `TranslateLanguage.getAllLanguages()`.
  If `hasPack` causes trouble, delete it. It only decides whether to show a
  "downloading" message.
- `Reader.kt`: the five recogniser option classes and their packages.
- `Tasks.await(...)` results: Kotlin may complain about nullability.
- Version pins in `build.gradle.kts` and `app/build.gradle.kts`: AGP 8.7.3,
  Kotlin 2.0.21, Gradle 8.11.1, compileSdk and targetSdk 34, minSdk 26.
  ML Kit versions were confirmed as current on 14 Nov 2026:
  text-recognition (and -chinese, -japanese, -korean, -devanagari) 16.0.1,
  language-id 17.0.6, translate 17.0.3.
- The release step (`softprops/action-gh-release@v2`) needs the
  `contents: write` permission that the workflow already asks for.

## Test list for the phone

1. Start asks for "Display over other apps", then notifications, then screen
   access. On Android 14 and up it should offer the whole screen only.
2. The bubble appears, drags, and remembers where it was left.
3. Tap on an English page with target French: boxes land on top of the text.
   If they sit too high or too low by a fixed amount, the problem is the
   overlay window position. See `OverlayView.onDraw` (it subtracts the view's
   on-screen origin) and the window flags in `BubbleService.show`.
4. Tap on a Japanese comic page with "Detect automatically": it should pick
   Japanese. Top-to-bottom text is expected to be the weak case.
5. First use of a language downloads a pack of about 30 MB. Test with
   internet off afterwards: it should still translate.
6. Rotate the phone, then tap again.
7. Lock and unlock the phone: Android ends screen access, so the bubble
   should disappear cleanly and Start should bring it back.
8. A screenshot-blocking app should give "No text found", not a crash.

## Files

- `MainActivity.kt`: the settings screen. Layout is built in code, no XML layouts.
- `BubbleService.kt`: foreground service. Owns the bubble, the capture and
  the translation layer.
- `ScreenGrabber.kt`: VirtualDisplay plus ImageReader. Always holds the newest frame.
- `Reader.kt`: OCR, and working out the writing system by trying the models.
- `Lang.kt`: language detection and translation.
- `Pipeline.kt`: the five steps in order. Throws `Pipeline.Stop` with a
  plain-words message for problems the user should see.
- `Views.kt`: `BubbleView` and `OverlayView` (fits each translation into its box).
- `Prefs.kt`: saved settings.

## Decisions already made, and why

- No AndroidX, no Compose, no coroutines. Fewer libraries means fewer ways
  for a blind first build to fail. Background work is one single-thread
  executor that calls `Tasks.await`. Do not call `Tasks.await` on the main thread.
- No `R` references in Kotlin. Icons are drawn in code.
- OCR models are bundled in the APK (not downloaded by Play services), so
  reading works straight after install. The APK is larger because of it.
- `debug.keystore` is committed on purpose so each build installs over the
  last one. It is a throwaway key, not a secret.
- Only `arm64-v8a` and `armeabi-v7a` are built (real phones, no emulator).
  Add `x86_64` to `abiFilters` if you need to run it in an emulator.
- OCR only covers Latin, Chinese, Japanese, Korean and Devanagari. That is an
  ML Kit limit. `Prefs.UNREADABLE` lists languages it can translate into but
  cannot read.

## Ideas not built yet

- Keep the translation up while scrolling (re-translate on scroll).
- Per-block language detection for pages that mix languages.
- A better engine for manga (vertical Japanese) than ML Kit.
- Long-press the bubble to switch target language without opening the app.

## Working with Zim

- Explain things plainly, at a beginner level. Define a technical term the
  first time you use it.
- Be direct about what is broken or untested.
- No em dashes in anything written for him.
- UI look: black and blue (X dark mode). No gradients, emoji or glass effects.
