# Soky IPTV Pro — release and milestone procedure

The last approved version is **v0.1.9**, on `main`, preserved as `milestone/v0.1.9-tv-approved`. v0.1.10 lives on `feat/v0.1.10-cast-menu-live-epg` until accepted.

1. Increase `versionCode` and `versionName` for the feature branch (v0.1.10 = 11).
2. Run `./gradlew :app:testDebugUnitTest :app:assembleDebug` through GitHub Actions.
3. Confirm the artifact contains a valid Android APK. Distribute direct APK links only after successful upload.
4. Verify the Fire TV and Android TV scenarios in [TESTING](TESTING.md), especially screenshots, long holds, screensaver audio and Live channel focus. Green CI does not verify hardware behaviour.
5. Obtain explicit approval, merge the feature PR, and create a fixed milestone branch. Preserve older milestones for rollback.
6. Never commit provider logins, TMDB keys, pairing URLs or tokens. Optional `TMDB_API_KEY` and `TMDB_READ_TOKEN` CI secrets protect source control but not extracted APK contents.

---
## Upstream SokyIPTV release history and signing guidance

Releases are cut by pushing a tag. GitHub Actions does the rest.

```bash
git tag v0.1.0
git push origin v0.1.0
```

`.github/workflows/release.yml` runs the tests, assembles the release APK and attaches it to a
GitHub release.

## Signing

**Right now, release builds are signed with the Android debug key.** This is deliberate, and
it is temporary.

An *unsigned* APK cannot be installed on Android at all — the installer rejects it before the
user sees anything, with an error that explains nothing. That would mean no one can test the
app until signing infrastructure exists. So `app/build.gradle.kts` falls back to the debug key
when no keystore is configured, and the result installs fine.

The cost, stated plainly: **when the project moves to a real keystore, Android will refuse to
upgrade over a debug-signed install.** Everyone testing today will have to uninstall and
reinstall, losing their settings. That is fine for a handful of early testers and completely
unacceptable once there are hundreds. **Set up real signing before announcing the project
publicly.**

### Setting up real signing

1. Generate a keystore:

   ```bash
   keytool -genkeypair -v -keystore upload.jks -keyalg RSA -keysize 4096 \
     -validity 10000 -alias sokyiptv
   ```

2. Add these repository secrets under **Settings → Secrets and variables → Actions**:
   `KEYSTORE_BASE64` (the output of `base64 -i upload.jks`), `KEYSTORE_PASSWORD`, `KEY_ALIAS`,
   `KEY_PASSWORD`.

3. That is all — `release.yml` and `build.gradle.kts` already pick them up. The release notes
   will report `signed: yes` instead of `debug-key`.

**The keystore must belong to the project, not to one person.** A single maintainer holding
the only copy is the same single point of failure this project exists to avoid — if they go
quiet, nobody can ship an upgrade, and every user is stranded on whatever version they have.
Share it among at least two maintainers, and back it up somewhere that is not one laptop.

Losing the keystore is unrecoverable. There is no reset.

## Version numbers

Bump `versionCode` and `versionName` in `app/build.gradle.kts`. `versionCode` must increase on
every release; Android uses it, not `versionName`, to decide what counts as an upgrade.
