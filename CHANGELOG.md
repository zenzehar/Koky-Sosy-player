# v0.1.11.6 TV alpha — dashboard VOD bars, backup diagnostics, keyboard control

- User clarified the refresh/date bars belong at the **bottom INSIDE the Movies and Series tiles on HOME**, not the Movies/Series catalogue browser. Removed the catalogue-screen bars and added individually focusable/reloadable bottom strips inside the HOME tiles with last-successful local datetime, refresh icon, running progress and retry indication.
- Existing source-filtered, separate Movies/Series WorkManager refresh implementation retained. HOME tile refreshes the corresponding type for all enabled Xtream providers; never queues Live/EPG or the other VOD type. The regular full update also updates the respective successful stamps.
- **Legacy Movies/Series access block removed**: saved VOD content can be entered and browsed during in-progress global or section-specific updates; in-place indexed Room reads serve saved data. Home cards no longer darken/lock just because a full sync is due/running. Live TV loading remains independent.
- Credential manual export and import now show a visible **Android TV AlertDialog** on failure and success, including an explicit shared Documents backup path on success or permissions / file / passphrase guidance on failure. Export/restore controls remain focusable even when passphrase is too short and report the missing requirement rather than silently refusing. Passphrase uses TV-native click-to-edit field (no keyboard on focus).
- TV text inputs in General, EPG, Parental PIN, Recording storage credentials, and Stremio Add-ons settings use click-to-edit keyboard behaviour. Focus/highlight alone must not launch the IME; OK/click enters editing, Back dismisses.
- The pale pink/white fields on the **right-hand login panel** now use a transparent background when unfocused as well as invisible border. Focused fields keep the pale fill and yellow outline; idle text remains legible in white. No changes to the connection validation flow.
- Preserves approved v0.1.11.0 native VLC engine and seeking behaviour, v0.1.11.5 three-level remote focus, buffering/stall indicators and other working features. Screensaver recovery STILL NOT user-tested; do not claim accepted.
- v0.1.11.6 is a prerelease; avoid uninstall without first checking provider backup/restore, because debug signing identity can still change and Android may not persist file permissions across reinstalls.

# Additional feature — VOD section refresh bars (18 October 2026)

- Movies and Series each show a **separate persistent bottom bar** within the VOD browser, with the last successful provider catalogue update in the device's local date/time, a reload icon, visual D-pad focus, and no grid overlap.
- Clicking bar refreshes **only the visible VOD type** (Movies or Series) and, if a provider filter is selected, only that provider; All providers syncs each enabled Xtream provider of the selected VOD type. **Never** triggers Live TV, other VOD type, episode fetching, or EPG update.
- Refresh uses network-constrained WorkManager work, which survives leaving the VOD screen. Repeated clicks are coalesced; shows progress and a failure/retry message. On success VOD SQL pages and counts are reloaded, and progress/history/metadata/favourites are preserved via the existing phase-specific upsert.
- Dates are stamped **only after a successful VOD phase**, stored separately for each type/source. The existing full refresh worker now stamps each phase as it completes. For an upgraded device without new per-type stamps, the old common VOD update time is shown only while no new type-specific stamps are present.
- Background or manual update failures do not advance the last successful update stamp. Refresh should be done sparingly on large catalogues, not every tab open.
- No change to VLC 3.6.1 playback, approved seeking, screensaver (still untested), or screensaver buffering indicator.

# v0.1.11.5 TV navigation preview — user feedback 18 October 2026

Source: v0.1.11.4 test branch. Approved v0.1.11.0 golden milestone remains untouched.

Real-TV results on v0.1.11.4: user confirmed prior focus fix works but requested exactly THREE levels, single key press up/down: autoplay TOP, icon buttons MIDDLE, seek bar BOTTOM. No buffering stutter recurred; quick spinner at video start was observed. Screensaver recovery not yet tested. Searchable language choice accepted. Credential backup still launched missing system document picker despite shared-folder access. New requests: Watch/Resume must be initial focus when entering movie/series title; login page should not draw white borders on unfocused items.

Implemented:
- VLC: initial center playback button focus; Up center->autoplay and Down center->seek; from autoplay Down returns to buttons; from seek Up returns to buttons. Native seeking/hold logic unchanged.
- Media3: separate seek slider focus target, same three levels. Existing slider callbacks unchanged.
- Catalogue frequency: compact selected-value menu and six options in a remote-friendly dialog, matching language picker.
- Manual backup on TVs with granted broad document-folder access now directly writes/reads passphrase-encrypted Documents/KokySosy/manual-logins.kspc, before considering Android ACTION_CREATE_DOCUMENT/OPEN_DOCUMENT. This avoids a falsely resolvable system file-picker app. If permission is unavailable, SAF fallback is still attempted. No credential plaintext.
- Movie detail retries primary Watch/Resume focus until LazyColumn button attaches; series waits for a playable episode before requesting focus once. Does not refocus after season changes.
- Login idle input boxes hide border completely until focused, including editing state; kind, Restore backup and Test Connection OutlinedButtons use no static white outline. Clears old focus indications when navigating away.
- Keeps v0.1.11.4 spinner instrumentation and screensaver work UNCHANGED. User has not verified screensaver. No codec, VLC 3.6.1, streaming, buffer size or seek policy changes.
- Device-credential automatic restore after uninstall remains BEST-EFFORT due to Android permission reset/signature-bound Android ID; prior beta debug APK signatures may rotate. Manual file recovery on same device still requires passphrase. Do not uninstall sole working credentials without a safe independent backup.

