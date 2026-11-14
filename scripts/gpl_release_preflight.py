#!/usr/bin/env python3
"""Capture local GPL release *evidence* for a Chimera Forge APK.

Read-only except for the ignored JSON report it writes. Never publishes
anything; this is NOT a complete corresponding-source bundle or legal approval.
Requires a completed local build with third_party/arcana and third_party/forge.
"""
from __future__ import annotations

import argparse
import datetime as dt
import hashlib
import json
import os
from pathlib import Path
import subprocess
import sys


ROOT = Path(__file__).resolve().parent.parent
DEFAULT_ARCANA = "f6a149cb202587e5aa8128191beb630a7d94935d"
DEFAULT_FORGE = "583714b8bfbd9cb04f8bc39ad95861190caf7691"


def git(path: Path, *arguments: str) -> str:
    result = subprocess.run(
        ["git", "-C", str(path), *arguments],
        capture_output=True, text=True, check=False,
    )
    if result.returncode:
        raise RuntimeError(f"git failed for {path.name}: {result.stderr.strip()[:180]}")
    return result.stdout.strip()


def digest(path: Path) -> str:
    h = hashlib.sha256()
    with path.open("rb") as handle:
        for chunk in iter(lambda: handle.read(1024 * 1024), b""):
            h.update(chunk)
    return h.hexdigest()


def main() -> int:
    ap = argparse.ArgumentParser(description=__doc__)
    ap.add_argument("--apk", type=Path, required=True, help="The exact finished APK to record")
    ap.add_argument("--arcana", type=Path, default=ROOT / "third_party/arcana")
    ap.add_argument("--forge", type=Path, default=ROOT / "third_party/forge")
    ap.add_argument("--expected-arcana", default=os.getenv("ARCANA_SHA", DEFAULT_ARCANA))
    ap.add_argument("--expected-forge", default=os.getenv("FORGE_SHA", DEFAULT_FORGE))
    ap.add_argument("--out", type=Path, default=ROOT / "reports/gpl-build-evidence.json")
    args = ap.parse_args()

    checks: dict[str, bool] = {}
    notes: list[str] = []
    revisions: dict[str, str | None] = {}
    modified_counts: dict[str, int | None] = {}

    for name, folder, expected in (
        ("arcana", args.arcana, args.expected_arcana),
        ("forge", args.forge, args.expected_forge),
    ):
        exists = (folder / ".git").exists()
        checks[f"{name}_checkout_exists"] = exists
        revisions[name] = None
        modified_counts[name] = None
        if not exists:
            notes.append(f"{name}: missing local Git checkout at {folder}")
            continue
        try:
            actual = git(folder, "rev-parse", "HEAD")
            revisions[name] = actual
            checks[f"{name}_matches_expected_commit"] = actual == expected
            # Modifications are EXPECTED in Arcana due to Chimera's build overlays.
            changed = git(folder, "status", "--porcelain", "--untracked-files=no")
            modified_counts[name] = len(changed.splitlines()) if changed else 0
            if changed:
                notes.append(
                    f"{name}: tracked source is modified; a plain upstream archive "
                    "will NOT be the complete corresponding source."
                )
        except (RuntimeError, OSError) as exc:
            checks[f"{name}_matches_expected_commit"] = False
            notes.append(str(exc))

    apk = args.apk.expanduser().resolve()
    checks["apk_exists"] = apk.is_file() and apk.stat().st_size > 0 if apk.exists() else False
    license_file = ROOT / "third_party_licenses/Forge-GPL-3.0.txt"
    checks["forge_license_copy_present"] = license_file.is_file()
    checks["release_playbook_present"] = (ROOT / "docs/GPLV3_RELEASE_READINESS.md").is_file()

    chimera_sha = None
    try:
        chimera_sha = git(ROOT, "rev-parse", "HEAD")
    except (RuntimeError, OSError) as exc:
        notes.append(str(exc))

    report = {
        "schema": "chimera-gpl-evidence-v1",
        "generated_utc": dt.datetime.now(dt.timezone.utc).isoformat(),
        "purpose": "BUILD PROVENANCE ONLY; NOT A SOURCE BUNDLE OR LICENSE COMPLIANCE CERTIFICATION",
        "apk": (
            {"sha256": digest(apk), "size_bytes": apk.stat().st_size, "filename": apk.name}
            if checks["apk_exists"] else None
        ),
        "commits": {
            "chimera": chimera_sha,
            "arcana_expected": args.expected_arcana,
            "arcana_checkout": revisions["arcana"],
            "forge_expected": args.expected_forge,
            "forge_checkout": revisions["forge"],
        },
        "tracked_modified_file_counts": modified_counts,
        "checks": checks,
        "preflight_checks_ok": all(checks.values()),
        "notes": notes,
        "MANUAL_RELEASE_BLOCKERS": [
            "Produce the complete source tree for this actual APK including patched Arcana, Forge, Chimera, scripts and relevant dependencies.",
            "Verify the source archive against the APK and retain paired hashes.",
            "Audit all embedded dependencies and preserve applicable notices.",
            "Give recipients equivalent timely access to the exact corresponding source.",
            "Review GPLv3 section 6 and app store / installation restrictions with counsel.",
            "Obtain owner's explicit release approval.",
        ],
    }

    args.out.parent.mkdir(parents=True, exist_ok=True)
    args.out.write_text(json.dumps(report, indent=2, sort_keys=True) + "\n", encoding="utf-8")
    print(f"Evidence manifest: {args.out}")
    print(f"Preflight data checks: {'OK' if report['preflight_checks_ok'] else 'INCOMPLETE'}")
    print("Distribution status: BLOCKED until manual release requirements are independently satisfied.")
    return 0 if report["preflight_checks_ok"] else 2


if __name__ == "__main__":
    sys.exit(main())
