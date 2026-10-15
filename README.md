# Koky & Sosy Player

**Android TV • Google TV • Amazon Fire TV** | GPL-3.0-or-later | Independent, TV-focused IPTV player

[![Android build](https://github.com/zenzehar/Koky-Sosy-player/actions/workflows/build.yml/badge.svg)](https://github.com/zenzehar/Koky-Sosy-player/actions/workflows/build.yml)
[![GPL-3.0](https://img.shields.io/badge/license-GPL--3.0-blue)](LICENSE)

Koky & Sosy Player is a TV-remote-first IPTV client with **Live TV**, **Movies** and **Series**. It plays media you provide; it **does not include streams, subscriptions, credentials or channel lists**. The core comes from the open-source [OpenTV](https://github.com/opentvproject/opentv) project, with the copyright and GPL license preserved.

## Install / test

1. Open [Actions](https://github.com/zenzehar/Koky-Sosy-player/actions/workflows/build.yml), select the latest successful run for the chosen branch, and download its **debug APK** artifact.
2. Install the APK on a TV device that permits sideloading. For an in-place upgrade, keep the same signing identity and use a higher Android `versionCode`.
3. Add your own Xtream/M3U/Stalker source, either on the TV or using the **phone setup/management QR** on the same local network.
4. Confirm streams, Live EPG, audio, D-pad navigation, subtitles and VOD resume with your own legally accessible provider.

**Release policy:** `main` represents the **most recently approved milestone**. New development uses a dedicated `feat/` branch and a draft pull request until tested on TV. Compilation is not real-device verification.

## TV controls

| Control | Result |
|---|---|
| D-pad | Move focus. The selected TV channel and the browsing highlight are deliberately separate |
| OK / Select (brief) | Activate the focused button or title |
| Hold OK / Select (~600 ms) | Open the options menu on movie, series, episode and Continue Watching cards |
| Menu button | Also opens those options where supported; not required |
| Visible `⋮` | Alternative for remotes without long-press or Menu support |
| Back from fullscreen Live | Return to the same category/channel in the guide, with that channel's video/audio preview |
| Up / Down in the guide | Browse channels without switching the preview until another channel is chosen |
| Back from VOD | Restore the previous title/season/category context |

Some remotes do not deliver repeat or hold key events consistently. Long-press has a **time-on-release fallback** plus a visible options control. The latest TV behaviour must be verified on Fire TV and an Android TV box.

## Major features

- **Live TV:** channel/group browser, EPG grid or list mode, guide preview, quality variants, favourites, catch-up when supported, recording and optional NAS.
- **Movies & Series:** cached/paged catalogue, six-up TV poster grid, in-place search, detail artwork, seasons, episode thumbnails, audio/subtitles, progress and resume.
- **Continue Watching:** one logical latest point per title, episode label/progress, click-to-resume, hold for Resume / Open title / Reset.
- **Series autoplay:** optional next-episode continuation, including the next season, with correctly updated viewing history.
- **Cast:** on-demand TMDB credit and portrait lookup for **movies and series**, merged with provider-only names. Credits from visited titles are saved into the local catalog; tapping actors searches the library and optionally cross-checks TMDB filmography against existing local titles. Images are lazily loaded and disk-cached. No whole-library API crawl.
- **Phone setup:** local QR-paired browser pages let you type provider details and configure TMDB API key/read-access token without a TV keyboard.
- **Metadata:** provider artwork first, optional TMDB backfill and cast. Missing TMDB data shows a named placeholder, not a fake photo.
- **Privacy:** provider logins and TMDB credentials are device settings, not stored in the GitHub repository. A local pairing token is required for the phone manager. Build-time defaults remain extractable from an APK.

## Build

Requires the Android SDK and Java toolchain documented in `gradle/` and the GitHub Actions workflow.

```bash
./gradlew :app:testDebugUnitTest :app:assembleDebug
```

Built APK: `app/build/outputs/apk/debug/app-debug.apk`.

Optional GitHub Actions **repository secrets**: `TMDB_API_KEY` and `TMDB_READ_TOKEN`. These enable defaults in the APK only when explicitly configured. **Never commit actual secrets, provider credentials, pairing tokens or real playlist URLs.** Compiled defaults are **not confidential**: anyone with the APK can extract them.

## Versions and documentation

- Approved checkpoints: `milestone/v0.1.7-tv-approved`, `milestone/v0.1.8-tv-approved`, `milestone/v0.1.9-tv-approved`. Older checkpoints remain available in GitHub.
- Work in progress: `feat/v0.1.10-cast-menu-live-epg`. Android `versionName` uses `0.1.10-tv-alpha` in this branch; it is not approved until tested.
- See [requirements](docs/KOKY_SOSY_REQUIREMENTS.md), [architecture](docs/ARCHITECTURE.md), [TV regression tests](docs/TESTING.md), [releasing](docs/RELEASING.md), [roadmap](docs/ROADMAP.md), and [changelog](CHANGELOG.md).

## Legal and attribution

Licensed under **GNU GPL-3.0-or-later**, derived from OpenTV. This project does not supply media or authorize access to any stream. Use it only with content you are entitled to view. Original contributors and license notices remain in source files and in [LICENSE](LICENSE).