Do not merge/approve before actual TV hardware validation.

v0.1.11.4 TV Recovery Preview (18 October 2026)

User TV findings: D-pad between seek bar and autoplay but not buttons; screensaver interrupt and audio-only resume; Android TV missing file picker; languages clutter settings; recurring stutter.

Native VLC player and seek algorithms are preserved. Focus routing from seek bar Down now takes priority over stale autoplay flag. VLC buffering is indicated with a spinner ONLY on sustained network buffer input; non-buffering clock stall is labelled separately. On dream resume, VLC surface is reattached, and Android keep-awake flags are reapplied. TV firmware may still impose its own screensaver.

Settings languages use a searchable dropdown dialog. The manual encrypted provider backup has a shared-Documents path fallback when Android TV lacks a system document picker. The optional device vault uses a separate encrypted file under Documents/KokySosy and explicit Android All Files Access. Source changes sync to the vault, and startup can restore if the user has permission. Factory reset clears recognized vault first.

SECURITY AND LIMITATION: Android access grants do not survive uninstall. Android ID is signature-scoped and can change with GitHub debug signing. Auto-restore is best effort and MAY REQUIRE renewed permission, and decrypting a vault across signing identity changes may be impossible. The previous release could not pre-create this vault. Do not uninstall your only functioning credentials without a separate recovery plan. No plaintext secrets stored in shared Documents; All Files Access is broad and opt-in. No merge/approval until real device tests.

# v0.1.11.3 TV alpha — cinematic UI refinement and remote navigation

**User acceptance of previous build:** User reported v0.1.11.2 GUI "Perfect!" and requested finer polish, removal of captions/connectors, transparent buttons and accessible autoplay.

**Changes (presentation/navigation ONLY):**
- Removed explanatory text, caption connector stems, and dots under all transport icons.
- Identical 74 x 74 dp rounded-square geometry for every button, 22 dp corner radius, balanced 19 dp gaps and 35 dp crisp white icons. Removed artificially always-emphasized pause frame and shadows outside hitboxes. Focus highlighting is exactly the *button shape* with a thin blue outline, only while actually focused. Pause and episode buttons all follow the same focus rule.
- Button face is only **26% opaque** (74% transparent); focused face no more than 30% opaque (70% transparent). Icons remain legible.
- Reduced transport footprint and moved icon row down nearer the seek timeline without obstructing it.
- Autoplay is a single D-pad focusable *whole pill*, using a semantic Switch role and eliminating the duplicate inner focus target. VLC remote focus now follows transport → Up seek timeline → Up autoplay; Down from autoplay returns to seek timeline; Down from seek returns to transport. Media3 remote Up from transport/seek -> autoplay and Down from autoplay -> central transport button.
- Preserves existing movie vs series control visibility, top-left title, upper-right autoplay visual, blue timeline and original screenshot layout. No extra everyday controls.
- **Absolutely no change** to v0.1.11.0 LibVLC 3.6.1 player creation, seek policy, timing, back/exit lifecycle, decoder/cache, native seek calculations, source URLs, or Media3 seek callbacks.

**Not yet accepted:** Android CI passing does not demonstrate real remote focus or pixel-perfect appearance; user must test. Golden approved source remains unchanged and PR is draft.

# v0.1.11.2 TV alpha — cinematic video player overlay

**Reference:** Exact user-supplied Arabic 16:9 player screenshot, 18 October 2026.
**Baseline:** Branch based on v0.1.11.1, preserving the user-approved v0.1.11.0 golden milestone.

- New shared screenshot-matched cinematic chrome for LibVLC and Media3: upper-left large title (Arabic supported), episode number, pill-shaped time; upper-right dark pill with bright-blue autoplay Switch; middle-low control strip with five dark rounded icon buttons (previous/rewind 10s/central pause/forward 10s/next), white annotation captions and glowing center focus.
- Blue seek timeline with white thumb and elapsed/total labels across the bottom. Native VLC left/right seeking, hold acceleration, seekTo, lifecycle and LibVLC 3.6.1 are unchanged. Media3 slider seek callbacks remain unchanged. Changes are visual layouts only plus existing episode button callbacks.
- Movies show three buttons without episode/autoplay features.
- No embedded movie still: live video remains displayed behind translucent overlay; screenshot is a design reference only.
- v0.1.11.1 provider backup, logo/mobile launcher, splash and Continue Watching remain unchanged.
- Test on a real TV required. No merge or milestone approval yet. Debug APK signing may be incompatible with previous installations.

# v0.1.11.1 TV UI preview — based exclusively on approved v0.1.11.0

