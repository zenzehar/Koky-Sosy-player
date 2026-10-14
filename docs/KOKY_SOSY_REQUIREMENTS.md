# Koky & Sosy Player — Android TV development contract

Start: 2026-10-15. Independent repository imported from opentvproject/opentv.
GPL-3.0-or-later derived work. Not based on the older Koky TV/Open-IPTV-Smarters fork.

## Binding product decisions
- Android TV / Google TV / Fire TV first. No phone playback app required initially.
- TV Home MUST fit the display without scrolling. Do not relax this requirement.
- Three main tiles: Live TV, Movies, Series. Adaptive secondary shortcuts.
- TV D-pad and Back navigation must work throughout. Input focus must not open the keyboard until deliberate OK.
- All category initial on first entry in Live/Movies/Series; restore category and focus after detail/playback.
- Three separate searches: Master, selected-category in-place full-catalogue, and category-name search.
- Media3 primary; classified, bounded fallback (e.g., LibVLC) only on genuine unsupported media.
- Seekable VOD: short ±10s; held progressive preview; verify real decoder position. Never simulate success on unseekable streams.
- Series autoplay ON by default, configurable. Proper Next Episode and seamless continuation between seasons.
- Episode thumbnails, season selection and return focus to last-played episode.
- Continue Watching: one card per logical series, latest meaningful continuation; targeted resets and OK-long-press context.
- Keep manual LAN sync. Add optional persistent NAS/cloud relay and QR pairing. Offline devices must not need simultaneous uptime.
- Separate refresh schedules for catalogues, guide and expiry; options Off, 1h, 3h, 6h, 12h, 24h where applicable.
- Preserve settings, profiles, credentials and history. Avoid forceful uninstall/factory reset. Do not publish provider credentials.

## Baseline state in this first commit
- New Android application ID and TV-launcher-first manifest.
- New non-scrolling dashboard linked to existing provider, Live, Movies, Series, Recordings and search routes.
- Native seek target calculation and unit tests are present, but NOT YET connected to remote events or actual playback.
- Inherited provider/recording/EPG/VOD code requires re-auditing; inheritance is not proof of correctness.
- Dashboard footer currently reports expiry as unavailable; must be bound to actual validated provider response later.
- Launcher artwork and complete screens need original Koky & Sosy rebranding.
- Production signing identity not yet installed. CI debug APKs may not update each other.
- No physical TV testing has been performed.

## Priorities
P0: stable build + source management + correct D-pad focus; TV keyboard mode; full metadata index/search;
authenticated playback; real seek; safe Back/player teardown; one-per-series resume; profile integrity.
P1: series thumbnails/autoplay/seasons; accurate account expiry; channel manager; catalogue status;
manual and cloud/NAS sync; factory reset; comprehensive settings.
P2: optional Catch-up, Multi-Screen, cast, PiP where device/provider capable.

## Testing / release honesty
Compilation is not device testing. Model seek-policy tests do not prove real seeking.
Use a small verified test matrix on actual Android TV-capable environments.
Never claim APK exists before GitHub Actions produces an artifact.
Do not merge the feature branch without user request.
