## v0.1.11.2 cinematic player acceptance tests

1. Play Arabic series episode اللعبة S01E05 and open overlay. Match reference: upper-left title/episode/time, upper-right autoplay switch, five lower-center controls with labels, bright-blue progress bar with white thumb at bottom and dark transparent gradient.
2. Remote: press Up/Down to traverse timeline and buttons, left/right tap to seek ±10 seconds, hold to accelerate, center click to pause/resume. Confirm exact original v0.1.11.0 VLC seeking behaviour remains.
3. Prev/Next episode including season boundaries, first/last episode disabled, autoplay toggle persistent. Movies have only three transport buttons.
4. Video plays smoothly for 10–20 min without added stalls; Back and resume work after seek and early startup. Media3 fallback and recordings retain their seek behaviour.
5. Phone in landscape doesn't clip controls; login backup and branded startup still work as in v0.1.11.1.
6. Screenshot match remains subject to real-device visual review; this is not yet approved. Golden branch and original release remain preserved.

## v0.1.11.1 targeted UI/backup regression plan

1. Install on a phone: app icon appears in the normal app drawer and opens the app. Confirm Android TV/Fire TV icon also exists and brand matches splash.
2. Cold-launch with a saved provider: display branded splash until provider database is loaded, then Home directly (no unbranded spinner/dot in between).
3. On an episode, controls show Previous / -10 sec / Play-Pause / +10 sec / Next plus Autoplay toggle. D-pad center on video or seek timeline pauses/resumes. Pressing center while a command button is focused activates that button, and the existing seek D-pad/hold and resume positions behave exactly like v0.1.11.0. Test first/last episodes, across-season navigation, movies without episode controls.
4. Play the known stable episode **اللعبة S01E05** continuously and repeat seeks/Back to verify the restored v0.1.11.0 VLC engine hasn't been disturbed.
5. Export provider credentials with a passphrase of at least 8 characters from Settings to Documents; confirm file is encrypted, cannot be decoded as plain text, and provider login is restorable using file and passphrase. Test wrong passphrase and verify current providers are not overwritten.
6. Before uninstall/reinstall or clearing data, keep backup file in a user-controlled location and record the passphrase. Debug signing changes may block in-place upgrades; don't delete an existing install without first verifying the backup.
7. Return to the user for the absent transport reference photo and whether the Continue Watching item was missing or improperly displayed.
8. Screensaver suppression during playback was accepted at v0.1.11.0 and must remain unchanged.

## v0.1.11.0 hybrid playback rollback test

- Baseline VOD player source: v0.1.9 snapshot `72347c8d` (source rollback); not v0.1.10.3, since its success claim was withdrawn.
- First play **اللعبة S01E05** for 10–20 minutes without seeking. Compare against the repeat buffering/stall failures in v0.1.10.5–0.1.10.8 and the known smooth standalone VLC + IPTV Smarters Pro results.
- Press OK and use Diagnostics QR to send timing and interruptions if any. This diagnostic recorder is passive; the old player still owns native playback. If QR unavailable, retrieve Settings > Playback > Show latest diagnostic QR.
- Next seek +/-10 seconds and long hold to compare with v0.1.9; the old algorithm has a more aggressive hold ramp, so avoid jumping close to end until ordinary playback is confirmed.
- Press Back during early startup, while paused, after seeking, and after a stall. Verify no crashes, correct season/episode selection and saved resume state.
- Smoke-test cast search, full-library search, current categories, guide mini-preview/Live, QR pairing and updated Settings. Media3 buffering choices remain active; historical 1400ms VLC cache is *not* controlled by new buffer presets in this isolated rollback build.
- Screen saver pause path is present but remains untested. Any hardware acceptance must be explicitly reported by user.
- Debug APK may be signed by a different ephemeral GitHub certificate. Do not uninstall current app before backup; keep existing releases/branches as rollback copies.

## v0.1.10.9 real-TV QR diagnostic acceptance

1. After installing, open **Settings > Playback > Show latest diagnostic QR** to test scanning before starting a movie. Scan with phone; result must start `KOKY-DIAG/1`. Do not report credentials (none are intentionally encoded).
2. Reopen the problematic episode **اللعبة S01E05** in Native VLC. When the player freezes, press OK, navigate to **Diagnostics QR** on the second transport row, scan and paste raw decoded text here.
3. If a dense QR does not scan on the phone, select **Use easier-to-scan QR (smaller parts)**. Scan **all numbered QR parts** with Next part/Previous part and paste them in order.
4. If QR generation fails even in fallback, photograph the displayed **QR renderer error class** and the report text; the app must not silently hide the failure.
5. After a sudden app exit, relaunch. A recovered crash or possible unclean-exit QR should appear; otherwise open Settings > Playback > Show latest diagnostic QR to retrieve the persisted previous report.
6. Observe whether playback timer (native VLC reported position) stays at 0 while moving frames are visible; `VLC_STALL` currently tracks the playback clock, **not decoded video frame output**.
7. Check remote focus, Back, menu responsiveness and app memory after scanning. The QR interface must not start an additional provider session.
8. The original intermittent playback remains **unfixed** until physical-device testing proves otherwise. Screensaver still untested. Do not merge draft PR without approval.

## v0.1.10.8 QR diagnostic testing before/after playback faults

