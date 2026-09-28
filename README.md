# Inward Journey — Android TV app

A thin native shell for Android TV that renders the live game fullscreen.
Zero native UI: one fullscreen `WebView` pointed at the game URL, D-pad
friendly, with a built-in capability diagnostic. No game rewrite needed —
every site deploy updates the app automatically; the APK only changes when
the app itself changes.

`~/workspace/tv-app` — scaffolded 2026-09-28. Not yet built or installed.

## What it does

- Fullscreen immersive `WebView` loading `GAME_URL`
  (`MainActivity.kt` — change the constant to repoint it).
- Screen stays on during long sessions; hardware acceleration on.
- BACK walks WebView history, exits only at the root page (you can't get trapped).
- D-pad arrows / SELECT pass through to the page.
- Load failures show a readable on-TV error instead of a blank screen.
- **Diag mode**: set `DIAG_MODE = true` in `MainActivity.kt` and rebuild —
  the TV shows WebGPU availability, WebGL2 fallback, WebView/Chromium
  version, and a baseline fps meter, in big on-TV text. Results also go to
  logcat under tag `InwardJourneyTv`.
- Debug builds enable WebView remote debugging (`chrome://inspect` on a PC).

## Prerequisites

- Android Studio (easiest path — it manages the SDK/Gradle), **or**
- Push this folder to a GitHub repo: the included Actions workflow
  (`.github/workflows/build-apk.yml`) builds a debug APK on every push and
  attaches it as a downloadable artifact. No local toolchain needed.

## Sideload onto the TV

1. On the TV: Settings → Apps → Security & Restrictions → Unknown sources →
   allow your file manager (or enable Developer Options → USB debugging for adb).
2. Get the APK on the TV via any of:
   - `adb connect <TV-IP>` then `adb install app-debug.apk`
   - USB stick
   - "Send Files to TV" app (free on the Play Store)
3. It appears in the app list as **Inward Journey**.

## Before first launch

Update **Android System WebView** from the Play Store on the TV. WebGPU
needs WebView 121+ on Android 12+; the diag page reports the actual version.

## Follow-ups (not in this scaffold)

- **TV perf mode**: if the TV's GPU struggles, add a `?tv=1` low-spec path in
  the game (lower pixel ratio, lighter post) and point the app at it.
  Game-side work — belongs in the game-coding chat.
- **D-pad navigation**: the game currently assumes touch/mouse; menu
  navigation via arrows + SELECT needs game-side input handling.
- **Banner**: `res/drawable/tv_banner.xml` is a placeholder gradient —
  swap in a real 320x180 PNG for the leanback launcher.
- Play Store publishing is unnecessary for personal use; skip it.
