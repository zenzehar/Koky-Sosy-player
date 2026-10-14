# 2026-10-15 TV device regression 01

Observed on an Android television:
1. Movie/episode failed at 0:00 with ERROR_CODE_PARSING_CONTAINER_UNSUPPORTED (Media3).
2. Global search used internal custom keyboard, not OS selected keyboard.
3. Login defaulted to QR code and hid direct TV entry form.
4. Save path persisted provider before auth, and pairing callback navigated to Home irrespective of success.

Corrections staged:
- Replace global Search keyboard with installed OS IME, activated only when pressing OK on focused text field.
- Introduce TV-native text field wrapper, with navigation focus separate from edit focus. Apply to all login fields, including advanced settings.
- Default TV login to direct form, keep phone-assisted pairing optional.
- Validate Xtream/Stalker through real API handshake before any persistence; M3U via HTTP request and actual M3U marker.
- Pairing only leaves login on genuine success.
- Fix the playback unsupported-container case via guarded native LibVLC fallback in follow-up commit, not merely changing the error message.

Testing states must remain differentiated: source written, GitHub build passed, physical TV verified.
