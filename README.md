# Inward Journey — Ambient TV

A native Android TV app for calm, fullscreen ambient video. Two surfaces:

1. **Leanback browse** — pick an ambient channel from the TV launcher; the
   video plays fullscreen with D-pad controls.
2. **Daydream (screensaver)** — when the TV idles, the app plays ambient
   video instead of the stock screensaver. Select it under
   **TV Settings → Screen saver**.

Hard rules: **no WebView, no YouTube embeds, no ads, no analytics, no
tracking.** Playback is direct MP4 over HTTPS via Media3 ExoPlayer. The app
is a shell — every video URL comes from the catalog, nothing is hardcoded.

| | |
|---|---|
| applicationId | `app.inwardjourney.tv` (unchanged from the old launcher) |
| versionName / versionCode | `3.0` / `11` (old launcher was 10) |
| min / target / compile SDK | 28 / 34 / 34 |
| Stack | Kotlin, Jetpack Leanback, Media3 ExoPlayer, Coil, Coroutines |

---

## How content works (catalog)

On every cold start the app fetches `catalog.json` from this repo's `main`
branch:

```
https://raw.githubusercontent.com/vy6ycr7tcc-debug/inward-journey-tv/main/catalog.json
```

Fallback order when the fetch fails (offline, 404, bad JSON):

1. the last cached copy in internal storage,
2. the bundled `app/src/main/assets/catalog-default.json` (one channel
   labeled **Demo**, a stable public sample MP4 — its only job is proving the
   playback pipeline end-to-end).

### Catalog schema (v1)

```json
{
  "version": 1,
  "updated": "2026-10-07",
  "channels": [
    {
      "id": "sunrise-lake",
      "title": "Sunrise Lake",
      "description": "Still water at first light.",
      "cardImage": "https://example.com/thumbs/sunrise-lake.jpg",
      "videos": [
        {
          "id": "sunrise-lake-01",
          "title": "First light",
          "url": "https://example.com/video/sunrise-lake-01.mp4",
          "durationSec": 600
        }
      ]
    }
  ],
  "screensaver": { "channelId": "sunrise-lake", "shuffle": true }
}
```

- `channels[].videos[].url` — absolute HTTPS URL of the MP4 (the R2 public
  URL). This is the only place video URLs live.
- `cardImage` — thumbnail for the browse card; empty/missing falls back to a
  bundled gradient placeholder.
- `screensaver.channelId` — which channel the Daydream plays; missing id
  falls back to the first channel. `shuffle` randomizes multi-video channels.
- Malformed channels/videos are skipped, not fatal — one bad entry can't
  blank the app.

## Publishing a video (owner workflow)

1. **Render** per the video spec below.
2. **Upload** the MP4 to the R2 bucket's public bucket and note its public
   HTTPS URL.
3. **Add the entry** to `catalog.json` on `main`: a new channel object (or a
   new video inside an existing channel) with the R2 URL. Commit — the next
   cold start picks it up. No app release needed.

---

## Content & experience direction

The app is the shell; the videos carry the experience. Whoever produces the
videos should hit this bar:

- **Why video, not realtime:** the TV's GPU cannot carry the live WebGL world.
  The performance strategy is pre-rendered video: tightly defined, slow
  camera paths — never an open explorable world.
- **Aesthetic:** ethereal calm. Deep blues and golds with ember accents,
  slow breathing motion, drifting motes/particles. Contemplative, points
  inward, never flashy. The content is the star; app chrome stays minimal
  and dark.
- **Channels (examples — owner finalizes):** ambient loops drawn from the
  game's places, e.g. Sunrise Lake (home), Island of Mind, Island of Body,
  the Densities cinematics. Each channel is one mood, looped seamlessly.
- **Interface behavior:** true 10-foot UI (large type, high contrast,
  D-pad-first), on-screen UI fades out after a few seconds of idle during
  playback, any keypress brings it back. This is a lean-back product.

### Video spec (for producers)

- **Codec:** H.264 **High profile**, 1080p, MP4 container
- **Audio:** AAC stereo, muxed into the MP4
- **Length:** 60–600 s, **seamless loop** — clean loop point, no hard cut at
  the seam
- **4K/HEVC:** opportunistic. The device may decode it, but never *require* it
  — 1080p H.264 is the compatibility target.
- **Audio direction:** the Inward Journey water bed under everything at low
  gain (~0.12, as in the Voices app ambient toggle) with natural fades at
  loop seams — never a jarring restart. A small sound library (a handful of
  tracks, auto-advance) may back channels that have no bespoke audio.

---

## Self-update (version.json)

On cold start the app fetches:

```
https://raw.githubusercontent.com/vy6ycr7tcc-debug/inward-journey-tv/main/version.json
```

shaped as:

```json
{ "versionCode": 11, "versionName": "3.0", "apkUrl": "https://…/inward-journey-tv-3.0.apk" }
```

Flow:

1. If remote `versionCode` **>** installed `BuildConfig.VERSION_CODE`, a
   D-pad friendly dialog offers the update.
2. On accept, the APK is downloaded to cache and handed to the system
   installer via `FileProvider` (`REQUEST_INSTALL_PACKAGES`).
3. No update / fetch failed / parse failed → silent, browse screen still
   loads.

**Releasing a new version:**

1. Bump `versionCode`/`versionName` in `app/build.gradle.kts`.
2. Build the APK, attach it to a GitHub Release.
3. Update `version.json` on `main`: new `versionCode`, `versionName`, and an
   `apkUrl` that **actually serves the APK** (the URL in this repo is a
   placeholder until v3.0's APK is published).

Devices on older `versionCode`s will prompt on next cold start.

## Build

Requirements: JDK 17+, Android SDK with `platforms;android-34` and
`build-tools;34.0.0` (set `ANDROID_HOME` or `local.properties` `sdk.dir`).

```bash
./gradlew assembleDebug     # APK: app/build/outputs/apk/debug/app-debug.apk
./gradlew lintDebug         # lint gate
```

Install over adb: `adb install -r app/build/outputs/apk/debug/app-debug.apk`

Regenerating the banner/icon (drawn programmatically — no designer assets):

```bash
java tooling/GenArt.java app/src/main/res
```

## Signing note (important)

Debug builds are signed with a debug key. If the APK is signed with a
**different key** than the old launcher still installed on the TV, the install
fails with a **signature mismatch**. In that case: uninstall the old
**"Inward Journey TV"** app on the TV first, then install — same
applicationId, `versionCode` 11 > old 10, so it upgrades cleanly once
signatures match.

## What the app never does

No WebView, no YouTube, no ads, no analytics, no tracking, no cleartext HTTP,
no credentials or bucket names in code. The two endpoints it knows are the
raw GitHub `catalog.json` and `version.json` files; everything else —
videos, thumbnails, screensaver choice — comes from the catalog.
