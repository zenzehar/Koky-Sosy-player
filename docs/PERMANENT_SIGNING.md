# Permanent Android APK signing for Soky IPTV Pro

## One-time owner action

1. Keep the private ZIP Soky_IPTV_Pro_Private_Signing_Kit.zip safely backed up.
2. Open https://github.com/zenzehar/Koky-Sosy-player/settings/secrets/actions/new
3. Secret name: SOKY_SIGNING_BUNDLE
4. Secret value: copy the ONE complete line from GITHUB_SECRET_COPY_THIS.txt inside your ZIP.
5. Save the secret. Do not commit, paste, or share the key/password.

The workflow reads only this one secret, validates the permanent PKCS12 certificate against signing/soky_certificate.sha256, and signs the APK with a stable key. The public fingerprint is safe in GitHub.

## Package IDs and one-time migration

- TV development app ID is tv.sokyiptv.pro.debug. Keep it unchanged for existing TV testing.
- Production app ID is tv.sokyiptv.pro. This is a separate Android app.
- All future debug APKs must use the same permanent signing key and increasing versionCode to update existing permanent-key-signed debug APKs.
- v85/v86 pre-signing debug builds used temporary GitHub keys. The first permanent-key APK requires a one-time verified backup/uninstall/reinstall/restore (unless the exact old signing key is recovered).
- Never uninstall before confirming a restorable backup. Preserve the working v85 Gold source milestone.
- Permanent signing does not modify player logic, data migration, account validation, or codecs.

## Pipeline guarantees

- scripts/prepare_signing.py validates the owner-provided secret before configuring Gradle.
- scripts/verify_apk_signing.py compares the actual APK signing certificate with the pinned public fingerprint.
- CI can compile external PRs without the secret, but cannot distribute their temporary-key APKs.
- Published release builds are blocked if the secret is missing or the certificate mismatches.

## Privacy

Key, password and secret must remain outside this public Git repository. Back up the ZIP in two private secure locations. Whoever controls this key can sign app updates. Lost private keys generally cannot be re-created.
