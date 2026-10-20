# Google Drive watch-history sync — experimental TV test

This isolated experiment is **not merged** into the mainstream Soky IPTV Pro development branch. It uses the existing local playback history and never uploads provider URLs or credentials. It is not yet approved on a real television.

## First-run instructions

1. Install the `v0.1.11.13` experimental Android TV APK, retaining your previous working version and encrypted provider backup.
2. Open **Settings → Sync → Google Drive**.
3. Because the temporary Google TV OAuth Client Secret is intentionally **not bundled with or committed to GitHub**, enter your **test** client secret in the masked field, then choose **Save test configuration**. It stays on-device encrypted by Android Keystore. Do not share it in screenshots.
4. Select **Connect Google account**. On your phone, open the verification URL shown on TV and enter the **temporary Google user code**. Approve access to the private application folder.
5. Return to TV and wait for **Connected**. Press **Sync Now** to test the initial round trip.
6. Open the same Google account in another TV running the identical test APK. Both boxes must have the same IPTV provider catalogue; the URL-derived SHA-256 hashes require identical provider stream URLs.
7. Play a movie or episode on TV A, watch at least two minutes, pause or leave the app. On TV B choose **Sync Now** and verify the resumed point.

## How sync works

- Google OAuth TV device code login using Drive `drive.appdata` permission.
- Tokens and test client secret are encrypted locally via Android Keystore.
- Cloud content is **only** profile name, hashed media key, time position, total time and last update timestamp.
- Own device maintains a dedicated `soky-progress-v1-<device-id>.json` file; downloads every device snapshot, merges newest clock timestamp, uploads its own snapshot if changed.
- Sync runs on app foreground startup, every two minutes during active use, and on app background/exit; Sync Now is available on demand. Network failures do not block playback. Offline changes remain in the Room database.
- It does not attempt to sync provider passwords, streams, subscriptions, favourites, NAS recordings or Live TV.

## Known experimental limitations

- **Deletes and watch-history reset tombstones are not yet synchronised.** Removing Continue Watching locally can be undone by another device's older cloud snapshot.
- Files are hidden in Google Drive `appDataFolder`, not visible in My Drive. Other Google accounts cannot see them.
- Match requires *identical provider stream URLs*, after a local catalogue import on each device; changes to URLs may prevent a match.
- TV Client Secret is entered once per test device. TV distributed clients cannot reliably keep secrets; rotate the test secret before public distribution. Never commit the secret.
- **Native Android phone OAuth sign-in is not implemented** yet. This build is TV-only cloud sign-in.
- Google OAuth apps still in Testing mode may require reauthorisation after around seven days; ensure your Google account is listed as a test user.
- Debug signing and differing app package IDs can prevent upgrading from earlier builds. Export encrypted credentials before uninstalling any installed version.
- Android background restrictions may prevent immediate upload after force-killing the app; wait for the next foreground sync.
- Real TV validation and secure production signing are still required.

## Cloud data privacy

Stream URLs can embed passwords, so the existing LAN/NAS `SyncBundle` is NOT uploaded. A SHA-256 digest of the complete local media URL is used as an opaque match key. This reduces accidental disclosure but does not make all watch-history metadata completely anonymous.

Google Drive API official docs:
- https://developers.google.com/identity/protocols/oauth2/limited-input-device
- https://developers.google.com/workspace/drive/api/guides/appdata
