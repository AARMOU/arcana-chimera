#!/usr/bin/env python3
"""Export allowlisted GPL audit metadata only; never APK, source or secrets."""
from __future__ import annotations

import argparse
import json
from pathlib import Path
import re

HEX = re.compile(r"^[0-9a-f]{64}$")
JAR = re.compile(r"^[A-Za-z0-9_.+~-]{1,180}\\.jar$")

def read_json(path):
    if path.is_symlink() or not path.is_file() or path.stat().st_size > 16 * 1024 * 1024:
        raise ValueError("Missing or unsafe audit input")
    return json.loads(path.read_text(encoding="utf-8"))

def sha256(value):
    if not isinstance(value, str) or not HEX.fullmatch(value):
        raise ValueError("Missing or malformed sha256")
    return value

def safe_text(value, limit=360):
    if not isinstance(value, str) or len(value) > limit or any(ord(ch) < 32 for ch in value):
        raise ValueError("Malformed audit metadata")
    if any(token in value.lower() for token in ("private key", "password=", "secret=", "token=", "-----begin")):
        raise ValueError("Potential secret in audit metadata")
    return value

def licenses(items):
    if not isinstance(items, list) or len(items) > 20:
        raise ValueError("Invalid license declaration list")
    return [{"name": safe_text(row.get("name", "")),
             "url": safe_text(row.get("url", "")),
             "distribution": safe_text(row.get("distribution", ""))}
            for row in items]

def export(pair_path, integrity_path, inventory_path, pom_path):
    pair, integrity, inventory, pom = [read_json(path) for path in
                                       (pair_path, integrity_path, inventory_path, pom_path)]
    if (pair.get("distribution_approved") is not False or
            integrity.get("release_approved") is not False or
            integrity.get("integrity") != "PASS" or
            inventory.get("schema") != "chimera-license-evidence-v1" or
            inventory.get("release_approved") is not False or
            pom.get("schema") != "chimera-pom-license-review-evidence-v1" or
            pom.get("distribution_approved") is not False or
            pom.get("license_compatibility_verified") is not False or
            pom.get("evidence_only") is not True):
        raise ValueError("Release HOLD or report schema violated")
    apk_hash, source_hash = sha256(pair.get("apk_sha256")), sha256(pair.get("source_sha256"))
    if integrity.get("binary_sha256") != apk_hash or integrity.get("candidate_sha256") != source_hash:
        raise ValueError("APK/source pair hash disagreement")
    jars = inventory.get("staged_jar_evidence")
    pom_jars = pom.get("staged_jars")
    gradle = pom.get("gradle_dependencies")
    if (not isinstance(jars, list) or not jars or not isinstance(pom_jars, list) or
            len(jars) != len(pom_jars) or not isinstance(gradle, list) or
            len(jars) != inventory.get("jar_count")):
        raise ValueError("Missing or inconsistent runtime dependency inventory")
    jar_rows = []
    for i, row in enumerate(pom_jars):
        original = jars[i]
        name = row.get("file")
        if (not isinstance(name, str) or not JAR.fullmatch(name) or
                name != original.get("file") or row.get("scope") != original.get("scope") or
                row.get("sha256") != original.get("sha256") or
                row.get("review") != "HUMAN_REVIEW_REQUIRED"):
            raise ValueError("Unmatched or unreviewed runtime dependency")
        jar_rows.append({"scope": safe_text(row["scope"], 20), "file": name,
                         "sha256": sha256(row["sha256"]),
                         "candidate_pom_coordinates": [safe_text(s, 220) for s in row.get("candidate_pom_coordinates", [])],
                         "declared_licenses": licenses(row.get("declared_licenses", [])),
                         "notice_paths": [safe_text(s, 300) for s in row.get("notice_paths", [])],
                         "unresolved": safe_text(row.get("unresolved", ""), 80)})
    gradle_rows = []
    for row in gradle:
        if row.get("review") != "HUMAN_REVIEW_REQUIRED":
            raise ValueError("Unreviewed Gradle dependency")
        gradle_rows.append({"coordinate": safe_text(row.get("coordinate", ""), 220),
                            "pom_metadata_found": row.get("pom_metadata_found") is True,
                            "declared_licenses": licenses(row.get("declared_licenses", [])),
                            "unresolved": safe_text(row.get("unresolved", ""), 80)})
    return {
        "schema": "chimera-audit-evidence-only-v1",
        "distribution_approved": False, "source_complete_certified": False,
        "license_compatibility_verified": False, "human_release_review_required": True,
        "apk_sha256_not_attached": apk_hash,
        "source_candidate_sha256_not_attached": source_hash,
        "source_candidate_integrity": "PASS; NOT a clean rebuild or GPL certification",
        "forge_and_android_staged_jar_count": len(jar_rows),
        "jars_without_embedded_notice_path": sum(not row["notice_paths"] for row in jar_rows),
        "jar_pom_unresolved_count": sum(row["unresolved"] != "LICENSE_DECLARATION_NOT_VERIFIED" for row in jar_rows),
        "gradle_pom_unresolved_count": sum(row["unresolved"] != "LICENSE_DECLARATION_NOT_VERIFIED" for row in gradle_rows),
        "jar_evidence": jar_rows, "gradle_evidence": gradle_rows,
        "blockers": [
            "Qualified human review of authoritative dependency licenses and NOTICE text",
            "Audit shaded/native components, Forge resources and other included assets",
            "Clean rebuild of COMPLETE corresponding source and durable matching source access",
            "GPLv3 section 6 / Google Play review; on-device QA; explicit owner release approval",
        ],
    }

def main():
    p = argparse.ArgumentParser(description=__doc__)
    for option in ("pair", "integrity", "inventory", "pom", "out"):
        p.add_argument("--" + option, type=Path, required=True)
    args = p.parse_args()
    report = export(args.pair, args.integrity, args.inventory, args.pom)
    args.out.parent.mkdir(parents=True, exist_ok=True)
    args.out.write_text(json.dumps(report, sort_keys=True, indent=2) + "\\n", encoding="utf-8")
    print("AUDIT-ONLY report assembled; no APK/AAB/source/secret data included. RELEASE HOLD remains.")

if __name__ == "__main__":
    main()