## Implemented
- Add missing normal `android.intent.category.LAUNCHER` category while retaining TV `LEANBACK_LAUNCHER`, so the same APK appears in phone app drawers.
- Add a pre-Android-26 legacy launcher vector for minSdk 23 and refresh adaptive foreground, TV banner, and in-app splash with matching original K/S midnight/teal/orchid design.
- Keep the existing brand splash visible until the SourcesViewModel has loaded the saved providers, avoiding the second unbranded single-dot progress screen between splash and home.
- Add manual previous/next episode lookup across season boundaries; show Previous, ±10s, Pause/Play, Next and Autoplay controls in episode playback. D-pad Select/Enter toggles playback while hidden controls/seek timeline are focused. Extra engine/diagnostic actions are removed from the routine VLC transport but remain accessible on VLC error / Settings. Movie playback omits series controls.
- **Do NOT replace or change the approved v0.1.11.0 VLC playback lifecycle, LibVLC 3.6.1 settings, native seeking, or long-hold seeking policy.** Changes to its Compose transport/key mapping are UI behaviour only.
- Add a passphrase-protected AES-GCM provider credential backup file through Android Storage Access Framework. User chooses a file location outside app-private storage and restores it on first-run login or Settings. This avoids retyping provider credentials after uninstall, but **requires** keeping the encrypted file and its passphrase; automatic silent external credentials persistence is not possible across uninstall without third-party backups. No plaintext provider passwords in the external file; tampering/wrong passwords rejected; existing provider entries not overwritten.
- Added backup roundtrip/tamper tests and previous-episode DAO function.

## Pending before user acceptance
- **Control reference photograph is not available as an attachment in this turn.** The controls have been simplified per written requirements, but their final styling must be matched to the photo once reattached.
- **Continue Watching report is ambiguous:** user wrote that a title *had* a resume position but *was* in Continue Watching, which is normal; clarify whether the title was actually **missing** from Continue Watching or showing incorrectly. Do not alter proven resume logic speculatively.
- Credential backup is manual, not a shared silent folder; Android can delete any app-owned folder on uninstall. Android TV file picker availability varies by OS/device.
- New APK signing may still differ from prior CI builds. Do not claim in-place update compatibility.
- Do not merge draft PR or mark new milestone before real device approval.

# v0.1.11.0 TV test — v0.1.9 playback restored, modern features preserved

**Purpose:** User asked to branch the latest feature-rich app, retaining all non-playback enhancements but reverting playback to its last uncontested working version. v0.1.10.3 acceptance was later explicitly withdrawn, so the baseline is **milestone/v0.1.9-tv-approved** at `72347c8dc5102ad3c937fd1a4cfa1f8199506c85`.

## Playback rollback
- Exact v0.1.9 native LibVLC startup, HTTP UA, default **1400 ms** VLC cache, hardware decoder override, event handling, native seek and progress code restored in `VlcFallbackScreen.kt`.
- Embedded LibVLC dependency downgraded from **3.7.7 to 3.6.1**, matching v0.1.9.
- Restored original v0.1.9 D-pad tap/long-press seek policy and matching original tests.
- Kept only compatibility shims required by the newer app: existing `suspendPlayback` screensaver pause parameter, passive read-only buffering/error logging, `Diagnostics QR` capture and explicit standalone VLC handoff. No newer playback watchdog, automatic stop/play recovery, seek coalescing or native configuration overrides are carried into the restored VLC player.
- Modern Media3 backend for Live/DVR/recordings stays intact to avoid unintentionally regressing those subsystems. Xtream stream URL construction and other provider code stays unchanged.
- The v0.1.9 VLC renderer deliberately uses historical **1400 ms** caching, regardless of newer buffer preset UI. That setting remains functional for Media3; this experiment does NOT claim per-profile VLC buffer tuning is active.

## Still retained
- Full modern categories, cast filmography and metadata caching, Continue Watching, per-episode resume database, newest title/episode screens, screen layouts, multi-profile, QR manager, TV remote focus, app settings, Arabic UI, active playback/crash diagnostics and GitHub build pipeline.
- Prior checkpoints and unmerged draft PRs untouched; this is a separate hybrid branch, not a replacement of main.

## Acceptance
- User must test the exact troublesome episode **اللعبة S01E05** without seeking for 10–20 minutes; then repeated +/-10s seeks, long presses, immediate Back, episode auto-continuation and resume.
- Screensaver still **UNTESTED**. This is **NOT** an approved milestone merely because GitHub CI passes.

# v0.1.10.9 TV alpha — repair missing playback diagnostic QR

**Real-TV failure confirmed:** On SDK 34 SDMC HAKO Pro, v0.1.10.8 showed `QR generation unavailable` while LibVLC playback still reported clock stall and one interruption. Source screenshot shows engine=NATIVE, playback duration=1855 seconds, position=0, VLC ready at 11s, clock-stall event at 18s. This is not evidence that the provider or RAM caused it.

**Fix:** Replace untested in-dialog QR bitmap code with the app's already used phone-pairing QR renderer and a separate 480px RGB565/low-error-correction fallback for TVs where the primary code fails. All errors now display a safe exception **class name only**, not raw exception messages (which may contain credentials). Users can choose `Use easier-to-scan QR`; numbered fallback QR parts let the user scan the *entire* 950-character report without silently dropping events. 480px memory-bounded QR image; no extra permissions and no network upload. Add actual Android Bitmap → phone-readable ZXing round-trip tests using the exact report extracted from the user's photo, plus forced fallback and pagination tests.

**Scope:** No LibVLC engine, seek, caching, provider URL or automatic playback changes; preserves native LibVLC 3.7.7 and the report recorder. User should capture diagnostic text after next stall to guide root-cause repair. This remains an unapproved alpha. Real playback and screensaver behaviour still require physical-TV acceptance.

