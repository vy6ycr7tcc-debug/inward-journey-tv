# Inward Journey — Android TV Launcher

A tiny, **content-agnostic** TV shell for the *Inward Journey* game.

The app never bundles the game and never needs updating when the game changes.
Instead it downloads the latest build on demand, stores it on the TV, and plays
it locally in a fullscreen WebView.

- Package: `app.inwardjourney.tv`
- `versionCode 5`, `versionName "2.2"` (installs as an upgrade over the previous app)
- `minSdk 28`, `compileSdk 34`, `targetSdk 34`, Java 17
- **Zero external dependencies** — platform APIs only (no AndroidX)

## Distribution (no more manual APK transfers)

- The built APK is committed under `apk/` (one file per version).
- `launcher-version.json` at the repo root advertises the latest build.
- The installed app checks that file on launch and offers a one-tap in-app
  update (via the framework `PackageInstaller` — the system still asks you to
  confirm the install, and the first update asks for the "install unknown
  apps" permission).

**Signing:** every APK is signed with the same local debug key. Keep using
that key for every build published here — Android refuses to install an
update signed with a different key. (There is deliberately no CI build: a
CI debug key would differ and break updates.)

### Release checklist

1. Bump `versionCode` / `versionName` in `app/build.gradle`.
2. `./build-apk.sh` and sanity-check the APK metadata.
3. Copy the APK to `apk/inward-journey-tv-<version>.apk`.
4. Update `launcher-version.json` (`versionCode`, `versionName`, `apkUrl`).
5. Commit and push. The TV picks the update up on next launch.

## How content flows

The game's deploy pipeline publishes two files at a stable URL:

```
https://vy6ycr7tcc-debug.github.io/Animation/version.json   -> {"sha":"<hex>","built_at":"..."}
https://vy6ycr7tcc-debug.github.io/Animation/game.zip       -> zip of the built site (index.html at root)
```

The app:

1. Fetches `version.json` and compares its `sha` with the locally stored SHA.
2. If there is no local content or the SHA differs, downloads `game.zip`
   (showing progress) and unpacks it to internal storage (`filesDir/game`).
3. Serves the unpacked files from disk through
   `WebViewClient.shouldInterceptRequest`, so the page URL stays `https://…`
   but the bytes come from local storage — instant and offline.
4. `version.json` and `game.zip` are always fetched from the network (never
   served from disk) so the update check always sees the live site.

Updates are **atomic**: the new build is unpacked into `game_new`, validated
(must contain `index.html`), and only then renamed over `game`. Any failure
leaves the existing copy untouched.

### Repointing the content source

The content URL is a single constant in
`app/src/main/java/app/inwardjourney/tv/GameUpdater.kt`:

```kotlin
const val CONTENT_BASE = "https://vy6ycr7tcc-debug.github.io/Animation"
```

Change that one line to point the app at a different host/path.

## Screens

- **LauncherActivity** (the `LEANBACK_LAUNCHER` entry point): shows the installed
  content SHA (or "not downloaded"), a **Play** button (disabled until content is
  present), and a **Download / update content** button with a progress bar.
  Downloading new content auto-launches the player.
- **PlayerActivity**: fullscreen immersive WebView that plays the local content.
  It never checks for updates — the launcher owns that.

## Remote mapping

| Remote input            | Action                                              |
| ----------------------- | --------------------------------------------------- |
| D-pad arrows            | Pass through to the focused WebView (game movement / focus) |
| SELECT (tap)            | Click the focused control if there is one (map/menu buttons), else `Space` — the game's action button |
| SELECT (hold ~450 ms)   | `F` — fly                                           |
| Play/Pause key          | `Space`                                             |
| BACK                    | WebView history, else exit the player               |

SELECT taps a focused button via JavaScript rather than a bare `Space`
keypress, because the game's intro handler swallows `Space` while the start
map is open — a bare `Space` never activates the map's buttons.

The WebView is kept focused (`requestFocus()` on init and `onResume`) so the
remote's keys reach the page.

## Building

`./build-apk.sh` builds `dist/inward-journey-tv.apk` with the local
kotlinc + aapt2 + d8 + apksigner pipeline (the Gradle daemon does not run in
this environment, and the project has zero dependencies, so the manual
pipeline is simple and deterministic). The version is read from
`app/build.gradle`; the script generates the `BuildConfig` stub AGP would
normally produce.

```bash
./build-apk.sh
# -> dist/inward-journey-tv.apk
```

## Sideloading to a TV

1. Enable **Developer options** on the TV and turn on **USB debugging** /
   **Network debugging** (ADB).
2. Connect over ADB:

   ```bash
   adb connect <tv-ip>:5555
   adb install -r app/build/outputs/apk/debug/app-debug.apk
   ```

   Or download the APK artifact from the GitHub Actions run and install it with
   a file manager / `adb install`.
3. Launch **Inward Journey** from the TV home screen, choose
   **Download / update content**, and wait for the download to finish. The game
   launches automatically when it's ready.

## Debugging

In debug builds, `chrome://inspect` is enabled — connect a PC over ADB and open
`chrome://inspect` in Chrome to inspect the WebView. Logs use the tag
`InwardJourneyTv`.
