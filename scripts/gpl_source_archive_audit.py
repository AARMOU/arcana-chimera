#!/usr/bin/env python3
"""Fail-closed paired source archive INTEGRITY audit (not GPL certification).

Checks a binary and matching same-build source CANDIDATE without extracting the
archive, disclosing secrets, uploading artifacts or approving distribution.
"""
import argparse
import hashlib
import json
from pathlib import Path, PurePosixPath
import sys
import tarfile

REQUIRED = {
    "BUILD-PROVENANCE.json",
    "sources/chimera/scripts/build_arcana_forge_apk.sh",
    "sources/chimera/scripts/gpl_build_pair.py",
    "sources/chimera/third_party_licenses/Forge-GPL-3.0.txt",
    "sources/forge/pom.xml",
    "sources/arcana/android/app/build.gradle.kts",
}
BAD_SUFFIXES = {".jks", ".p12", ".pfx", ".keystore", ".key", ".aab", ".apk"}
BAD_NAMES = {"credentials.json", ".env", ".env.local", "local.properties",
             "secrets.properties", "google-services.json", "id_rsa"}
PUBLIC_PEM = "sources/chimera/scripts/delivery/frankenarcana-public.pem"


def digest(path):
    result = hashlib.sha256()
    with path.open("rb") as f:
        for block in iter(lambda: f.read(1024 * 1024), b""):
            result.update(block)
    return result.hexdigest()


def audit_pair(binary, archive, manifest):
    if binary.is_symlink() or archive.is_symlink() or manifest.is_symlink():
        raise ValueError("Symlink inputs forbidden")
    if not binary.is_file() or not archive.is_file() or not manifest.is_file():
        raise ValueError("Binary, source candidate and manifest required")
    kind = binary.suffix.lower().lstrip(".")
    if kind not in ("apk", "aab"):
        raise ValueError("Expected APK or AAB")
    doc = json.loads(manifest.read_text(encoding="utf-8"))
    if (doc.get("distribution_approved") is not False
            or doc.get("source_name") != archive.name
            or doc.get("source_sha256") != digest(archive)
            or doc.get(kind + "_sha256") != digest(binary)):
        raise ValueError("Pair manifest digest, artifact kind or HOLD mismatch")
    seen = set()
    file_counts = {"chimera": 0, "forge": 0, "arcana": 0}
    with tarfile.open(archive, "r:gz") as tar:
        members = tar.getmembers()
        if len(members) > 1000000:
            raise ValueError("Excessive archive member count")
        for entry in members:
            name = entry.name
            p = PurePosixPath(name)
            if (not name or p.is_absolute() or ".." in p.parts or
                    len(name) > 500 or name in seen):
                raise ValueError("Unsafe or duplicate archive member")
            seen.add(name)
            if not entry.isfile():
                raise ValueError("Nonregular archive member; extraction could be unsafe")
            if entry.size > 1024**3:
                raise ValueError("Oversize archive entry")
            if p.name.lower() in BAD_NAMES or p.suffix.lower() in BAD_SUFFIXES or (
                p.suffix.lower() == ".pem" and name != PUBLIC_PEM
            ):
                raise ValueError("Potential secret or release binary in source")
            if len(p.parts) > 2 and p.parts[0] == "sources" and p.parts[1] in file_counts:
                file_counts[p.parts[1]] += 1
        if not REQUIRED.issubset(seen):
            raise ValueError("Required source/build inputs missing: " +
                             ", ".join(sorted(REQUIRED - seen)))
        evidence = tar.extractfile("BUILD-PROVENANCE.json")
        if evidence is None or evidence.size > 256 * 1024:
            raise ValueError("Build provenance missing or too large")
        embedded = json.load(evidence)
    for field in ("commits", "file_counts", kind + "_sha256"):
        if embedded.get(field) != doc.get(field):
            raise ValueError("Embedded provenance and manifest disagree on " + field)
    if embedded.get("file_counts") != file_counts:
        raise ValueError("Source file counts disagree with archive contents")
    if any(v == 0 for v in file_counts.values()):
        raise ValueError("Missing one or more source trees")
    return {"schema": "chimera-pair-integrity-audit-v1",
            "binary_sha256": digest(binary), "candidate_sha256": digest(archive),
            "source_file_counts": file_counts, "integrity": "PASS",
            "release_approved": False,
            "limitations": [
                "No clean rebuild from this candidate has been demonstrated",
                "No dependency licenses, exact build tools or sources certified",
                "No permanent source hosting or recipient notices verified",
                "No Play distribution, GPLv3 section 6 or legal review certified"]}


def main(argv=None):
    p = argparse.ArgumentParser(description=__doc__)
    p.add_argument("--binary", type=Path, required=True)
    p.add_argument("--source", type=Path, required=True)
    p.add_argument("--manifest", type=Path, required=True)
    p.add_argument("--out", type=Path, required=True)
    a = p.parse_args(argv)
    try:
        report = audit_pair(a.binary, a.source, a.manifest)
        a.out.parent.mkdir(parents=True, exist_ok=True)
        a.out.write_text(json.dumps(report, indent=2, sort_keys=True) + "\n")
    except (ValueError, OSError, KeyError, tarfile.TarError, json.JSONDecodeError) as exc:
        print("BLOCKED: Source integrity audit failed:", exc, file=sys.stderr)
        return 2
    print("Source candidate integrity PASS; GPL / clean rebuild / release still HOLD.")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
