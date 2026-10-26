<p align="center">
  <img src="app/src/main/res/drawable-nodpi/soky_tv_banner.png" alt="Soky IPTV Pro TV banner" width="480">
</p>

# Soky IPTV Pro

**A premium IPTV player for Android TV, Google TV, Fire TV and Android phones.**

TV-first navigation · Live TV · Movies · Series · Resume & Continue Watching · EPG · Recording · Private local backups

> **Bring your own legal content.** Soky IPTV Pro is a player only. It does not supply IPTV subscriptions, copyrighted streams, playlists, server logins or TV channels.

## What makes Soky IPTV Pro different?

- **Built for the TV remote:** full D-pad navigation, predictable focus highlights, keyboard only after OK, fast screen transitions and native Android TV launcher support.
- **Live TV and EPG:** categories, guide navigation, channel previews, favourites, schedule and optional recordings.
- **Movies and series:** local and indexed provider catalogues, multilingual title search, metadata, trailers, film details, season/episode selection and artwork.
- **Independent catalogue refresh:** separate home-card refresh controls, accurate last-successful-update timestamps for Live TV, Movies and Series. Continue browsing while a catalogue updates.
- **Reliable native playback:** LibVLC-based VOD playback, seek bar and transport controls, subtitles/audio selection, device wake recovery and TV-centric fullscreen controls.
- **Continue Watching:** per-profile watched position, resume minutes and percentage, latest episode and automatic next-episode continuation.
- **Cast and discovery:** optional TMDB cast information, person search across your own imported titles, cached metadata and thumbnails.
- **Convenient source setup:** Xtream Codes, M3U and Stalker support, login validation and phone-assisted setup on the local network.
- **Backup and restore:** password-protected provider credential backup, TV-friendly restore and optional local/NAS synchronisation.
- **Settings and accessibility:** multiple languages, selected OS keyboard, parental controls, adjustable sync frequencies, robust Back-key handling and TV/phone UI.

## Screens and branding

The S/K logo, adaptive icon, phone launcher icon, TV banner and splash artwork are stored under `app/src/main/res`. The colour palette is coral, cyan, blue and midnight.

## Download, install and test

The **current user-approved gold-standard development baseline** is **[Soky IPTV Pro v0.1.11.54](https://github.com/zenzehar/Koky-Sosy-player/releases/tag/v0.1.11.54-continue-watching-duration-card)** ([direct version-numbered APK](https://github.com/zenzehar/Koky-Sosy-player/releases/download/v0.1.11.54-continue-watching-duration-card/Soky_IPTV_Pro_v0.1.11.54.apk)). This is the latest accepted *development checkpoint*, not a production-signed release or security certification. The older emulator-oriented v0.1.11.48–v0.1.11.50 builds remain withdrawn from the recommended download path. **Do not use diagnostic APKs containing embedded test logins.**

**Important for existing testers:** This full rebrand changes the Android package identity to `tv.sokyiptv.pro`, and therefore installs separately from earlier versions. Before installing, export and verify an encrypted provider login backup from the previous app. Copying providers or viewing history across package identities requires an explicit migration or import; it does not happen automatically.

## TV remote controls

| Input | Behaviour |
|---|---|
| D-pad | Navigate between cards, catalogue sections, refresh bars and player controls |
| OK | Open focused content, activate buttons, or enter text editing |
| Long OK / Menu | Open contextual actions on supported items |
| Back | Dismiss keyboard/controls, then return to the previous screen |
| VOD Up/Down | Autoplay → playback buttons → timeline, one step per level |

## Build from source

Requires JDK 17 and a configured Android SDK.

```sh
./gradlew :app:testDebugUnitTest :app:assembleDebug
```

The debug APK appears in `app/build/outputs/apk/debug/`. A production release requires a permanent keystore and explicit release-signing configuration. Do not ship a debug-signed release as 1.0.

## Privacy and licensing

The player stores IPTV details on your device. It does not require a central subscription service. Optional third-party metadata or add-on services use their own terms and privacy policies. LAN / NAS and Google Drive watch-history sync code remains in the repository but **all history synchronization is temporarily disabled in the v0.1.11.54 gold baseline** for playback-stability isolation. Local Continue Watching and position saving remain active; existing account data and cloud history have not been deleted.

This source distribution includes GPL-licensed material and must preserve applicable copyright and licence obligations. See [LICENSE](LICENSE), [third-party notices](docs/THIRD_PARTY_NOTICES.md), [Privacy](docs/PRIVACY.md) and [Release information](docs/RELEASING.md).

## Development status

**Current user-approved gold standard: [Soky IPTV Pro v0.1.11.54](https://github.com/zenzehar/Koky-Sosy-player/releases/tag/v0.1.11.54-continue-watching-duration-card)**, with the immutable-reference checkpoint branch [`milestone/v0.1.11.54-soky-iptv-pro-golden`](https://github.com/zenzehar/Koky-Sosy-player/tree/milestone/v0.1.11.54-soky-iptv-pro-golden). Exact released commit: `a1820c13aeef716fecc34694c2dd10464d14f634`. Its [CI tests and APK assembly](https://github.com/zenzehar/Koky-Sosy-player/actions/runs/40295591950) passed. Future development should start from this commit on a **new testing branch** without modifying the accepted golden source. The previously approved v0.1.11.12 checkpoint and its [approval record](https://github.com/zenzehar/Koky-Sosy-player/issues/40) remain available as history.

**Scope of the v54 golden:** v53's playback-exit stabilization, Fire TV launcher manifest updates, removed buffering indicators, translucent top-left timer and v54's corrected Continue Watching card display. Google Drive, LAN and NAS progress sync are **paused**; local playback/resume and Continue Watching remain on. No v52 experimental adaptive-buffer/cached-progress features are included. The designation is user-approved and **does not certify physical-device test coverage, secure release signing or production readiness**. The repository `main` branch is the public project homepage, not the gold application-source branch.

