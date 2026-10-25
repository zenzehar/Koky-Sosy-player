# Soky IPTV Pro — Android 14 Xtream API probe (one-off)

**Package**: `tv.sokyiptv.responseprobe`. Not an update to `tv.sokyiptv.pro.debug` or `tv.sokyiptv.pro`. It does not link to or change the original app's Room database, settings or cloud sync.

## Reuse credentials in the shared Documents folder

The original Soky application stores optional encrypted backups in `Documents/SokyIPTVPro/`:
- `device-logins.kspc`: automatic **device/signature/app-identity-bound** vault.
- `manual-logins.kspc`: passphrase-encrypted user backup.

Open this diagnostic app, choose **Import encrypted backup**, navigate to the file using Android's system document picker. It tries the original app's documented device-vault derivation (both production and debug IDs). This succeeds **only if original and diagnostic installs derive the same Android ID** (same device/user/signing key) and the vault exists. Android's sandbox prevents directly reading the original app's private data.

If device-vault decryption fails, use **Soky settings → Provider backup → Save manual backup to shared device folder**, choose a backup passphrase, and open `manual-logins.kspc` in the probe with that passphrase. No credentials are written to the diagnostic app's disk.

## Manual provider entry (new in Diagnostic D2)

Choose **Or enter Xtream provider manually** and provide the Xtream **server URL (including port)**, username, password, and optionally the same User-Agent you use in Soky. Tap **Use manually entered provider**, then **Run read-only Xtream API probes**. There is **no backup requirement** for this option.

The app accepts HTTP or HTTPS, including a plain `hostname:port` and an optional known `/player_api.php` suffix. Login details must be in their **own fields**, not embedded in the URL. The information lives in process memory only; the form clears after selecting the provider, and the app never writes provider credentials to storage, reports, Android logs or clipboard.

This APK has the **same independent diagnostic package ID** as D1 and upgrades it if installed, but it **does not replace Soky IPTV Pro**.

## Tests

- `player_api.php` account endpoint (do not confuse cached expiry with an authentication success)
- `get_live_categories`
- `get_live_streams`
- `get_vod_categories`
- `get_vod_streams`
- `get_series_categories`
- `get_series`

Reports HTTP status, response content type, redirection, number of bytes sampled, JSON top-level structure or unexpected HTML title. **Never prints or logs credentials, query strings, provider URL or unredacted response**. If HTML is detected, inspect its sanitized/escaped first 24KiB inside the app, or open the sanitized HTML sample using a browser. Requests use the imported source's User-Agent and same Xtream API paths as Soky. D3 examines complete responses: up to 2MiB using Soky's exact Kotlinx lenient JSON parser, and larger catalogues using a memory-bounded streaming JSON validator (maximum 64MiB per request). The first 32KiB prefix is no longer considered proof of valid JSON. Raw JSON is never written to shared storage or a diagnostic report.

This probe is an opt-in diagnosis utility, not an alternative player. Delete it when finished. It never attempts to reuse private Android app data or silently bypass scoped storage.

## D3 validation interpretation

The main app runs `kotlinx.serialization.json.Json { ignoreUnknownKeys = true; isLenient = true }`. D3 tests that exact parser for complete JSON bodies up to 2MiB, and reports whether Android's `org.json` disagrees (useful for distinguishing a D2 probe false positive). Larger catalogue bodies are parsed with Android's bounded streaming `JsonReader`, whose tolerance can differ slightly from Kotlinx; those findings are labelled separately. All error details are safely classified without logging response text or credentials. Do not infer validity of large catalogues from their prefix alone.
