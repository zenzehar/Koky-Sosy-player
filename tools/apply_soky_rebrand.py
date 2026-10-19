#!/usr/bin/env python3
"""One-time source rebrand for Soky IPTV Pro.

Idempotent: designed to run on a clean worktree from the approved golden source.
Preserves original upstream COPYRIGHT and LICENSE notices required by GPLv3.
Does not rewrite archived Git history, release tags, APKs or user device data.
"""
from pathlib import Path
import re
import shutil

ROOT = Path(__file__).resolve().parents[1]
NAME = "Soky IPTV Pro"
REPLACEMENTS = [
    (r"Soky IPTV Pro", NAME),
    (r"SOKY IPTV PRO", "SOKY IPTV PRO"),
    (r"Soky IPTV Pro", NAME),
    (r"SOKY IPTV PRO", "SOKY IPTV PRO"),
    (r"Soky IPTV Pro", NAME),
    (r"tv\\.kokysosy\\.player", "tv.sokyiptv.pro"),
    (r"app\\.sokyiptv", "app.sokyiptv"),
    (r"SokyIptv", "SokyIptv"),
    (r"SokyIPTV", "SokyIPTV"),
    (r"SOKYIPTV", "SOKYIPTV"),
    (r"sokyiptv", "sokyiptv"),
    (r"SokyIPTVPro", "SokyIPTVPro"),
    (r"SOKY_IPTV_PRO", "SOKY_IPTV_PRO"),
    (r"soky_iptv_pro", "soky_iptv_pro"),
    (r"soky-iptv-pro", "soky-iptv-pro"),
    (r"SOKY", "SOKY"),
    (r"IPTV PRO", "IPTV PRO"),
    (r"Soky", "Soky"),
    (r"IPTVPro", "IPTVPro"),
]
SKIP_DIRS = {".git", ".github", ".gradle", ".idea", "build", ".kotlin", "node_modules"}
TEXT_EXT = {".kt", ".kts", ".xml", ".json", ".md", ".html", ".js",
            ".toml", ".yml", ".yaml", ".properties", ".pro",
            ".txt", ".py", ".gradle", ".sh", ".bat", ".gitignore"}
KEEP_COPYRIGHT = re.compile(r"Copyright \\(C\\) 2026 The OpenTV Contributors")

def clean_text(text):
    for pattern, replacement in REPLACEMENTS:
        text = re.sub(pattern, replacement, text)
    # The author/copyright identity is legally immutable despite new UI branding.
    text = text.replace("Copyright (C) 2026 The OpenTV Contributors",
                        "Copyright (C) 2026 The OpenTV Contributors")
    text = text.replace("The OpenTV Contributors",
                        "The OpenTV Contributors")
    return text

def is_skipped(path):
    return any(part in SKIP_DIRS for part in path.parts) or path.name == "LICENSE"

count = 0
for path in list(ROOT.rglob("*")):
    if not path.is_file() or is_skipped(path.relative_to(ROOT)):
        continue
    if path.suffix.lower() not in TEXT_EXT and path.name not in {"gradlew", "gradlew.bat"}:
        continue
    try:
        original = path.read_text(encoding="utf-8")
    except (UnicodeError, OSError):
        continue
    updated = clean_text(original)
    # Structured Android identifier must remain distinct from the former brand.
    if path.name == "build.gradle.kts" and "namespace =" in updated:
        updated = re.sub(r'namespace = "[^"]+"',
                         'namespace = "app.sokyiptv"', updated)
        updated = re.sub(r'applicationId = "[^"]+"',
                         'applicationId = "tv.sokyiptv.pro"', updated)
    if path.name == "BrandSplash.kt":
        updated = updated.replace("SokyIPTV logo", "Soky IPTV Pro logo")
        updated = updated.replace('Text("SOKY IPTV PRO",', 'Text("SOKY",')
        updated = updated.replace('Text("PLAYER",', 'Text("IPTV PRO",')
    # Display-friendly exact brand in the UI, not concatenated internal code names.
    if path.suffix.lower() == ".xml" and "res/values" in path.as_posix():
        updated = updated.replace("SokyIPTV", "Soky IPTV Pro")
    if updated != original:
        path.write_text(updated, encoding="utf-8")
        count += 1

# Rename Java/Kotlin source directories after updating package declarations.
for top in ("app/src/main/java/app", "app/src/test/java/app", "app/src/androidTest/java/app"):
    old = ROOT / top / "sokyiptv"
    new = ROOT / top / "sokyiptv"
    if old.exists():
        new.mkdir(parents=True, exist_ok=True)
        for source in old.rglob("*"):
            if source.is_file():
                dest = new / source.relative_to(old)
                dest.parent.mkdir(parents=True, exist_ok=True)
                shutil.move(source, dest)
        shutil.rmtree(old)

# Old and new Room schema source exports need to match new database FQCN.
schema_root = ROOT / "app/schemas"
if schema_root.is_dir():
    old = schema_root / "app.sokyiptv.data.db.SokyIptvDatabase"
    new = schema_root / "app.sokyiptv.data.db.SokyIptvDatabase"
    if old.exists() and not new.exists():
        shutil.move(old, new)

renamed = 0
for path in sorted(ROOT.rglob("*"), key=lambda x: len(x.parts), reverse=True):
    if not path.is_file() or is_skipped(path.relative_to(ROOT)):
        continue
    newname = clean_text(path.name)
    # Kotlin types now also get consistent filename changes.
    if newname != path.name:
        dest = path.with_name(newname)
        if not dest.exists():
            path.rename(dest)
            renamed += 1

# Do not leave an out-of-date splash logo on the rebranded activity.
splash = ROOT / "app/src/main/java/app/sokyiptv/ui/BrandSplash.kt"
if splash.exists():
    txt = splash.read_text()
    txt = txt.replace("R.drawable.ic_sokyiptv_logo", "R.drawable.soky_splash_icon")
    txt = txt.replace("R.drawable.ic_soky_logo", "R.drawable.soky_splash_icon")
    splash.write_text(txt)

print(f"Soky IPTV Pro rebrand applied to {count} text files; renamed {renamed} file basenames.")
