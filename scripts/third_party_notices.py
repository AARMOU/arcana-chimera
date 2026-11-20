#!/usr/bin/env python3
"""Write the third-party notices that ship inside the Chimera APK/AAB.

Every staged JAR must match a reviewed entry in third_party_licenses/reviewed.json.
A JAR that is unreviewed, or whose licence cannot be combined with GPLv3, stops the
build. Android (Gradle) coordinates are listed by reviewed group; an unreviewed group
is reported, not hidden, and recorded in the JSON report.
"""
import argparse
import hashlib
import json
from pathlib import Path
import re
import sys
import zipfile

ROOT = Path(__file__).resolve().parents[1]
NOTICE_NAME = re.compile(r"(?i)(^|/)NOTICE(\.txt|\.md)?$")
COORD = re.compile(r"[+\\]--- ([A-Za-z0-9_.\-]+):([A-Za-z0-9_.\-]+)(?::([^\s(]+))?(?: -> ([^\s(]+))?")
MAX_NOTICE = 200_000


def load_reviewed(path):
    data = json.loads(Path(path).read_text(encoding="utf-8"))
    for key in ("forbidden_jars", "jars", "gradle_groups"):
        if not isinstance(data.get(key), list):
            raise ValueError("reviewed.json is missing " + key)
    return data


def classify(names, reviewed):
    """Return ({jar: entry}, problems)."""
    matched, problems = {}, []
    for name in sorted(names):
        bad = next((f for f in reviewed["forbidden_jars"] if re.search(f["match"], name)), None)
        if bad:
            problems.append(name + ": " + bad["license"] + " must not ship (" + bad["why"] + ")")
            continue
        entry = next((j for j in reviewed["jars"] if re.search(j["match"], name)), None)
        if entry is None:
            problems.append(name + ": no reviewed licence entry")
            continue
        matched[name] = entry
    return matched, problems


def jar_notices(jar):
    out = []
    with zipfile.ZipFile(jar) as z:
        for info in z.infolist():
            if NOTICE_NAME.search(info.filename) and 0 < info.file_size <= MAX_NOTICE:
                out.append(z.read(info).decode("utf-8", "replace").strip())
    return out


def gradle_coordinates(report):
    found = {}
    for line in Path(report).read_text(encoding="utf-8", errors="replace").splitlines():
        m = COORD.search(line)
        if m:
            group, artifact, version, resolved = m.groups()
            found[group + ":" + artifact] = resolved or version or ""
    return found


def build(jar_dirs, reviewed, texts_dir, gradle_report=None):
    jars = {}
    for folder in jar_dirs:
        for jar in sorted(Path(folder).glob("*.jar")):
            jars.setdefault(jar.name, jar)
    if not jars:
        raise ValueError("No JARs found to describe")
    matched, problems = classify(jars, reviewed)
    if problems:
        raise ValueError("Licence review blocks this build:\n  " + "\n  ".join(problems))
    used_texts, lines = [], []
    lines += ["THIRD-PARTY SOFTWARE IN ARCANA TABLE WITH FORGE", "",
              "This app as a whole is distributed under the GNU General Public License,",
              "version 3 (see the GPLv3 tab). It includes the components below, each under",
              "its own licence. Licence texts follow the list.", "",
              "== Bundled libraries ==", ""]
    groups = {}
    for name, entry in matched.items():
        groups.setdefault(entry["name"], (entry, []))[1].append(name)
    for title in sorted(groups):
        entry, names = groups[title]
        lines.append(title + " - " + entry.get("elected", entry["license"]))
        if entry.get("elected"):
            lines.append("  Offered as: " + entry["license"])
        if entry.get("note"):
            lines.append("  " + entry["note"])
        lines.append("  Files: " + ", ".join(names))
        lines.append("")
        for t in entry.get("texts", []):
            if t not in used_texts:
                used_texts.append(t)
    unreviewed = []
    if gradle_report:
        coords = gradle_coordinates(gradle_report)
        lines += ["== Android libraries (resolved by Gradle) ==", ""]
        by_license = {}
        for coord, version in sorted(coords.items()):
            group = coord.split(":")[0]
            rule = next((g for g in reviewed["gradle_groups"]
                         if group == g["prefix"].rstrip(".") or group.startswith(g["prefix"])), None)
            if rule is None:
                unreviewed.append(coord)
                continue
            by_license.setdefault(rule["license"], []).append(coord + (":" + version if version else ""))
            for t in rule.get("texts", ["Apache-2.0.txt"] if rule["license"] == "Apache-2.0" else []):
                if t not in used_texts:
                    used_texts.append(t)
        for lic in sorted(by_license):
            lines.append(lic + ":")
            lines += ["  " + c for c in by_license[lic]]
            lines.append("")
        if unreviewed:
            lines.append("Licence not yet reviewed (see each project's own terms):")
            lines += ["  " + c for c in unreviewed]
            lines.append("")
    seen, notice_blocks = set(), []
    for name in sorted(matched):
        for text in jar_notices(jars[name]):
            digest = hashlib.sha256(text.encode()).hexdigest()
            if digest not in seen:
                seen.add(digest)
                notice_blocks.append((name, text))
    if notice_blocks:
        lines += ["== NOTICE files shipped by the libraries ==", ""]
        for name, text in notice_blocks:
            lines += ["--- from " + name + " ---", text, ""]
    lines += ["== Licence texts ==", ""]
    for t in used_texts:
        path = Path(texts_dir) / t
        if not path.is_file() or not path.stat().st_size:
            raise ValueError("Licence text missing: " + t)
        lines += ["--- " + t + " ---", path.read_text(encoding="utf-8", errors="replace").strip(), ""]
    report = {"schema": "chimera-third-party-notices-v1", "jar_count": len(matched),
              "jars": {n: e.get("elected", e["license"]) for n, e in matched.items()},
              "notice_files": len(notice_blocks), "licence_texts": used_texts,
              "unreviewed_gradle_coordinates": unreviewed}
    return "\n".join(lines) + "\n", report


def main(argv=None):
    p = argparse.ArgumentParser(description=__doc__)
    p.add_argument("--jars", type=Path, action="append", required=True)
    p.add_argument("--gradle-report", type=Path)
    p.add_argument("--reviewed", type=Path, default=ROOT / "third_party_licenses/reviewed.json")
    p.add_argument("--texts", type=Path, default=ROOT / "third_party_licenses/texts")
    p.add_argument("--out", type=Path, required=True)
    p.add_argument("--report", type=Path)
    a = p.parse_args(argv)
    try:
        text, report = build(a.jars, load_reviewed(a.reviewed), a.texts, a.gradle_report)
    except (OSError, ValueError, zipfile.BadZipFile) as exc:
        print("BLOCKED: " + str(exc), file=sys.stderr)
        return 2
    a.out.parent.mkdir(parents=True, exist_ok=True)
    a.out.write_text(text, encoding="utf-8")
    if a.report:
        a.report.parent.mkdir(parents=True, exist_ok=True)
        a.report.write_text(json.dumps(report, indent=2, sort_keys=True) + "\n", encoding="utf-8")
    print("Third-party notices: %d JARs, %d NOTICE files, %d licence texts, %d unreviewed Gradle coordinates"
          % (report["jar_count"], report["notice_files"], len(report["licence_texts"]),
             len(report["unreviewed_gradle_coordinates"])))
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
