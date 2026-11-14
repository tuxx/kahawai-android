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

## Versioning

Starting with `0.0.26-rc.1`, app releases use the same version numbers
as the [kahawai](https://github.com/iksteen/kahawai) hub: app
`X.Y.Z-rc.N` goes with hub `X.Y.Z-rc.N`. Before that, the app was
numbered on its own (`0.1.x`, up to `0.1.43`).

That makes the version number drop once, from `0.1.43` to
`0.0.26-rc.1`. Android still treats it as an update, because the build
number underneath keeps going up. Updaters that compare version names
(Obtainium and the like) may think `0.0.26-rc.1` is older and not offer
it. If yours does that, install the `0.0.26-rc.1` APK by hand once,
over the existing app (no need to uninstall; login and settings stay).
Later releases update normally from there.

When the app needs more than one release for the same hub version, the
extra ones get a fourth number: `0.0.26-rc.1`, then `0.0.26-rc.1.1`,
`0.0.26-rc.1.2`, and so on. These sort correctly as versions
(`rc.1` < `rc.1.1` < `rc.1.2` < `rc.2`), so updaters see each one as
newer. All of them work with hub `0.0.26-rc.1`.

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
- **Home**: continue-watching and up-next rows plus a row per library.
- **Catalog**: `data/repository/CatalogRepository.kt` — libraries, items,
  item detail, children (episodes/tracks), watch progress shown on
  browse screens, mark watched/unwatched. Library browsing and search go
  through the hub's unified `/catalogue/search` endpoint (search covers
  every library in one request, items only for now). Multi-season shows load complete summaries
  from `/items/{show}/seasons`, then page `/seasons/{season}/items` for the selected
  season. Covers use the authenticated hub image cache; specials are `0` and
  unnumbered episodes use `absolute`. Single-season shows keep their flat list.
  Older hubs fall back to child-group summaries and show posters. Season images
  refresh on page entry so artwork arriving after a backfill becomes visible.
- **Artwork**: title logos, backdrops, episode stills and cast headshots
  where the hub has them, each with a fallback (blurred or cropped
  poster, season art, plain title text, initials), since the hub serves
  these from its cache only.
- **Playback**: `playback/CapabilityProfileBuilder.kt` probes the
  device's actual decoders (`MediaCodecList`) rather than hardcoding a
  claim. `ui/player/PlayerViewModel.kt` owns the session lifecycle:
  start → attach a Media3 `MediaItem` (progressive for `direct` mode,
  HLS for `remux`/`transcode`) → report progress every 10s → seek → end
  session on exit. HLS sessions serve a growing EVENT playlist: a seek
  inside what the hub has already produced stays local, anything beyond
  it goes through the hub's seek-restart endpoint. Resume/start-over
  prompts, next-episode auto-advance for series, chapter marks on the
  seek bar, and a skip button for intros/outros (optionally automatic).
- **Subtitles**: text and image-format (PGS/VobSub) tracks both render
  on-device (`ui/player/subtitle/`) — text via Media3, images via a
  Compose-canvas overlay (`ImageSubtitleOverlay.kt`) that composites
  display sets the hub decodes and streams over a tap. Server-side
  burn-in is now only a hub-chosen fallback for tracks it can't deliver
  either way. The CC control follows the hub subtitle list and selected track,
  including ASS/image overlays; direct-play embedded bitmap tracks still use
  Media3's native track selection. The chosen subtitle track is
  remembered per title in the account's hub preferences.
- **Settings**: device-local app settings (`ui/settings/`), reachable
  from the home menu: seek step sizes, auto-skip, player gestures, notch
  handling, and changing the hub server. Hub administration and the
  account's hub-wide preferences are left to the web client.

## Known gaps (for now)

- No downloads/offline playback.
- No live catalog refresh via `/api/v1/events` (SSE) — screens re-fetch
  when you return to them.
- Search lists titles only; the hub can also return album artists and
  songs, which the app doesn't ask for yet.

## Developing against a hub

You'll need a running kahawai hub to point the app at — see the
[kahawai](https://github.com/iksteen/kahawai) repo for how to build and
deploy one. The hub alone is enough to exercise auth, browsing, search,
and session negotiation; actually playing a file also needs a
`mediahost` and, for content that needs it, a `transcoder`.
