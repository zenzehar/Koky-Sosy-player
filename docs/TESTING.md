## v0.1.10.4 playback stress-testing (USER ACCEPTANCE REQUIRED)

Test **without uninstalling the prior version** if signing permits. If Android reports conflicting signatures, do not remove the old app until playlists/settings/history are backed up. Never infer device testing from CI.

1. Start a movie, press Back during spinner/before first frame, repeat 20×. Repeat with series S01E03 and a different season; focus returns without crash.
2. Play 2–5 min, exit, verify resume. Seek forward/back 10 s ten times, and do 1s, 3s and 5s D-pad holds on focused timeline. A single hold must issue ONE native seek on release and never jump ten minutes unexpectedly.
3. While seek/rebuffer is active, send repeated Right/Left/Back quickly. Controls should respond, native seek requests should coalesce, and Back should exit safely. Saved resume must not incorrectly use a preview timestamp.
4. Seek near the end and towards the middle of multiple provider movies and episodes (MP4, MKV, TS/HLS where available). Native VLC seek failure may still need the **Try Media3** button; note if fallback plays/decodes successfully.
5. Pause intentionally for 20 seconds. Seek while paused and resume manually. No background recovery should override user Pause.
6. Switch Settings > Playback buffers between Fast, Balanced, Stable and Large; each selection stays saved after app restart and applies on NEXT playback. Compare start latency, buffering, memory/heat, video/audio and long viewing.
7. On an older/lower-RAM Fire TV, keep playback running for 1 hour, repeat playback/back 30× and monitor memory. No runaway allocation, frozen UI, accumulated player surfaces or increasing startup delay.
8. Smoke test Live preview, guide return, subtitle/audio pickers, cast, Continue Watching and QR management. Screensaver remains UNTESTED.
9. On crash, record **redacted** Android logcat and exact video/provider format. Do not record provider credentials/URLs.

Automated validation: playback buffer preset tests, seek policy tests, existing Android unit tests and debug APK assemble; these DO NOT replace physical video-decoder testing.

## v0.1.10.3 physical Fire TV / Android TV regression plan

- Open a movie/series episode then press Back during loading, before first frame. Reopen and repeat 20 times. No crash and no blank/stuck surface.
- Seek ±10 seconds and long-press seek in both movies and episodes. Verify picture/audio continue and timeline reflects actual native playback.
- Seek to the middle and near end of a long VOD stream. If seek freezes, leave it for 20 seconds: bounded native decoder nudge and restart should recover rather than leave a permanent freeze. Then Back must be safe.
- From a stuck/frozen seek press Back promptly. Reopen the title; verify no lingering player or crashed navigation.
- Manually Pause a title and ensure no automated stall recovery starts while paused.
- Check Media3/local/recording playback and VLC HTTP/HTTPS title return, saved resume data, focus/season restoration and previous cast-search behavior.
- Screensaver outcome remains UNTESTED / DEFERRED.

# v0.1.10.2 TV acceptance and regression tests

1. On Firestick play a series episode. Back to title, re-enter episode, Back again at least 10 times. Repeat with Media3 and VLC fallback and with another season. No crash; original title/season navigation intact.
2. Test long/rapid D-pad sequences (Up Up Down Back Right), hold OK and returning from fullscreen. Focus must be visible, no duplicate navigation and no crash.
3. Resume a Movie around 10 minutes in: Movie detail Resume must show actual minutes and percent. Resume again later; values should update on return.
4. Resume an episode from season 2: Series title selector and main Resume must default to season 2, episode number and actual time/percent; manually choosing season 1 should still work.
5. Open a title, choose an actor. Source title must appear on Person page even if the provider originally supplied no cast field. Reopening should work without re-fetching local cast names.
6. Check actor common titles across all local Movies/Series categories and configured providers, including items with known numeric TMDB IDs and exact film/show titles.
7. Verify the cast photos are lazy-loaded, smooth with D-pad navigation, and missing photos show named placeholders. TMDB calls shouldn't bulk-load the whole catalogue.
8. Test after app relaunch: persisted cast credits must still appear in Person results (profile-independent).
9. **Firestick screensaver remains UNTESTED**; don't mark it passed based on build or emulation.

Without a logcat stack trace, a physical-TV crash cannot be marked conclusively resolved. Collect a redacted adb logcat around a failed Back for follow-up; exclude credentials and provider URLs.

---
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
