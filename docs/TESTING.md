# v0.1.10.1 alpha: focused regression checklist

- [ ] Phone QR manager loads; TMDB section shows existing configured/not-set status (no secret).
- [ ] Enter TMDB key or read token from phone; Save updates status to **Saved and verified on your TV**; check TV Settings fields.
- [ ] Test saving only key, only token, both; test blank input, disconnected TV and stale QR.
- [ ] From Settings → Display & Playback → Metadata, select **Enter TMDB credentials from phone** and verify QR screen opens.
- [ ] In Movies, Series, Episodes and Continue Watching, **brief OK** opens title/plays, **hold OK ~600ms** opens anchored dropdown. Releasing must **not** select a menu item.
- [ ] D-pad Up/Down through the open dropdown, OK selects intended action, Back dismisses.
- [ ] Check visible ⋮ action on remotes without Menu/hold, without unexpected navigation.
- [ ] Play mid-season episode, leave playback, reopen series; season selector and main Play/Resume target should show latest unfinished episode. Selecting a different season deliberately starts that season.
- [ ] Verify movie and series poster grids always show non-clipped titles below artwork at TV resolution.
- [ ] On v0.1.9, Check Updates does **not** detect unpublished builds: after v0.1.10.1 the About screen links to GitHub Actions; publishing an APK-bearing GitHub Release is required for prior installations.
- [ ] Cast lookup still finds indexed Movie and Series titles across all configured providers/categories, subject to stored cast metadata and result limits.
- [ ] **Firestick screensaver playback is NOT TESTED; carry forward to later hardware testing.**

Build-verified is not hardware-verified. Never include provider credentials or tokens in logs.

---
# TV test and release checklist

This document covers the **Koky & Sosy Player** branch, not only the original OpenTV app. Passing Gradle tests is necessary but insufficient: UI focus, Fire TV screensaver handling and provider-specific playback require physical-device testing.

## Required devices

- Amazon Fire TV Stick: confirm OK-hold/Menu and system screensaver behaviour.
- Android TV or Google TV box: confirm D-pad focus, long-press and playback.
- Optional touchscreen phone for the **browser-based setup** page only (same LAN as TV).

## v0.1.10 manual regression matrix

| Area | Test | Expected result |
|---|---|---|
| Movie cast | Open a movie with TMDB credits | Actor portraits or named placeholders, no crash on missing data |
| Series cast | Open Cast tab on a series | Same portrait treatment; selecting an actor opens person titles |
| Person page | Select same actor from each section | Their local matching Movies **and** Series appear (subject to metadata) |
| Phone metadata | Open TV's QR manager on phone, save key/token | TV saves credentials; phone GET returns status booleans, not key/token text |
| Remote movie options | Hold OK ~600ms on movie poster | Play / Open title / Reset options; brief OK still opens details |
| Remote series options | Hold OK on series poster | Open episodes / Reset series progress; brief OK opens details |
| Remote episode options | Hold OK on an episode row | Play episode / Reset episode progress |
| Remote alternatives | Try dedicated Menu and visible ⋮ | Options work even if OK repeat events are unavailable |
| Screensaver | Start live stream then activate Fire TV saver or switch display off | Audio/video pause. No background playing. Manual resume after waking |
| EPG | Open category with long Arabic/English names | Names have more width, readable two-line channel names, compact programme timeline |
| Live return | Play channel, Back to guide | Same channel visibly selected/focused, small preview audio/video running |
| Live browse | Up/Down through same category | Highlight moves without retuning until OK selects another |
| Search / stability | Type Arabic, switch categories, issue rapid key sequences | No clipping, stale filters or crash |
| Regression | Resume/autoplay, login, sync, settings, factory reset | Existing approved behaviour preserved |

### Bug reports

Include model, OS build, app version, precise remote key sequence, expected vs observed behaviour, and a redacted `adb logcat` trace. **Remove provider passwords, TMDB tokens, playlist URLs and QR URLs** before sharing logs.

### Verification status

- **Build verification:** GitHub Actions `:app:testDebugUnitTest` and `:app:assembleDebug`.
- **Hardware verification:** User/maintainer tests on actual TV hardware. Do not mark scenarios above passed based solely on compilation.
- **Release approval:** User explicitly accepts the build before merging the development PR or updating the approved milestone.

---
## Legacy OpenTV testing notes

There is no QA department; there is you, your provider and your telly. This page makes that
count. The app logs everything it does, so a bug report with a log attached is usually a fix
within the day — and a "works for me" without one is usually nothing.

## The quick loop (with a Mac/PC on the same network)

```bash
bash test-run.sh        # reinstalls, launches, records to testrun.log, shows a checklist
```

## What to test after any change

| # | Check | Pass looks like |
|---|-------|-----------------|
| A | Quality grouping | One row per logical channel; "N qualities" badge on grouped ones |
| B | Guide feeds | Gear → enable a guide for your country → "matched N of M" reported |
| C | Now/Next | Matched channels show programme + progress bar, not "No guide information" |
| D | Playback | Channel plays; quality switch top-right; switching mid-stream keeps playing |
| E | **Wrong matches** | No channel shows another channel's programmes — the worst bug this app can have. Report the exact channel name |
| F | State survives | Favourites/hidden survive a refresh and an app restart |
| G | Fast switching | Holding channel-change never wedges playback (one tune when you stop) |

## Reading the log

Interesting lines and what they mean:

- `Matcher: N of M channels have a working guide` — the headline EPG number.
- `Feed 'X' failed: <reason>` — a guide source failed; the same text shows in Guide settings.
- `Source N: ... channels` — catalogue sync result.
- `AndroidRuntime: FATAL` — a crash; everything above it in the log is the story.

## Reporting

Open a GitHub issue with: device + Android version, what you did (checklist letter is
enough), what happened, and the relevant chunk of `testrun.log`. **Strip your provider's
URL/username/password first** — logs deliberately never print credentials, but check what
you paste anyway.