# v0.1.10.8 TV alpha — temporary QR playback/crash diagnostics

**User-requested testing instrumentation**, to be removed from the final stable build, while investigating native VLC buffering on the same provider episode that standalone VLC and IPTV Smarters Pro play smoothly.

- Adds a bounded (18-event) diagnostic recorder including engine, Native/Tuned mode, LibVLC status transitions, buffering percentage, number of rebuffer interruptions, stalled-clock indication, seek targets, Media3 error code, elapsed/total playback seconds, device SDK/board model and rough heap/RAM. Strings are allowlisted. Does not include stream URL (often includes Xtream password), title, account, key, request headers or exception messages. Saved in app-private SharedPreferences; nothing uploaded automatically.
- Adds **Diagnostics QR** in the embedded VLC transport/error controls, **Scan error diagnostics QR** on Media3 error screens, and **Show latest diagnostic QR** in Settings > Playback. A phone camera scanning the on-screen QR receives PLAIN TEXT to paste into ChatGPT; does not require connecting devices or exporting files.
- Registers a process-wide uncaught Java exception handler, preserves a short message-free stack trace and recent event history synchronously before invoking Android's original handler. After a fatal exception, on next app launch the TV automatically shows a QR with the recovered crash report. Impossible to render a QR inside an already-dead process.
- Native abort, force-stop or Android OS process eviction bypass Java handlers. A persisted in-progress playback marker can show **POSSIBLE_UNCLEAN_EXIT=1** on restart; this is not proof of a crash. The user can dismiss, and the previous report stays accessible in Settings.
- Low-overhead and memory-conscious: no screenshot/video recording, no permissions, no background server, bounded log and QR, event-driven persistence with at most one ordinary report per 3 seconds, 500-ms video poll reused without spawning jobs. QR rendering only on demand.
- Existing LibVLC 3.7.7/Media3 playback, caching, D-pad, resume and cast remain unchanged. **Playback still fails previous real-TV validation** and screensaver behaviour remains UNTESTED; v0.1.10.8 is a diagnostic alpha, not a successful milestone.
- Known scope: without privileged logcat/access to network traffic, the QR cannot reveal arbitrary native VLC internals, HTTP status or native stack traces; it is safe structured app-side evidence, not a substitute for Android bugreports.

# v0.1.10.7 TV alpha — embedded LibVLC engine-only comparison

**Failed previous real-TV test:** v0.1.10.6 Native VLC settings repeatedly stalled/buffered (photo: Buffering 8% · interruptions: 2); user also observed playback stalled without buffering. The same episode plays smoothly using IPTV Smarters Pro and standalone VLC on the same TV and network. Our embedded playback implementation is the main suspect.

**One functional change:** upgraded embedded org.videolan.android:libvlc-all from **3.6.1** to stable **3.7.7** (October 2026). This tests whether the newer engine's demuxing, decoder and HTTP internals behave more like the working standalone VLC. Native/Tuned VLC modes and manual external VLC handoff are unchanged. No cache, User-Agent, seek, UI, or provider-URL changes.

**Distribution:** The repo-owned alpha branch's successful GitHub CI attempts to publish an explicitly unapproved prerelease APK asset, so the user can download an APK directly from GitHub. Debug signing remains per-CI and can block in-place upgrades.

**Acceptance:** test the identical troublesome episode in Native VLC for at least 10 minutes without seeking; compare interruption counts and stalls. If failing again, use external VLC for immediate playback and collect redacted input/decoder telemetry. Screensaver remains untested. Draft PR, no merge or milestone until real-TV approval.

# v0.1.10.6 TV alpha — native VLC compatibility and standalone VLC fallback

## Evidence-based scope
The user tested the *same episode on the same TV/network* using IPTV Smarters Pro's internal player and standalone VLC. **Both play smoothly.** Koky & Sosy v0.1.10.5 continues to rebuffer and occasionally stalls. Its Media3 fallback fails with ERROR_CODE_PARSING_CONTAINER_UNSUPPORTED for that episode. Source-stream quality cannot be blamed without further evidence.

## Changes
- Native VLC mode now defaults ON for new and previously unset installations. It allows LibVLC's native user-agent, cache/HTTP handling and hardware-decoder defaults, matching the known-working standalone VLC baseline more closely.
- Preserve configured custom per-source User-Agent explicitly: native mode omits only the generic OpenTV UA. Provider-specific overrides are never silently lost.
- Retain old Tuned VLC mode for comparison: selected network cache + generic/custom User-Agent, hardware decoder override and HTTP reconnect. Settings > Playback allows D-pad switching; applied at the next video.
- Buffer presets remain available for Media3 and Tuned VLC. Native VLC uses its own caching. The absence of a custom cache size in native mode is intentional, not a broken setting.
- Add **Open in VLC app** button to playback and error controls. It passes the URL only to the installed VLC Android package, stops internal playback first to avoid concurrent provider sessions, and leaves the embedded player screen on successful handoff. External app cannot automatically provide our progress/next-episode controls.
- Preserve cast, resume, guide navigation and existing UI with no changes to user data or URL constructions.
- Pure policy tests enforce default vs tuned options and explicit custom UA.
- Do NOT claim verified until real Fire TV hardware testing, including continuous playback and seek/back. Screensaver remains untested. This PR is a draft, not an approved milestone.

