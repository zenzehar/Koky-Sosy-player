# Soky IPTV Pro: performance audit and controlled optimization campaign
**Baseline:** user-approved gold v0.1.11.54, commit `a1820c13aeef716fecc34694c2dd10464d14f634`.
**Working branch:** `perf/v0.1.11.55-gold54-performance-pass1`.
**Status:** code-level optimization pass; results require real-device benchmarking. Do not merge into gold without TV acceptance.

## Scope and constraints
This repository has 163 Kotlin source files in the gold tree. Work examined Compose catalogue browsing,
Room data access, reactive view models, EPG matching, recommendation feeds, HTTP setup, LibVLC lifecycle,
sync scheduling, and build/release setup. The optimization mandate is **no behavior regression**:
preserve local Continue Watching, movie/series identity, resume offsets, selected seek behavior, Live TV
and EPG results, Fire TV launcher metadata, and v54 native VLC teardown. Cloud/LAN/NAS
watch-history synchronization stays **disabled** in this campaign.

## Implemented in pass 1

| Area | Baseline cost | Improvement | Behavioral invariant |
|---|---|---|---|
| Continue Watching | Up to 300 separate movie/episode reads, plus parent-series reads, on each Room emission | One batched movie read, one batched episode read, one parent-series read per provider; bounded inputs, off-main mapping | Newest logical title and correct episode/timestamp still win |
| Reset movie progress | Full `allMovies()` scan including unrelated providers/years | Query candidate movies for the same provider/year only, then apply identical normalized-title filter | Only matching quality variants have progress removed |
| Paged VOD TV browser | `accumulatedList + nextPage` reallocated every loaded title over and over | `SnapshotStateList.addAll` modifies pages in place; list no longer recopied on each fetch | Stable item keys, D-pad selection and page order unchanged |
| EPG matching working set | `SELECT *` instantiated complete Channel records, including streams and artwork, for every guide-match pass | Five-column projection (id/key/three EPG candidate IDs) | Same normalization and candidate precedence |
| EPG matching writes | One Room transaction/observer invalidation per changed channel ID | Collect changes, commit via one `@Transaction` call | Unchanged matched IDs are not written; matching policy unchanged |
| EPG guide grouping | Re-sorted each programme list on every category switch | Reuse `ProgrammeDao.observeWindow`'s `ORDER BY epgChannelId, startUtcMillis`; grouped iteration order is already preserved | Chronological guide entries |
| Unused recommendations on TV VOD entry | Opening the paged VOD browser could separately load and rank the entire movie/series catalogues | Make full-library home-feed ranking explicit opt-in rather than a side effect of opening/refreshing the paged TV browser | Existing on-demand recommendations code remains available |

No database schema migration, playback decoder changes, provider HTTP changes, watch-history
sync reactivation, secrets, credentials, or provider stream URLs were introduced.

### Complexity bounds, not fabricated benchmark results
- **Resume lookup:** O(number of saved items) map/group work, with O(1 + number of represented sources) batched
  database requests instead of O(number of saved items) or more. Current query cap is 300 IDs,
  within the legacy Android SQLite 999-bound-parameter limit.
- **Catalogue paging:** repeated immutable-list append incurred cumulative O(n²/pageSize) element copies
  as pages grew; in-place observable append makes addition O(number of new items).
- **EPG match writes:** one transaction rather than one transaction per changed channel. The number of
  SQL updates is unchanged; the transaction and invalidation overhead is reduced.

These are source-level complexity claims, **not measured frame rate, memory, CPU or elapsed-time gains**.

## Unchanged higher-risk opportunities for later profiling
1. **Xtream JSON imports:** large provider payloads and JSON tree materialisation can inflate peak memory.
   Streaming parsing requires validation against real malformed provider payloads, especially after the
   previous emulator parsing incidents; **do not silently replace the golden importer**.
2. **Deep OFFSET pagination:** SQLite may scan/sort many rows to access late pages; keyset pagination
   needs stable compound sort keys and careful category/favourites/search compatibility tests.
3. **EPG grid:** 20k channels and large programme windows still allocate sizeable lists; profile the
   recomposition rate and memory before changing grouping or window semantics.
4. **Image caches:** investigate poster decode sizes, Coil memory/disk cache hit rates and Fire OS
   low-memory background behavior using physical hardware metrics.
5. **Startup and Live previews:** isolate I/O, decoder readiness and heavy synchronisations with
   tracing; preserve native media start/end/error behavior and Back teardown.
6. **Release security:** CI debug signing, OAuth app-client secret packaging, and obsolete diagnostic
   credential history remain separate production-hardening work. Do not mix a security migration into
   this performance PR without explicit review.

## Repeatable physical device acceptance and measurement
Use the **same account/profile, source, Wi-Fi/Ethernet, TV resolution and 20k+ title catalogue** for
v54 versus the optimization APK, preferably on both Android TV and Firestick.

1. Make an encrypted backup before any change to the installed APK; debug signing may differ.
2. Compare cold launch (5 repetitions), open Movies/Series (5 repetitions), category changes,
   search over 4k and 20k items, and deep VOD scroll (10+ pages).
3. Compare EPG open, switching 10 categories with an already-filled guide, and EPG refresh;
   record whether channels/programmes and favourites remain identical.
4. Test Continue Watching with multiple episodes and movies, one record per title, correct resume
   time and display, deliberate duplicates/completion, reset of quality variants, and unknown duration.
5. Watch VOD 20+ minutes, repeatedly Back out of 20 videos, seek forward/back, put app in
   background and return. Verify no native crash and no Cloud/LAN/NAS sync activity.
6. Record real-device measurements, for example (replace the package id with the one on-device):
   ```bash
   adb shell am force-stop tv.sokyiptv.pro.debug
   adb shell am start -W -n tv.sokyiptv.pro.debug/app.sokyiptv.MainActivity
   adb shell dumpsys gfxinfo tv.sokyiptv.pro.debug reset
   # Interact with one test scenario on the TV.
   adb shell dumpsys gfxinfo tv.sokyiptv.pro.debug framestats
   adb shell dumpsys meminfo tv.sokyiptv.pro.debug
   ```
   Capture launch times, frame-time percentiles/jank, PSS memory and process CPU alongside test
   date, firmware, resolution and library size. App package names/signatures vary by installed build.
7. **Acceptance gate:** pass CI; preserve catalogue counts/selection/back navigation/resume;
   no new fatal playback issues; demonstrate repeatable runtime gains on physical hardware
   before designating a replacement gold standard.

## Rollback
- Approved golden APK/source remain at `v0.1.11.54-continue-watching-duration-card` and
  `milestone/v0.1.11.54-soky-iptv-pro-golden`.
- This branch and its PR are separate; closing the PR does not alter the golden checkpoint.
