# Google Drive progress sync — per-user TV OAuth experiment v0.1.11.14

This feature lives ONLY on `experiment/soky-iptv-pro-lab-2026-10-19`. Main, the rebrand development branch and all golden milestone branches remain unchanged. Draft PR #39 is not approved for merge.

## One-time developer-only setup

1. Open repository **Settings → Secrets and variables → Actions**.
2. Select **New repository secret** and enter **Name:** `GOOGLE_TV_OAUTH_CLIENT_SECRET`.
3. For its **Secret** value, paste the existing **TV and Limited Input devices** OAuth Client Secret from Google Auth Platform → Clients. Never enter a user password, Drive token, or provider login here.
4. Save. If missing, the Android unit tests and APK compilation may still pass, but the direct APK publishing job deliberately fails to avoid releasing a build with a disabled Connect Google Account button.
5. Re-run the latest GitHub Actions workflow for the experimental draft PR or push a new test commit. The CI build embeds the app-level OAuth configuration automatically.
6. For production, rotate the already-shared test credential in Google Cloud and update this repository secret; ensure the Google OAuth app is **In production** and required consent details are completed.

**Important:** Google's TV OAuth design assumes a distributed app cannot keep client credentials secret. GitHub Actions secrets protect the source repository and build pipeline, not the compiled APK. Extracting the embedded client secret from an APK is possible; security comes from each user's own Google authorisation and individually issued tokens.

## User experience (no developer credentials on any device)

1. Install the test APK.
2. Open **Settings → Sync → Google Drive**.
3. Select **Connect Google account**. No Client ID or Client Secret field exists.
4. Scan the Google authorisation-page QR code with a phone (or open the verification URL manually).
5. Sign in to **your own Google account**, enter the TV's temporary case-sensitive code, and approve Drive's private app-data permission.
6. When connected, press **Sync Now**. The signed-in Google account email is shown if Drive supplies it.
7. Repeat on another TV with the **same Google account** and the same IPTV provider catalogue. Watch on TV A and sync on TV B to verify matching watch position.

Each user authorises their own account independently. Progress is stored in that account's own hidden Google Drive `appDataFolder`; it is not stored in the developer's Drive. Local progress remains available offline, and uploads/merges occur at startup, every two minutes during app use, when leaving the app and on Sync Now.

## Limits and privacy

- The app uploads **only** SHA-256 digests of stream identifiers, profile names, watched time, total duration and timestamps. It does **not** upload IPTV credentials, original provider URLs, subscriptions, NAS recordings or LAN-synchronisation bundles.
- The hash approach requires identical provider stream URLs on each device to match progress. Catalogue content must already be loaded on both TVs.
- Cloud files are per device, enabling merges across two active TVs; the newest timestamp wins.
- When switching to a **different Google account** on the same TV, progress recorded before the account switch is excluded from future cloud uploads to the new Drive. Local watch history, however, remains visible on the device; choose a separate device/app data profile if local privacy between people is important.
- Delete/reset tombstones are not implemented. Local Continue Watching removal can reappear after syncing another device's historical cloud snapshot.
- An Android **phone-specific OAuth client** and native phone sign-in UX remain pending.
- OAuth test-mode refresh tokens can expire after seven days; test users must be on the Google consent screen's allowed list.
- Production signing is required before a public release. Debug signing may prevent in-place updates from other builds.
- Google Drive API has per-user and per-project quotas; this is not unlimited free hosting.
- The current build passes Android CI when confirmed; successful CI does not prove that the Google sign-in or sync works on the user's real TV.

Official references: https://developers.google.com/identity/protocols/oauth2/limited-input-device and https://developers.google.com/workspace/drive/api/guides/appdata