# v0.1.10.5 TV alpha — continuous playback interruption mitigation

- User reported cyclic play / pause / play and increasing buffering on v0.1.10.4.
- Raised native VLC caches for Fast/Balanced/Stable/Large to 1.5/5/8/12 seconds respectively; longer caches may increase memory demand and startup delay.
- Added LibVLC native Buffering event telemetry and an unobtrusive count of interruptions, distinct from a timestamp stall when no buffering is signalled. Native event instrumentation does not prove video-frame output.
- Enabled VLC 3.x HTTP reconnect on HTTP/HTTPS VOD media.
- Removed automatic VLC pause/play and stop/play recovery steps during network/seek stalls. These can prolong starvation and release/reinitialise decoder resources while the provider is already reconnecting.
- Added an explicit Retry VLC option and retained Try Media3 fallback on the TV transport controls.
- New unit tests cover native buffering detection, long-stalled playback, and startup/non-rebuffer events. Prior tests and existing cast, resume, and navigation are preserved.
- Physical-device validation and screensaver outcome are PENDING. Not an accepted milestone.

# v0.1.10.4 TV alpha — playback and seek stability audit (UNVERIFIED ON TV)

User withdrew the physical-test approval previously recorded for v0.1.10.3 due to numerous playback/seek faults. The old branch is now a source snapshot, NOT a quality signoff. Never merge this alpha without new TV testing.

- Added four persistent buffering profiles in Settings > Playback: Fast, Balanced (default), Stable, Large. Changes apply when opening the next player. VLC uses bounded network-caching durations; Media3 sets a bounded allocator target (12–48 MiB) and matching playback buffering thresholds. Preview/recording/DVR have separate specialised caps.
- VLC: coalesce seeks to the most recent target instead of sending overlapping native seek commands; protect resume data from unconfirmed seek previews and backwards seeks; prevent runaway repeats in hidden-controls mode.
- More conservative, responsive long-press seeking: still accelerates after the normal 10 s tap, but avoids extreme multi-minute jumps that overload fragile provider seek indexes.
- Allow manually switching to Media3 from the VLC control bar. This is an escape for VLC-specific decoder/seek faults; the alternative may not parse every provider container.
- Media3: 10-second seek increments, bounded allocator size, release guard to avoid duplicate native teardown.
- Avoid eager Media3 controller allocation for ordinary VLC VOD playback; initialise the alternate engine only when selected, preserving native RAM/codec headroom.
- Added unit tests for buffer profiles and remote seek ramp; retaining v0.1.10.3 rollback code unchanged.
- IMPORTANT LIMITATIONS: Device/stream compatibility, decoder freezes despite a moving timestamp, and Fire TV screensaver remain to be physically verified. Buffering cannot correct a provider's defective seek index.

# v0.1.10.3 TV alpha — VLC seek recovery and safer fast-Back

- Move LibVLC native release to AndroidView.onRelease so fast Back during buffering/loading does not free the player while its surface can remain attached.
- Guard LibVLC callbacks and duplicate Back navigation after exit starts.
- Bound frozen-seek handling: nudge decoder at 7 seconds without native-time progress, then restart playback in-place after 17 seconds; do not automatically repeat a known-failed seek.
- Seek previews are not recorded as confirmed viewing history. Media3 view is detached on release and VOD progress snapshots are captured on the UI thread before IO persistence.
- Added pure seek-stall state-machine regression tests. Screensaver test deferred. Physical Fire TV/Android TV validation outstanding.

# v0.1.10.2 TV alpha — cast completeness, resume clarity and stability

- Fixed a significant actor-search gap: TMDB photo credits were displayed but not indexed locally. Now complete cast names from every opened title are merged with provider names and persisted to Room, so actors from the current title are searchable thereafter.
- Increased cast coverage to **up to 60** credited performers per opened title and up to **350 local hits per media type**; provider-only names remain present with a photo placeholder.
- When a person page is opened, optional TMDB filmography supplies additional movie/TV IDs and exact title candidates. Only matches already in the user's local catalogue are returned, via bounded Room database lookups. Credited membership is stored for future use.
- During future Xtream content refreshes, preserve previously enriched cast names, TMDB IDs, plots, title-row IDs, and favourites using one bounded provider-ID query per import batch, so a refresh does not erase the new cast index.
- Cast portrait metadata is bounded to **96 title entries in memory**. Photos use Coil's normal on-demand memory/disk cache; **no mass photo downloads or catalogue-wide TMDB crawl**.
- Fixed a concrete VOD exit race: the final save previously read Media3 player properties on an IO thread *after* releasing the player; this could throw an uncaught wrong-thread/released-player exception on Back. Now final position/duration are snapshotted on the UI thread before release, and only those immutable values are persisted in background.
- Stabilized Series navigation: remember a single episode Room Flow while composing; avoid forcing focus on lazy episode rows while returning from playback.
- Refresh Movie/Series resume after lifecycle resume, showing **minutes watched and percentage** on detail-level Resume actions. Series defaults to the latest unfinished episode and its season.
- Fire TV screensaver behavior **still pending hardware verification**, carried forward unchanged.
- Development-only build: v0.1.10.2, Android versionCode 13. Does not replace user-approved v0.1.9 milestone.

