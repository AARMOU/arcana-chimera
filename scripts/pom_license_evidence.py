#!/usr/bin/env python3
"""Offline review evidence from resolved Maven/Gradle POMs; never a legal clearance."""
from __future__ import annotations

import argparse
from collections import defaultdict
import json
from pathlib import Path
import sys
import xml.etree.ElementTree as ET

MAX_POM = 2 * 1024 * 1024
MAX_POMS = 30000
MAX_INVENTORY = 16 * 1024 * 1024


def tag_name(node):
    return node.tag.rsplit("}", 1)[-1]


def child(node, name):
    if node is None:
        return None
    return next((x for x in node if tag_name(x) == name), None)


def field(node, name):
    x = child(node, name)
    return (x.text or "").strip() if x is not None else ""


def parse_pom(path):
    """Record declarations verbatim; parent inheritance is NOT assumed."""
    if path.is_symlink() or not path.is_file() or path.stat().st_size > MAX_POM:
        raise ValueError("Unsafe or oversized Maven POM")
    root = ET.parse(path).getroot()
    parent = child(root, "parent")
    group = field(root, "groupId") or field(parent, "groupId")
    artifact = field(root, "artifactId")
    version = field(root, "version") or field(parent, "version")
    if not group or not artifact or not version:
        return None
    licenses = child(root, "licenses")
    declared = []
    if licenses is not None:
        for entry in licenses:
            if tag_name(entry) != "license":
                continue
            name, url = field(entry, "name"), field(entry, "url")
            distribution = field(entry, "distribution")
            if name or url:
                declared.append({"name": name, "url": url, "distribution": distribution})
    return {
        "coordinate": group + ":" + artifact + ":" + version,
        "group": group, "artifact": artifact, "version": version,
        "declared_licenses": sorted(declared, key=lambda x: (x["name"], x["url"])),
        "parent_coordinate": ":".join((field(parent, "groupId"), field(parent, "artifactId"), field(parent, "version")))
            if parent is not None else None,
        "metadata_only": True,
    }


def pom_index(roots):
    """Only .pom and pom.xml under explicitly selected build source/cache roots."""
    records = defaultdict(list)
    scanned = 0
    errors = []
    for role, directory in roots:
        if not directory.is_dir() or directory.is_symlink():
            errors.append(role + ":cache_missing")
            continue
        # Maven .pom files plus Forge source pom.xml.
        paths = set(directory.rglob("*.pom"))
        if role == "forge_source":
            paths.update(directory.rglob("pom.xml"))
        for path in sorted(paths):
            scanned += 1
            if scanned > MAX_POMS:
                raise ValueError("Too many Maven POMs; narrow selected cache roots")
            try:
                d = parse_pom(path)
                if d:
                    d["evidence_role"] = role
                    records[d["coordinate"]].append(d)
            except (ET.ParseError, ValueError, OSError) as exc:
                errors.append(role + ":" + type(exc).__name__)
    return records, scanned, sorted(set(errors))


def candidate_licenses(rows):
    found = {}
    for row in rows:
        for license_decl in row["declared_licenses"]:
            key = (license_decl["name"], license_decl["url"], license_decl["distribution"])
            found[key] = license_decl
    return list(found.values())