1. From the TV app open **Settings > Playback > Native VLC defaults**. Select **Show latest diagnostic QR** to verify the dialog and QR appear. Scan with phone camera. The phone should display raw text beginning with `KOKY-DIAG/1`; copy all of it and paste into chat. Never paste separate IPTV credentials.
2. Play the known troublesome episode **اللعبة S01E05** using Native VLC. If buffering/stalled warnings appear, press OK to show the control bar and choose **Diagnostics QR** on the second row. Scan and copy the complete report; include approximate moment/video position in your chat.
3. If a freeze prevents opening transport controls, press Back; then reopen **Settings > Playback > Show latest diagnostic QR**. Recent rebuffer/stall events were persisted to app-private storage.
4. If the app terminates unexpectedly, relaunch Koky & Sosy. A **Recovered crash diagnostic** QR should appear automatically if an uncaught Java exception was caught. If the OS/native VLC killed the process without Java handler, the report may instead say **POSSIBLE_UNCLEAN_EXIT=1**, which is only an interrupted playback hint. The current report remains accessible in Settings.
5. Also verify Media3 unsupported container error displays **Scan error diagnostics QR**, and scanned text includes numeric Media3 error code.
6. Repeat D-pad seek, stop/back/early-Back checks with QR report dialog dismissal. The scan tool must not itself pause/restart VLC or launch a second provider stream.
7. Report should contain only approved event/OS/heap statistics. It must NOT include stream URLs, HTTP auth, access tokens, or title names. No report is uploaded automatically; it leaves the device only when the user copies/pastes it.
8. After the user reports a reproducible fix, remove recorder, QR dialog, crash hook, Settings/UI buttons and tests before final stable release.
9. Screensaver remains untested. CI build/tests are necessary but do not prove native device playback.

## v0.1.10.7 LibVLC 3.7.7 A/B compatibility test

Previous evidence: v0.1.10.6 using LibVLC 3.6.1 buffers/stalls in-game episode اللعبة S01E05; standalone VLC and IPTV Smarters Pro internal player are smooth on the same TV/network. This points to our embedded player. This build upgrades ONLY the embedded engine, preserving the previous controls and settings.

1. Settings > Playback > **Native VLC defaults**, restart the exact episode **اللعبة S01E05** and watch for **10 minutes without seeking**. Note buffering banner / number of interruptions and whether playback remains smooth.
2. Next test a different movie and episode, normal ±10 second seeks, long holds, resume, next episode, and rapid Back while video is starting.
3. If it still freezes, select **Open in VLC app** from this specific embedded player and verify the exact stream URL handoff plays continuously in standalone VLC. This helps rule out provider URL construction.
4. Installed debug signatures may differ between CI versions: back up profiles/settings/history before any uninstall. Prefer separate-device test if uncertain.
5. Screensaver remains untested. Neither unit tests nor successful APK assembly confirms native playback on the TV.

## v0.1.10.6 standalone-VLC compatibility acceptance

1. Keep the same troublesome episode: **اللعبة S01E05**, which plays smoothly in IPTV Smarters Pro internal player AND standalone VLC, but buffers in our v0.1.10.5 embedded LibVLC. Confirm this evidence before interpreting results.
2. Choose **Settings > Playback > Native VLC defaults (recommended)**, then reopen the episode, avoid seeking, watch 10 minutes. Report continuous time and interruption counter. The mode intentionally ignores manual VLC cache values but retains explicitly customised provider UA.
3. For a controlled comparison, use **Settings > Playback > Tuned VLC**, reopen the exact same episode and test for 5 minutes. Document A/B outcome; avoid concurrent player/provider connections.
4. If Native still freezes, choose **Open in VLC app** from player controls. VLC should launch that episode, and our embedded stream should close before handoff. This is a usable temporary workaround but external VLC does not share native resume/autoplay.
5. Press Back early after playback begins, navigate through seasons, play/seek/exit five times and check saved resume. Test Arabic titles and focus on a remote.
6. If both embedded modes fail and standalone succeeds, inspect the native libvlc version/device decoder, HTTP headers and input stats. Don't blame the provider or simply keep raising cache without evidence. Capture redacted logcat; URLs embed credentials.
7. Screensaver remains UNTESTED. No merge or milestone approval solely on CI.

## v0.1.10.5 continuous playback diagnostic acceptance tests

1. Select **Balanced (5-second VLC cache)** in Settings > Playback, then play one previously stuttering VOD movie/episode for ten minutes **without seeking**. Note the number of freezes and whether the top banner displays "Buffering XX% · interruptions: N". This represents native VLC input-buffer events, not a measurement of network throughput.
2. If freezing persists, switch to **Stable (8-second VLC cache)** and restart the SAME title. Compare startup delay, uninterrupted watch time and native buffering count. Do not assume high buffer is always better for low-RAM Firesticks.
3. If the picture freezes without native Buffering status but displays "Playback stalled without VLC buffering", test manual Try Media3. It may fail with an unsupported container; report the precise error code instead of credential-bearing URLs.
4. Repeat on another provider stream or quality tier if available to isolate stream-specific server bitrate/codec behaviour.
5. Retry VLC only as an explicit user command; during normal playback no watchdog should repeatedly pause/play or stop/play.
6. Run seek / resume / rapid Back regression tests from v0.1.10.4. Check saved position integrity across ordinary and frozen exits.
7. Screensaver remains UNTESTED. Capture redacted logcat around native Buffering/MediaCodec only if further source-level diagnosis is required.
8. GitHub CI passing is NOT hardware-playback acceptance.

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
