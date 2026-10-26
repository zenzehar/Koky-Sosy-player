# Security policy

## Supported downloads

Soky IPTV Pro is experimental software. The current user-approved **development gold standard** is
[v0.1.11.54](https://github.com/zenzehar/Koky-Sosy-player/releases/tag/v0.1.11.54-continue-watching-duration-card), pinned to
[`milestone/v0.1.11.54-soky-iptv-pro-golden`](https://github.com/zenzehar/Koky-Sosy-player/tree/milestone/v0.1.11.54-soky-iptv-pro-golden) at commit
`a1820c13aeef716fecc34694c2dd10464d14f634`.
It passed CI but is **debug signed**, not a production-security certification or physical-TV acceptance.
The previous approved v0.1.11.12 milestone remains archived. Google Drive, LAN and NAS
watch-history synchronization are temporarily disabled in this baseline; local watch history is preserved.

No diagnostics, test login or emulator-only APK should be treated as an approved public distribution.
Do not automatically select the newest release tag for public download links.

## Credential-handling requirements

- Never commit IPTV usernames, passwords, stream tokens, personal provider URLs, Google OAuth client secrets,
  service-account keys, or account refresh/access tokens, in plaintext **or in trivially reversible obfuscation**.
- Do not package provider test logins into debug or release APKs, CI artifacts, test fixtures, QR data or logs.
- Use manual device-local provider input or encrypted device-local backup for diagnostics.
- A public Android app cannot keep an embedded client secret confidential. Use appropriate public-client
  OAuth flows with PKCE where supported.
- Prefer narrow Android/Google permissions. Avoid unnecessary broad file access.
- Verify the actual APK variant and contents before upload; debug builds are not production security releases.
- Protect signing keys and do not distribute a production APK signed with a shared/developer debug key.

## If credentials are exposed

1. Rotate or revoke the affected credentials immediately at the provider. Changing the GitHub branch is not enough.
2. Remove the affected GitHub Release assets/tags and workflow artifacts, and stop the associated workflows.
3. Assume public APKs, commits, PR diffs, forks and downloads may already have been copied.
4. Coordinate Git history remediation with the repository owner; only history rewriting with appropriate follow-up
   can reduce exposure in reachable refs, and it cannot recall external copies.
5. Retest with **new** non-embedded credentials, without publishing the new credentials.

Report vulnerabilities privately to the repository owner; do not post credentials or detailed exploits
in a public issue or pull request.
