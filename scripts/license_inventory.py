#!/usr/bin/env python3
"""Read-only license EVIDENCE inventory. Human approval always required."""
import argparse
import hashlib
import json
from pathlib import Path
import re
import sys
import zipfile

LICENSE_RE = re.compile(r"(?i)(?:^|/)(LICENSE[^/]*|LICENCE[^/]*|NOTICE[^/]*|COPYING[^/]*)$")
COORD_RE = re.compile(r"(?<![\w.-])([A-Za-z][\w.-]+(?:\.[\w.-]+)+):([\w.-]+):([^\s()*]+)")


def sha256(path):
    h = hashlib.sha256()
    with path.open("rb") as stream:
        for block in iter(lambda: stream.read(1024 * 1024), b""):
            h.update(block)
    return h.hexdigest()


def jar_evidence(path):
    if path.is_symlink() or not path.is_file() or path.stat().st_size > 300 * 1024**2:
        raise ValueError("Unsafe or oversized JAR")
    notices, coords = set(), set()
    try:
        with zipfile.ZipFile(path) as z:
            for item in z.infolist():
                name = item.filename
                if LICENSE_RE.search(name) and not item.is_dir():
                    notices.add(name)
                if (name.startswith("META-INF/maven/") and name.endswith("/pom.properties")
                        and 0 <= item.file_size <= 8192 and not item.is_dir()):
                    fields = dict(row.split("=", 1) for row in
                                  z.read(item).decode("utf-8", "replace").splitlines()
                                  if "=" in row and not row.lstrip().startswith("#"))
                    if all(k in fields for k in ("groupId", "artifactId", "version")):
                        coords.add(":".join(fields[k] for k in
                                            ("groupId", "artifactId", "version")))
    except (zipfile.BadZipFile, RuntimeError) as exc:
        raise ValueError("Unreadable JAR: " + path.name) from exc
    return {"file": path.name, "sha256": sha256(path), "size": path.stat().st_size,
            "notice_paths": sorted(notices), "pom_coordinates": sorted(coords),
            "license_review": "PENDING"}


def inventory(forge_libraries, app_libraries, runtime_report):
    if (not runtime_report.is_file() or runtime_report.is_symlink()
            or runtime_report.stat().st_size > 8 * 1024**2):
        raise ValueError("Gradle runtime report missing or unsafe")
    data = runtime_report.read_text(encoding="utf-8", errors="replace")
    if "releaseRuntimeClasspath" not in data:
        raise ValueError("Expected releaseRuntimeClasspath dependency report")
    coords = sorted({":".join(m) for m in COORD_RE.findall(data)})
    jars = []
    for scope, directory in (("forge", forge_libraries), ("android", app_libraries)):
        if not directory.is_dir() or directory.is_symlink():
            raise ValueError("Staged library directory missing")
        found = sorted(directory.glob("*.jar"))
        if not found:
            raise ValueError("Staged JAR directory empty: " + scope)
        for path in found:
            info = jar_evidence(path)
            info["scope"] = scope
            jars.append(info)
    return {"schema": "chimera-license-evidence-v1", "release_approved": False,
            "review_status": "HUMAN_LICENSE_REVIEW_REQUIRED",
            "gradle_report_sha256": sha256(runtime_report),
            "gradle_runtime_coordinates": coords, "staged_jar_evidence": jars,
            "jar_count": len(jars),
            "missing_embedded_notice_count": sum(not j["notice_paths"] for j in jars),
            "blockers": [
                "Determine actual licenses and preserve copyright and notice contents",
                "Review Gradle/Maven transitive and shaded dependencies",
                "Review Forge resource data and all card/art rights separately",
                "Verify compatibility, exact AAB source and recipient notice access",
                "Owner/legal approval mandatory; this is not GPL certification"]}


def main(argv=None):
    ap = argparse.ArgumentParser(description=__doc__)
    ap.add_argument("--forge-libs", type=Path, required=True)
    ap.add_argument("--app-libs", type=Path, required=True)
    ap.add_argument("--gradle-report", type=Path, required=True)
    ap.add_argument("--out", type=Path, required=True)
    a = ap.parse_args(argv)
    try:
        evidence = inventory(a.forge_libs, a.app_libs, a.gradle_report)
        a.out.parent.mkdir(parents=True, exist_ok=True)
        a.out.write_text(json.dumps(evidence, indent=2, sort_keys=True) + "\n")
    except (ValueError, OSError) as exc:
        print("BLOCKED:", exc, file=sys.stderr)
        return 2
    print("Evidence collected:", evidence["jar_count"], "JARs;",
          len(evidence["gradle_runtime_coordinates"]), "Gradle coordinates")
    print("RELEASE HOLD: human review required")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
