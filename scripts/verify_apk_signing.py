#!/usr/bin/env python3
"""Fail if a publishable APK is unsigned or signed with an unexpected key."""
from pathlib import Path
import os
import re
import shutil
import subprocess
import sys

ROOT = Path(__file__).resolve().parents[1]
EXPECTED = (ROOT / "signing" / "soky_certificate.sha256").read_text().strip().lower()


def main() -> None:
    if len(sys.argv) != 2:
        sys.exit("Usage: verify_apk_signing.py /path/to/file.apk")
    apk = Path(sys.argv[1])
    if not apk.is_file() or apk.stat().st_size < 1024:
        sys.exit("Missing APK: " + str(apk))
    sdk = Path(os.getenv("ANDROID_HOME") or os.getenv("ANDROID_SDK_ROOT") or "/opt/android-sdk")
    candidates = sorted(sdk.glob("build-tools/*/apksigner"), reverse=True)
    apksigner = shutil.which("apksigner") or (str(candidates[0]) if candidates else None)
    if not apksigner:
        sys.exit("apksigner missing; cannot verify permanent APK signing.")
    result = subprocess.run([apksigner, "verify", "--print-certs", str(apk)],
                            text=True, capture_output=True)
    if result.returncode != 0:
        sys.exit("APK signing verification failed: " + result.stderr[:600])
    matches = re.findall(r"Signer #\d+ certificate SHA-256 digest:\s*([a-fA-F0-9:]+)",
                         result.stdout)
    actual = [item.replace(":", "").lower() for item in matches]
    if actual != [EXPECTED]:
        # Certificate digests are PUBLIC. Never print keys or passwords.
        print(f"Expected public certificate SHA256: {EXPECTED}", file=sys.stderr)
        print(f"Actual APK signer certificate SHA256: {actual}", file=sys.stderr)
        if not actual:
            print("No matching signer field found. Relevant APK verifier lines:", file=sys.stderr)
            for line in result.stdout.splitlines():
                if "Signer" in line and "certificate" in line:
                    print(line[:200], file=sys.stderr)
        sys.exit("APK signer does not match the permanent Soky certificate.")
    print(f"Verified permanent APK signing certificate SHA256={EXPECTED}")


if __name__ == "__main__":
    main()
