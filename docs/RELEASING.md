# Soky IPTV Pro — release and milestone procedure

## Approved baseline and experimental work

- Latest user-confirmed Gold Standard: v0.1.11.85, main source and milestone/v0.1.11.85-GOLD.
- v0.1.11.86 is an unapproved performance test candidate; do not promote without real Android TV validation.
- Always increase Android versionCode for each update. Keep user data and package ID stable.
- Run automated tests and assemble on GitHub; then test real Fire TV and Android TV before approval.
- Preserve an immutable reference/milestone to the previously approved working version.

## Permanent APK signing

Follow [PERMANENT_SIGNING.md](PERMANENT_SIGNING.md) for the one-time owner secret setup.

**The workflow NEVER publishes an APK signed by GitHub's disposable debug key.**
Without the single SOKY_SIGNING_BUNDLE secret, PR builds may compile for tests, but cannot distribute or publish their APK; tagged release builds fail closed.

The public expected signing certificate SHA256 is in signing/soky_certificate.sha256. Its private key is never in the repository.

- TV test/update builds: tv.sokyiptv.pro.debug (same package ID as v85/v86).
- Production builds: tv.sokyiptv.pro (different app; can coexist).
- Sign both using the same owner-controlled permanent key; verify the final APK certificate before publishing.
- Future updates require the same signing identity and a higher versionCode.
- The first migration from ephemeral-key v85/v86 requires a verified app-data backup and one-time reinstall/restore, unless the original private debug signing key is recovered.

## Release steps

1. Integrate and TV-test the desired feature branch, then merge into main after approval.
2. Bump versionCode and versionName in app/build.gradle.kts.
3. Publish a tag matching the approved release name. The release workflow runs tests, assembles signed TV-update and production APKs, checks their certificate, then publishes a GitHub release.
4. Confirm both APKs are downloadable and that upgrade from the immediately previous permanently signed build retains settings and Continue Watching.
5. Do not commit provider credentials, client tokens, passwords, the keystore, or GitHub secret values.
