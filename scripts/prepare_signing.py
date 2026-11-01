#!/usr/bin/env python3
"""Set up the owner's long-lived Android signing key in an ephemeral CI runner.

The single GitHub Actions secret SOKY_SIGNING_BUNDLE is a base64-encoded JSON
object containing a password-protected PKCS12 keystore. No key or password is
stored in the Git repository. The public certificate fingerprint is pinned.
"""
import argparse
import base64
import json
import os
from pathlib import Path
import re
import subprocess
import sys

ROOT = Path(__file__).resolve().parents[1]
EXPECTED = (ROOT / "signing" / "soky_certificate.sha256").read_text().strip().lower()


def output_configured(value: bool) -> None:
    if path := os.getenv("GITHUB_OUTPUT"):
        with open(path, "a", encoding="utf-8") as out:
            out.write(f"configured={'true' if value else 'false'}\n")


def fail(message: str) -> None:
    print(f"::error::{message}", file=sys.stderr)
    sys.exit(1)


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("--required", action="store_true")
    args = parser.parse_args()
    encoded = os.getenv("SOKY_SIGNING_BUNDLE", "").strip()
    if not encoded:
        output_configured(False)
        if args.required:
            fail("Missing GitHub Actions secret SOKY_SIGNING_BUNDLE. Refusing to publish an APK with a temporary key.")
        print("No permanent signing secret installed. Tests may run, but APK distribution is disabled.")
        return
    try:
        data = json.loads(base64.b64decode(encoded, validate=True))
        assert data["format"] == "PKCS12"
        alias = data["alias"]
        password = data["password"]
        payload = base64.b64decode(data["keystore_base64"], validate=True)
        assert alias == "sokyiptv"
        assert len(password) >= 28 and "\n" not in password and "\r" not in password
        assert len(payload) >= 1024
    except (KeyError, ValueError, TypeError, AssertionError, json.JSONDecodeError):
        fail("Signing secret has an invalid format. Obtain the ONE-LINE value from the private signing kit.")
    # Passwords must be masked BEFORE they are written to runner environment.
    print(f"::add-mask::{password}")
    tmp = Path(os.environ.get("RUNNER_TEMP", "/tmp"))
    key_file = tmp / "soky-permanent-signing.p12"
    key_file.write_bytes(payload)
    key_file.chmod(0o600)
    env = os.environ.copy()
    env["SOKY_KEY_PASSWORD"] = password
    check = subprocess.run(
        ["keytool", "-list", "-v", "-storetype", "PKCS12", "-keystore",
         str(key_file), "-storepass:env", "SOKY_KEY_PASSWORD", "-alias", alias],
        env=env, capture_output=True, text=True,
    )
    if check.returncode != 0:
        fail("Provided keystore or password cannot be read by keytool.")
    match = re.search(r"SHA256:\s*([0-9a-fA-F:]+)", check.stdout)
    actual = match.group(1).replace(":", "").lower() if match else ""
    if actual != EXPECTED:
        fail("Signing keystore certificate does not match the permanently pinned Soky certificate fingerprint.")
    if env_file := os.getenv("GITHUB_ENV"):
        with open(env_file, "a", encoding="utf-8") as out:
            out.write(f"KEYSTORE_PATH={key_file}\n")
            out.write(f"KEYSTORE_PASSWORD={password}\n")
            out.write(f"KEY_ALIAS={alias}\n")
            out.write(f"KEY_PASSWORD={password}\n")
    else:
        fail("This signing setup must run inside GitHub Actions.")
    output_configured(True)
    print(f"Permanent signing key validated: SHA256 {actual}. Key material remains private.")


if __name__ == "__main__":
    main()
