# Soky IPTV Pro: rebuild after confirmed v0.1.11.21

## Evidence and nonnegotiable baselines

- Physical TV validation by owner: **v0.1.11.21 works**, while v0.1.11.25 and the supposed rollback v0.1.11.26 both freeze/crash after Movies or Series titles and category totals render, often on D-pad interaction. Multiple devices reproduced this.
- v0.1.11.26 was rebuilt from **v0.1.11.23**, not v0.1.11.21. Therefore it was *not* a true rollback to the last working version.
- Tag v0.1.11.21 points at commit `c9e6b997f1ffed3886f14ba1741e4544e0e913f7`.
- Tag v0.1.11.22 points at commit `a56caf6fb530b6a6ba1ca030f844741446f2891e`. 21->22 introduced background LAN management, substantial watch-history and cloud-index rewrites, and a full-catalogue `ProgressMediaIndex.build()` inside the VOD `continueWatching` flow. This is a credible ANR source but is **a hypothesis**, not a verified root cause.
- v0.1.11.27 baseline reuses the **exact v21 runtime source**, with only version metadata and separate release CI changed. Preserve this branch and v0.1.11.12 golden.
- Do NOT cherry-pick or wholesale transplant files from .22–.26. This plan documents behaviors, not code to copy.

## Scope of required changes (in priority order)

**Stage 0: working recovery and reference evidence**
- Publish a verified direct-install v27 APK and check both TVs: Movies and Series load, categories show counts, D-pad Up/Down/Left/Right, OK on categories, titles and episodes, Back, media playback, Live, Continue Watching, Google login and existing basic sync. Do not clear app data.
- Compare performance and run unit tests. Preserve this tag permanently as fallback.

**Stage 1: watch-history semantics, independent of transport**
- Current *active* profile only on the receiving TV. Do not copy, match, create, rename, or overwrite other profiles.
- Exactly one **visible Continue Watching card** per logical movie or series; latest timestamp wins even if newer episode finished. Retain prior resume for different titles. Never resurrect stale earlier series episodes after completion.
- Across matching catalogue entries, use stable provider/stream identity (hash only; no password, IPTV URL, stream credential in remote JSON); safely skip ambiguous identifiers. No blind title-only matching.
- Validate and cap timestamps, offsets, media durations, payload sizes and item counts. Merge only newer records and never overwrite a newer local update.
- **Critical TV perf guard:** Do not build, sort, hash or materialize entire Movie/Episode catalogues in any Compose ViewModel flow, VOD screen entry, focus callback or main-thread event. Preserve exact v21 UI behavior. If an index is needed, isolate on IO, bound/snapshot work, cache/invalidate it and test at 10k/50k episodes.

**Stage 2: LAN Share/Receive**
- Pair with a six-digit code once; store only the scoped device token. All HTTP transfer endpoints require that token after pairing, a private-network remote address, strict byte and time bounds, safe JSON decoding, and cancellation.
- Bidirectional exchange: receiver uploads its bounded current active-profile history and downloads a fresh peer snapshot. Server imports in an IO coroutine. Do not block the acceptance loop/main UI; never collect a full catalogue while the user moves focus.
- Pairing persists as requested. Reconnection/discovery must not continuously flood the network; cached peer IP probing should complement broadcast discovery. Allow manual pairing/address entry.
- Leaving Settings must NOT dispose the persistent, opted-in listener; disabling Share must revoke/stop it.
- Simulate a disconnected peer and a peer running the older protocol. Report meaningful failure and preserve history.

**Stage 3: Google Drive multi-device**
- Continue using the user's own Google account authorization, no IPTV credential uploads. Preserve compatibility with .21 encrypted OAuth storage and existing Google file content.
- Per-user Google account sync, active local profile, logical latest position; safe cross-device merge, no duplicate series cards.
- Google Drive is the preferred transport **when it actually succeeds**. LAN may run only on Drive failure or when unavailable, without falsely marking successful sync.
- Exactly **one scheduler/coordinator** responsible for each heavy cloud reconciliation. No recursive triggering between LAN WorkManager, Drive foreground task, lifecycle and screen ViewModels. Coalesce concurrent events with one gate; add minimum interval and cancellation.
- Background survives navigation; WorkManager is at least 15-minute periodic and Android can defer it. Do not claim guaranteed realtime when process is killed.
- Account switch boundary, historical file safety, duplicate snapshots and clock skew must have tests; deletion requires tombstones before promising synchronized removals.

**Stage 4: Settings/focus and safe NAS placement**
- Tab order: **Share | Receive | Google Drive | NAS** (NAS at far right). No other navigation/layout changes unless required for the feature.
- High-contrast TV focus border and distinct focused state for each actionable element in sync menu. Keyboard opens only on OK; don't regress existing D-pad browsing throughout the app.

## Release discipline and acceptance

1. Each stage has its own **draft PR** based on the last user-tested good version, a unique versionCode, CI tests and separate unapproved prerelease. Avoid stacking multiple unvalidated stages.
2. Confirm published GitHub release asset exists before providing direct download URL. Do not claim compiled/released based on queued CI.
3. Repeat v21-style smoke test on **both physical devices** before proceeding to a new stage. Verify watching progress across devices with Drive connected, Drive disconnected, and different active local profiles.
4. On freeze or crash, obtain Android `logcat` crash/ANR and timing, never blindly patch several unrelated components. Record a *specific* hypothesis, a *single change*, and A/B result.
5. Existing golden v0.1.11.12 and source reference v0.1.11.21 must never be overwritten or merged with unapproved changes.
6. Never claim 100% reliability or Netflix equivalence before tested; deliver what is verified and flag outstanding gaps. Do not remove required open-source attribution.

## First measured risk from .22

The old .22 implementation calls `ProgressMediaIndex.build(graph)` when the VOD Continue Watching flow emits. That method loads *every* movie and episode, computes multiple SHA-256 hashes per row, and allocates candidate indexes. Compose `mapLatest` upstream defaults to collector context unless explicitly dispatched, and can starve UI on large catalogs. The new implementation should never depend on this algorithm in a VOD screen. Evaluate this as a hypothesis with runtime measurements rather than copying either its code or any attempted hotfix from .24/.25.