**Limitation:** Exact cause of a hardware-only player-return crash requires an Android logcat stack trace. New navigation guard is a targeted fix, not a claim that all crashes have been reproduced or eliminated.

---
# v0.1.10.1 TV alpha hotfix — unapproved

- Phone Web Manager TMDB API entry: corrected undefined JavaScript helper that stopped event-handler installation; reliable POST-body reading and confirmed saved-status read-back; explicit phone-page success/error output.
- Added an **Enter TMDB from phone** button inside TV Settings → Metadata, linking directly to the Web Manager QR screen.
- Made native remote long holds open action menus **on key release**, preventing the released OK key from also activating a menu item.
- Replaced modal movie/series/episode/Continue Watching action dialogs with anchored TV dropdown menus. Reset confirmation remains available for Continue Watching.
- Movie and Series posters now **always show a readable title** below the image.
- Series details choose the newest unfinished episode as the default season and Play/Resume target; manual season selection still works.
- About now says whether a newer **published release** was found and links to GitHub Actions for alpha builds. Previously-installed versions cannot discover unpublished CI artifacts retrospectively.
- Fire TV screensaver behaviour: **deferred hardware test**, no claim of verification.
- Android versionCode 12, name 0.1.10.1-tv-alpha. v0.1.9 remains the approved checkpoint.

---
# Koky & Sosy Player — changelog

Version names below are Android TV application versions. The earlier **OpenTV upstream changelog** is retained under the historical section for licensing/provenance context; its numbering does **not** mean a newer Koky & Sosy APK is available.

## v0.1.10 TV alpha — development / not yet approved

- TMDB cast portraits for **Movies** and **Series**, with D-pad-accessible person cards that open matching Movies and Series in the local library.
- Enter or replace the TMDB API key/read-access token using the **LAN-only, QR-token-authenticated phone manager**; no credential values are returned by status requests.
- TV remote gestures: brief OK plays/opens; hold OK/Select or Menu opens the relevant title/episode options. Visible options controls remain available.
- Episode-specific Reset progress, and movie/series poster actions. Existing Continue Watching actions retained.
- Pause fullscreen Live audio and video when the device goes to screensaver / screen-off (and on background stop except intentional PiP).
- Rebalanced EPG layout: wider channel text, two-line names, tighter programme timeline, more legible category rail.
- Back from fullscreen Live restores the playing channel in the guide, focuses the channel row, and continues preview until a different channel is explicitly selected.
- Documentation and manual Fire TV/Android TV regression checklist refreshed.
- `versionCode = 11` (in-place upgrade over approved v0.1.9).

**Status:** compile and real-device verification required; do not merge this alpha without approval.

## v0.1.9 TV — approved milestone

- Improved branding, splash/launcher visuals, search descenders, search reset on category changes.
- Richer Continue Watching posters and click/long-hold actions.
- Initial Live return selection and left-hand login focus; factory reset.
- Correct repository-scoped update checker and optional TMDB key/token setup.

## v0.1.8 TV — approved milestone

- Automatic next episode/season setting, saved next-episode resume location.
- Live tile update percentage during imports and VOD lock through final catalogue preparation.
- Compact episode details and series TMDB cast-photo tab.

## v0.1.7 TV — approved milestone

- Six-up VOD grid, simplified category styling, improved focus, updated navigation and in-tile import progress.

## Earlier original OpenTV upstream history (not Koky & Sosy release numbering)

## 0.11.8

- **Edit a saved provider.** Settings → Providers now has an **Edit** button on each provider, so you
  can fix a server address, password, MAC or name in place instead of removing and re-adding it —
  your favourites, hidden channels and per-provider settings are kept.
- **Stalker portals: fewer dropped sessions.** A portal that occasionally rejected a call
  mid-session (channels loading only "sometimes") now gets one automatic re-handshake and retry, so
  an intermittent refusal becomes a silent re-auth rather than an empty channel list or a channel
  that won't tune.
- **Player controls stay clear of the system bar off-TV.** On phones and tablets the bottom control
  bar (subtitles, audio, quality, aspect ratio) could be drawn under the navigation bar and cut off;
  it's now inset above it. No change on Android TV.

## 0.11.7

- **Stalker portals: closer to a real box.** Building on v0.11.6, OpenTV now sends the rest of the
  identity a real MAG set-top box presents at login — the auth token in the session cookie (not just
  the header), a hardware-version hash, a timestamp and the box's API signature — and it probes a
  couple more portal paths. Some Ministra/Stalker panels only return the channel list when they see
  all of this, so lines that connected but showed no channels have a better chance of loading. If a
  portal still comes up empty, please open an issue and say which panel software it runs.

## 0.11.6

- **Stalker portals: full set-top-box identity.** OpenTV now authenticates to Stalker/Ministra
  portals the way a real MAG box does — sending the device serial, `device_id`, a signature and a
  metrics blob derived from your MAC, instead of the MAC on its own. Many portals won't hand over
  the channel list or stream links to a box that only presents a MAC, which is why some lines that
  worked in other apps came up empty in OpenTV. This lets those portals authorise OpenTV. If a
  portal still fails, please open an issue and say which one — it helps a lot.