def analyze(inventory, roots):
    if (inventory.is_symlink() or not inventory.is_file()
            or inventory.stat().st_size > MAX_INVENTORY):
        raise ValueError("Missing or oversized source dependency inventory")
    data = json.loads(inventory.read_text(encoding="utf-8"))
    if data.get("schema") != "chimera-license-evidence-v1" or data.get("release_approved") is not False:
        raise ValueError("Expected fail-closed Chimera dependency inventory")
    jars = data.get("staged_jar_evidence", [])
    gradle = data.get("gradle_runtime_coordinates", [])
    if not isinstance(jars, list) or not isinstance(gradle, list) or not jars:
        raise ValueError("Malformed or empty dependency inventory")
    pom, count, issues = pom_index(roots)
    if count == 0:
        raise ValueError("No Maven/Gradle metadata found; do not infer licenses")
    gradle_items = []
    for coord in sorted(set(gradle)):
        matches = pom.get(coord, [])
        licenses = candidate_licenses(matches)
        gradle_items.append({
            "coordinate": coord, "pom_metadata_found": bool(matches),
            "declared_licenses": licenses, "source_roles": sorted({m["evidence_role"] for m in matches}),
            "review": "HUMAN_REVIEW_REQUIRED",
            "unresolved": (
                "NO_MATCHING_POM" if not matches else
                "NO_EXPLICIT_LICENSE" if not licenses else
                "LICENSE_DECLARATION_NOT_VERIFIED"
            ),
        })
    jar_items = []
    for entry in jars:
        name = entry.get("file", "")
        if not isinstance(name, str) or not name.endswith(".jar"):
            raise ValueError("Malformed JAR file in dependency inventory")
        matches = []
        for coord, rows in pom.items():
            a, v = rows[0]["artifact"], rows[0]["version"]
            if name == a + "-" + v + ".jar" or name.startswith(a + "-" + v + "-"):
                matches.extend(rows)
        coords = sorted({m["coordinate"] for m in matches})
        licenses = candidate_licenses(matches)
        jar_items.append({
            "scope": entry.get("scope"), "file": name, "sha256": entry.get("sha256"),
            "candidate_pom_coordinates": coords,
            "declared_licenses": licenses,
            "source_roles": sorted({m["evidence_role"] for m in matches}),
            "notice_paths": entry.get("notice_paths", []),
            "review": "HUMAN_REVIEW_REQUIRED",
            "unresolved": (
                "NO_MATCHING_POM" if not matches else
                "AMBIGUOUS_COORDINATES" if len(coords) != 1 else
                "NO_EXPLICIT_LICENSE" if not licenses else
                "LICENSE_DECLARATION_NOT_VERIFIED"
            ),
        })
    return {
        "schema": "chimera-pom-license-review-evidence-v1",
        "distribution_approved": False,
        "license_compatibility_verified": False,
        "evidence_only": True,
        "pom_files_inspected": count,
        "pom_parse_or_source_warnings": issues,
        "gradle_dependencies": gradle_items,
        "staged_jars": jar_items,
        "unresolved_gradle_count": sum(x["unresolved"] != "LICENSE_DECLARATION_NOT_VERIFIED" for x in gradle_items),
        "unresolved_jar_count": sum(x["unresolved"] != "LICENSE_DECLARATION_NOT_VERIFIED" for x in jar_items),
        "human_review_required": [
            "Resolve POM inheritance, overrides and effective versions before licensing conclusions",
            "Check upstream authoritative LICENSE/NOTICE texts, compatibility and modifications",
            "Audit every runtime and transitive dependency including shaded/native libraries",
            "Audit Forge resource archive, card scripts, precons, downloaded/generated assets and IP",
            "Verify matching rebuildable source, recipient-facing notices and source URL",
            "Have a qualified human approve any actual GPL/Play distribution",
        ],
    }


def main(argv=None):
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--inventory", type=Path, required=True)
    parser.add_argument("--maven-cache", type=Path, required=True)
    parser.add_argument("--gradle-cache", type=Path, required=True)
    parser.add_argument("--forge-source", type=Path, required=True)
    parser.add_argument("--out", type=Path, required=True)
    a = parser.parse_args(argv)
    try:
        result = analyze(a.inventory, [
            ("maven_cache", a.maven_cache),
            ("gradle_cache", a.gradle_cache),
            ("forge_source", a.forge_source),
        ])
        a.out.parent.mkdir(parents=True, exist_ok=True)
        a.out.write_text(json.dumps(result, indent=2, sort_keys=True) + "\n", encoding="utf-8")
    except (OSError, ValueError, json.JSONDecodeError) as exc:
        print("BLOCKED: " + str(exc), file=sys.stderr)
        return 2
    print("Maven/Gradle POM evidence:", result["pom_files_inspected"], "files")
    print("Requires manual license, notice, rights and compatibility review. NOT RELEASE READY.")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
