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

The retained **Android TV test baseline** is **[Soky IPTV Pro v0.1.11.47](https://github.com/zenzehar/Koky-Sosy-player/releases/tag/v0.1.11.47-v019-native-vlc-restored)** ([direct version-numbered APK](https://github.com/zenzehar/Koky-Sosy-player/releases/download/v0.1.11.47-v019-native-vlc-restored/Soky_IPTV_Pro_v0.1.11.47.apk)). Its CI build passed, but it has **not** received final physical-TV acceptance and is **not** a production release. Later emulator-oriented v0.1.11.48–v0.1.11.50 test builds are withdrawn from the recommended download path. **Do not use diagnostic or experimental APKs containing embedded test logins.**

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

The player stores IPTV details on your device. It does not require a central subscription service. Optional third-party metadata or add-on services use their own terms and privacy policies. LAN / NAS sync and Google account cloud sync are separate optional capabilities; **Google Drive progress sync is available in the experimental v0.1.11.45–v0.1.11.47 line, but is not yet production-certified**.

This source distribution includes GPL-licensed material and must preserve applicable copyright and licence obligations. See [LICENSE](LICENSE), [third-party notices](docs/THIRD_PARTY_NOTICES.md), [Privacy](docs/PRIVACY.md) and [Release information](docs/RELEASING.md).

## Development status

**Current user-approved golden standard: [Soky IPTV Pro v0.1.11.12](https://github.com/zenzehar/Koky-Sosy-player/releases/tag/v0.1.11.12-soky-iptv-pro)** at [`milestone/v0.1.11.12-soky-iptv-pro-golden`](https://github.com/zenzehar/Koky-Sosy-player/tree/milestone/v0.1.11.12-soky-iptv-pro-golden). Exact accepted commit: `4334c26291c8555a0bb37f6a0f6c99f53cf0b5e1`. [Approval record](https://github.com/zenzehar/Koky-Sosy-player/issues/40). This **README on `main` is the public project homepage**, while the named golden branch contains the actual approved application source; do not assume `main` contains the current gold app code. Future fixes and features must branch from that golden commit without modifying the approved APK. Prior golden versions remain archived.

The latest retained non-golden test branch is `fix/v0.1.11.47-legacy-v019-playback-seeking` (draft PR #65), which restores original v0.1.9 native VLC playback/seeking and retains the exit-only Google Drive sync architecture. It is separate from the user-approved golden v0.1.11.12 branch. Outstanding v1.0 checks include playback/seek/exit validation on real TVs, sync regression tests, initial catalogue refresh, phone/TV regression and production signing. This golden designation is user-approved; it does not itself constitute newly completed device tests of the rebrand.

**Soky IPTV Pro is not affiliated with IPTV content providers, television networks or streaming subscription services.**
