# Install and configure Koky & Sosy Player

Repository: https://github.com/zenzehar/Koky-Sosy-player

## Install

1. Open the [Android build workflow](https://github.com/zenzehar/Koky-Sosy-player/actions/workflows/build.yml), select a **successful** branch run and download its APK artifact.
2. Enable TV sideloading and install the APK. Preserve the same signing identity for upgrade installs.
3. Enter your own Xtream/M3U/Stalker details on TV, or use the login-from-phone QR on the same network.

## Cast photographs and TMDB setup by phone

Open the app's **TV Manager / Manage from phone** screen and scan the temporary QR with a phone on the same trusted LAN. Expand **Cast photos and TMDB metadata** on the phone page. Enter a TMDB API key or API Read Access Token and tap Save. The phone receives confirmation, never the previously saved credential. On movie or series detail screens, cast photos appear when available from TMDB; otherwise named placeholders appear.

## Remote-control actions

Use brief OK to open/play, hold OK about 600 ms (or use Menu/visible options icon) for title and episode actions. After fullscreen Live, Back restores the same channel in its guide category. Up/Down browses without switching the small preview until another channel is selected.

## Source development

```bash
./gradlew :app:testDebugUnitTest :app:assembleDebug
```

Generated APK: `app/build/outputs/apk/debug/app-debug.apk`.

Approved checkpoint: `milestone/v0.1.9-tv-approved`. Current preview: `feat/v0.1.10-cast-menu-live-epg`. Do not merge until real TV testing and explicit approval.

Licensed GPL-3.0-or-later. Derived from [OpenTV](https://github.com/opentvproject/opentv) with attribution preserved.