## 0.11.5

- **Add a provider from your phone or laptop.** The "Manage on phone or laptop" web page now has an
  **Add a provider** section — pick Xtream, M3U or Stalker portal, fill it in with a real keyboard,
  then Test and Add. Channels load straight onto the TV, so you never have to type a server address
  or a MAC on the remote again.
- **Fixed the "camera won't scan it?" web address.** The manage screen was printing that link without
  its access token, so typing it into a browser hit a dead "Not found". It now shows the full working
  URL, and the token is short enough to type.


## 0.11.4

The big DVR release. Recordings behave like Sky Q, you can record straight to a NAS, watch a
recording while it's still taping, and the whole interface now speaks 30 languages.

### Recording & DVR

- **Watch a recording while it's still recording.** Start playing something the moment it begins
  taping — OpenTV reads the growing file straight off disk, so it costs **zero extra connections**
  to your provider. On a single-stream account that's the difference between "wait until it's
  finished" and "watch now". Fast-forward and rewind work within whatever's been taped so far.
- **Record straight to a NAS.** Point recordings at a network share over SMB (Settings →
  Recordings → NAS, or set it up from the phone/laptop web manager) so a cheap box isn't boxed in
  by its own storage — and every OpenTV in the house can reach the same recordings.
- **Single-connection auto-switch.** On a one-stream provider, if a recording is due to start on
  another channel, OpenTV moves the screen onto that recording as it begins — with a **30-second
  warning** first and a *Keep watching* button if you'd rather not. No more "why did my live stream
  just cut out?" (Recordings → Recording behaviour → *Auto-switch when recording starts*.)
- **Clash handling with multi-provider fallback.** Book two overlapping recordings and, if more
  than one of your providers carries that channel, OpenTV records each from a **different provider**
  so neither is cut. On a single provider it flags the clash instead.
- **Recording padding.** Start each recording a minute early and run a few minutes past the listed
  end, so a late kick-off or an overrun isn't clipped. Adjustable per side.
- **Series links show the next episode.** A series link now tells you exactly when it next records.
- **Storage readout.** See how much space recordings use and how much is left.

### Live TV

- **Pause & rewind live TV** *(experimental, opt-in).* Holds the last couple of minutes so you can
  pause and jump back. Off by default — it uses more memory.

### Languages

- **30 languages.** The whole interface is translated into Spanish, French, German, Italian,
  Portuguese, Dutch, Polish, Russian, Turkish, Arabic, Simplified Chinese, Japanese, Korean, Hindi,
  Swedish, Danish, Finnish, Norwegian, Czech, Greek, Romanian, Hungarian, Ukrainian, Indonesian,
  Thai, Vietnamese, Bulgarian, Slovak, Croatian and Persian. Settings → Language (each listed in
  its own name), or leave it on *System* to follow your device.

### Fixes & polish

- Smoother watch-while-recording, especially from a NAS: playback now keeps a buffer behind the
  live edge so a network share doesn't stutter at the write head.
- Back never lands you on a stray live channel or drops you out by accident — coming back from a
  recording returns to your recordings, and Back on the home screen asks before it exits.
- Password fields are masked, with a show/hide toggle.
- Recording stability: NAS write timeouts and reconnect handling, so a quiet network doesn't
  silently freeze a capture.

### Notes

- Everything stays on your device — provider logins never leave the box.
- Free and open source (GPL-3.0): https://github.com/opentvproject/opentv


## 0.10.0

- **Movies & Shows, redesigned.** A Plex-style layout with big artwork, cast & director, "more with
  this cast" rows, and cleaned-up titles (no more "NF -" / "(KR)" junk). Optional: add your own free
  TMDB key in Settings → Metadata and OpenTV fills in any posters, backdrops, cast or synopses your
  provider left blank — the key stays on your device.
- **No more stuck "Loading movies & shows".** The movies/series catalogue is cached, so it loads
  instantly on later launches instead of re-downloading every time — and the live preview no longer
  stutters while it loads.
- **Record to a USB / external drive.** Settings → Recordings → USB, pick a folder on a plugged-in
  drive, and recordings write straight there (no storage permission needed) and play back in-app.
- **Recordings screen overhaul.** Scheduled recordings show *when* they'll record and sit in their
  own "Scheduled" section; failed ones have a **Retry**; each is tagged to a profile; and bookings
  re-arm on every launch so a force-stop or app update can't quietly drop them.

## 0.9.0

- **Manage your channels from a phone or laptop.** A new "Manage on phone or laptop" screen in
  Settings shows a QR code and a link — open it on any device on your wifi and you get a proper web
  page to browse, **rename**, hide, favourite and **reorder** channels with a real keyboard and
  mouse. Changes apply to the TV instantly. Local-only: the TV is the server, nothing touches a
  cloud.
- **Recording keeps going when you leave the app or the box sleeps.** OpenTV can now ask Android to
  exempt it from battery optimisation (Settings → Recording → "Recording in the background"), which
  is what keeps recordings running in standby and when you switch away — swiping OpenTV out of
  recents no longer stops a recording either.
- **Rename channels.** Give a channel your own name; it sticks and survives a guide refresh (from
  the web manager for now).
- **Tidier update notes.** The "update available" prompt shows a clean summary of what's new instead
  of build metadata.

## 0.8.0

