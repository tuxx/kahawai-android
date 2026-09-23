# Kahawai — Android

A native Android and Google TV client for [kahawai](https://github.com/iksteen/kahawai) hub.
Kotlin + Jetpack Compose + Media3 (ExoPlayer). Talks only to the hub's 
versioned `/api/v1/*` surface — the same contract the kahawai web UI 
uses, no private endpoints.

> **⚠️ Status: in active development.** Kahawai itself is still in 
> development, and this app tracks the hub API as it changes. Expect
> breaking changes without notice — a client build may stop working
> against a newer (or older) hub until updated. There are no stable
> releases yet.

## Build

```sh
./gradlew assembleDebug
```

First run generates `local.properties` pointing at your Android SDK if
it's missing (`sdk.dir=...`); set `ANDROID_HOME` or edit it by hand if
`sdkmanager` can't find one. minSdk 26, compileSdk 36, JDK 17+ required
(the Gradle daemon needs `JAVA_HOME` pointed at a JDK 17+ install —
AGP 9's built-in Kotlin support won't configure otherwise).

## Run

```sh
adb install -r app/build/outputs/apk/debug/app-debug.apk
adb shell am start -n com.kolktech.kahawai/.ui.MainActivity
```

On first launch the app asks for the hub's address (e.g.
`192.168.1.20:8420` or `https://kahawai.example.com`) — self-hosted, so
there's no default. It defaults to `http://` if you don't specify a
scheme, since a bare `kahawai hub` process serves plain HTTP by default
(`bind` in `kahawai.toml`); put a reverse proxy or ACME cert in front
for a real deployment. From an emulator, the host machine's loopback is
`10.0.2.2`, not `127.0.0.1`.

The app also runs as a Google TV / Android TV app (leanback launcher
entry, D-pad friendly UI).

## What's here

- **Auth**: bearer JWT + rotating refresh token, encrypted at rest via
  DataStore + Tink (Android Keystore-backed AES-256-GCM) — see
  `data/auth/TokenStore.kt`. Supports first-time hub setup via setup
  key.
- **Catalog**: `data/repository/CatalogRepository.kt` — libraries, items
  (browse/search), item detail, children (episodes/tracks), watch
  progress shown on browse screens.
- **Playback**: `playback/CapabilityProfileBuilder.kt` probes the
  device's actual decoders (`MediaCodecList`) rather than hardcoding a
  claim. `ui/player/PlayerViewModel.kt` owns the session lifecycle:
  start → attach a Media3 `MediaItem` (progressive for `direct` mode,
  HLS for `remux`/`transcode`) → report progress every 10s → seek
  (routed through the hub's seek-restart endpoint for HLS sessions,
  since those serve a growing EVENT playlist) → end session on exit.
  Resume/start-over prompts and next-episode auto-advance for series.
- **Subtitles**: text and image-format (PGS/VobSub) tracks both render
  on-device (`ui/player/subtitle/`) — text via Media3, images via a
  Compose-canvas overlay (`ImageSubtitleOverlay.kt`) that composites
  display sets the hub decodes and streams over a tap. Server-side
  burn-in is now only a hub-chosen fallback for tracks it can't deliver
  either way. The CC control follows the hub subtitle list and selected track,
  including ASS/image overlays; direct-play embedded bitmap tracks still use
  Media3's native track selection.
- **Admin & settings**: hub administration and app settings screens
  (`ui/admin/`, `ui/settings/`), reachable from the navigation drawer.

## Known gaps (for now)

- No downloads/offline playback.
- No live catalog refresh via `/api/v1/events` (SSE) — screens re-fetch
  when you return to them.
- HLS seeks always round-trip through the hub's seek-restart endpoint,
  even when the target is already inside what's been produced — always
  correct, just one extra round trip on some seeks.

## Developing against a hub

You'll need a running kahawai hub to point the app at — see the
[kahawai](https://github.com/iksteen/kahawai) repo for how to build and
deploy one. The hub alone is enough to exercise auth, browsing, search,
and session negotiation; actually playing a file also needs a
`mediahost` and, for content that needs it, a `transcoder`.
