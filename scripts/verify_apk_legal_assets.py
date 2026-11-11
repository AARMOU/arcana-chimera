#!/usr/bin/env python3
"""Offline verification of legal assets actually packaged in an APK.

Preflight evidence only; never grants permission to distribute or publish.
"""
from __future__ import annotations
import argparse
import json
from pathlib import Path
import re
import sys
import zipfile

REQUIRED = {
    "GPL3": "assets/legal/GPL-3.0.txt",
    "CHIMERA_SCOPE": "assets/legal/CHIMERA-LICENSE-SCOPE.md",
    "FORGE_GPL": "assets/legal/Forge-GPL-3.0.txt",
    "RELEASE_NOTES": "assets/legal/GPL-RELEASE-NOTICES.md",
    "SOURCE_ACCESS": "assets/legal/SOURCE-ACCESS.txt",
}
THIRD_PARTY_NOTICES = "assets/legal/THIRD-PARTY-NOTICES.txt"
HEX_SHA = re.compile(r"^[0-9a-fA-F]{64}$")


def check_apk(path: Path) -> dict:
    if path.is_symlink() or not path.is_file() or path.suffix.lower() != ".apk":
        raise ValueError("APK must be a regular .apk file")
    if path.stat().st_size == 0:
        raise ValueError("APK is empty")
    with zipfile.ZipFile(path) as z:
        file_list = z.namelist()
        names = set(file_list)
        missing = [name for name in REQUIRED.values() if name not in names]
        # Duplicate legal filenames may conceal contradictory legal metadata.
        protected = set(REQUIRED.values()) | {THIRD_PARTY_NOTICES}
        duplicated = [name for name in sorted(protected) if file_list.count(name) > 1]
        read_errors = [name + " is duplicated" for name in duplicated]
        for item in REQUIRED.values():
            if item not in names:
                continue
            try:
                info = z.getinfo(item)
                if not (0 < info.file_size <= 2_000_000):
                    read_errors.append(item + " has invalid file size")
                else:
                    z.read(item)
            except (RuntimeError, ValueError, zipfile.BadZipFile):
                read_errors.append(item + " could not be read")
        published = False
        source_details = "NOT_PACKAGED"
        source_path = REQUIRED["SOURCE_ACCESS"]
        if source_path in names and not any(
            x.startswith(source_path + " ") for x in read_errors
        ):
            raw = z.read(source_path).decode("utf-8", "replace")
            fields = dict(line.strip().split("=", 1) for line in raw.splitlines() if "=" in line)
            version, url, digest = [fields.get(key, "") for key in
                ("SOURCE_VERSION_NAME", "SOURCE_URL", "SOURCE_SHA256")]
            published = (version not in ("", "UNPUBLISHED")
                         and url.startswith("https://")
                         and not any(c.isspace() for c in url)
                         and HEX_SHA.fullmatch(digest) is not None)
            source_details = ("METADATA_PRESENT_REQUIRES_EXTERNAL_VERIFICATION"
                              if published else "UNPUBLISHED_OR_MALFORMED")
        notices_ok = False
        if THIRD_PARTY_NOTICES in names and THIRD_PARTY_NOTICES not in duplicated:
            info = z.getinfo(THIRD_PARTY_NOTICES)
            notices_ok = 0 < info.file_size <= 4_000_000
            if notices_ok:
                z.read(THIRD_PARTY_NOTICES)
        reasons = []
        if missing:
            reasons.append("MISSING_LICENSE_ASSETS")
        if read_errors:
            reasons.append("UNREADABLE_LICENSE_ASSETS")
        if not published:
            reasons.append("SOURCE_ACCESS_NOT_READY")
        if not notices_ok:
            reasons.append("THIRD_PARTY_NOTICES_NOT_PACKAGED")
        return {
            "schema": "chimera-apk-legal-preflight-v1",
            "release_approved": False,
            "apk_name": path.name,
            "required_assets_present": not missing,
            "missing_assets": missing,
            "read_errors": read_errors,
            "source_access_status": source_details,
            "third_party_notices_present": notices_ok,
            "preflight_status": "BLOCKED" if reasons else "NEEDS_EXTERNAL_HUMAN_REVIEW",
            "reasons": reasons,
            "limitations": [
                "Cannot certify every dependency or content license",
                "Cannot verify complete corresponding source, rebuildability, or live URL",
                "Cannot authorize distribution or Google Play acceptance",
            ],
        }


def main() -> int:
    p = argparse.ArgumentParser(description=__doc__)
    p.add_argument("--apk", required=True, type=Path)
    p.add_argument("--out", type=Path)
    args = p.parse_args()
    try:
        report = check_apk(args.apk)
        if args.out:
            args.out.parent.mkdir(parents=True, exist_ok=True)
            args.out.write_text(json.dumps(report, indent=2, sort_keys=True) + "\n",
                                encoding="utf-8")
    except (OSError, ValueError, zipfile.BadZipFile) as exc:
        print("BLOCKED: " + str(exc), file=sys.stderr)
        return 2
    print(json.dumps(report, indent=2, sort_keys=True))
    return 2 if report["preflight_status"] == "BLOCKED" else 0


if __name__ == "__main__":
    raise SystemExit(main())