- **No more endless "Loading channels".** If a provider fails to load or comes back empty (a wrong
  login, a dead server), the guide now shows a clear error with **Retry** and **Provider setup**
  buttons instead of spinning forever.
- **Set up on a phone without the QR dance.** Installing on a phone now shows the login form
  directly so you can type your details in; the QR code is only offered on a TV, where typing is the
  painful part.
- **Paste into the setup fields.** The URL, username and password fields — and the on-screen
  keyboard — now have a **Paste** button, so a copied Xtream line drops straight in.
- **MPEG-TS or HLS, your choice.** Each Xtream provider now has a **Stream format** toggle. If your
  panel only serves `.ts` and channels wouldn't play, switch to MPEG-TS and they will. Defaults to
  HLS, so nothing changes unless you need it.
- **Parental controls scrolls.** The hidden-categories list is reachable to the bottom now, however
  many categories your provider has.
- **A real Channel Manager.** Settings → Channels is now a browsable, two-pane manager: pick a
  category on the left, see its channels on the right, hide or favourite each one — including
  hidden channels so you can bring them back. With more than one provider, a **source filter** keeps
  them separate instead of merged into one list.

## 0.3.0

- **Profiles.** Add local profiles (just a name — no accounts) from the person icon in the top
  menu, and switch between them. Each profile keeps its own resume points and watched state, so
  your half-watched film doesn't show as watched on someone else's profile. Existing resume points
  become the default "Me" profile's — nothing is lost.
- **Continue Watching.** Movies and Shows now open with a "Continue watching" shelf for the active
  profile — pick up your last film or episode where you left off, with a progress bar on each.
- **Local sync (no servers).** Settings → Sync copies your continue-watching between two OpenTV
  devices over your own wifi: one device shares behind a six-digit code, the other receives. It's
  local-only — nothing leaves the house — the household answer to the cloud sync that died with
  Viewella.

- **Proper player controls.** A single control bar slides up from the bottom with play/pause,
  rewind/forward (where the stream allows it), and pickers for **subtitles, audio track, quality
  and aspect ratio**. It hides after a few seconds and any remote button brings it back —
  nothing is ever left painted permanently over the picture.
- **Captions that actually show.** Subtitles are now chosen from the real tracks in the stream
  (and can be turned off), instead of a blind on/off that often left the renderer enabled but
  nothing on screen. Audio-track switching works the same way for multi-language streams.
- **Page through the guide.** Earlier / Now / Later buttons above the guide move the timeline
  forward and back, so you can see what's on later this evening with a remote.
- **Live preview in the guide.** The highlighted channel now plays, muted, inside the preview
  pane. It uses a single player that stops the instant you go full-screen or leave the guide —
  no second decoder — and it can be turned off in Display & playback for older boxes.
- **Channel manager.** Settings → Channels: search for a channel and hide it from the guide (or
  favourite it). Hidden channels drop out of Live TV and search but stay here, greyed, so you can
  bring them back. Hiding covers every quality variant of a channel at once.
- **Parental controls.** Set a 4-digit PIN and mark categories as hidden — they drop out of the
  guide, All channels and search until you unlock them (and with a PIN set, the parental screen
  itself is locked). In Settings → Parental controls.
- **Unified search with an on-screen keyboard.** Search now has its own screen with a d-pad
  keyboard (no more "type on your phone") and covers **channels, movies and shows** together, with
  live results as you type. Reachable from the top menu.
- **Loading indicators for Movies and Shows.** Both now show a spinner while the catalogue is
  syncing instead of a premature "nothing here".
- **Settings and Search in the top menu.** Both now live top-right, next to Live TV / Movies /
  Shows, instead of being buried behind the guide's icons.
- **A single Settings screen.** One Settings hub — Providers (add / remove / re-test sources), TV
  guide, Display & playback, Parental controls, and About (version, a manual update check,
  licence and links).
- **Dark / light / follow-system.** A new appearance setting. TV still defaults to dark; pick
  Dark or Light to force it on any device.
- **Quality picker only when there's a choice.** No more "Quality: Standard" on single-quality
  channels; the picker appears only when a channel actually has multiple qualities.
- New **Display & playback** settings screen (the sliders icon in the guide).

## 0.2.0

- **Guide detail pane** — highlighting a channel now shows its logo, what's on now with
  start/end times and a live progress bar, a synopsis when the guide carries one, and what's
  on next. Press to watch full-screen.
- **Live progress fill** on the current programme in the guide grid, and a focus highlight on
  the highlighted channel.
- **Fix: never asks for your provider again.** A cold launch could briefly decide "first run"
  before your saved sources had loaded and drop you on the setup screen — it now waits for the
  sources to load before deciding what to show.
- **Fix: no second video decoder on the guide**, which was locking up low-end boxes (a
  Chromecast could freeze). The detail pane is logo + guide info; the one decoder lives
  full-screen.

## 0.1.0

First public release.

- Xtream Codes and M3U/M3U8 sources
- Streaming XMLTV guide parsing with incremental, non-destructive sync
- Live TV playback on Media3/ExoPlayer with IPTV-tuned retry behaviour
- Movies and series catalogue
- Favourites, categories, search
- In-app self-update for sideloaded installs
- Single APK for Android TV, Fire TV, phones and tablets
