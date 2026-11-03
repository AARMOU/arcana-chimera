#!/usr/bin/env python3
"""Collect Maven Surefire test counts without mistaking compilation for gameplay."""
from __future__ import annotations
import argparse
import json
import xml.etree.ElementTree as ET
from pathlib import Path
from datetime import datetime, timezone


def summarize(root: Path) -> dict:
    files = sorted(root.glob("**/target/surefire-reports/TEST-*.xml"))
    result = {"reports": 0, "tests": 0, "failures": 0, "errors": 0, "skipped": 0, "invalid_reports": []}
    for file in files:
        try:
            element = ET.parse(file).getroot()
            suites = [element] if element.tag == "testsuite" else list(element.findall("testsuite"))
            for suite in suites:
                for key in ("tests", "failures", "errors", "skipped"):
                    result[key] += int(suite.attrib.get(key, 0))
            result["reports"] += 1
        except (ET.ParseError, ValueError, OSError) as exc:
            result["invalid_reports"].append({
                "file": str(file.relative_to(root)), "error": str(exc)
            })
    return result


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--root", type=Path, required=True)
    parser.add_argument("--out", type=Path, required=True)
    parser.add_argument("--sha", required=True)
    parser.add_argument("--exit-code", type=int, required=True)
    parser.add_argument("--duration-seconds", type=int, required=True)
    args = parser.parse_args()
    report = summarize(args.root)
    report.update({
        "forge_sha": args.sha,
        "maven_exit_code": args.exit_code,
        "duration_seconds": args.duration_seconds,
        "captured_utc": datetime.now(timezone.utc).isoformat(),
        "maven_success": args.exit_code == 0,
        "behavioral_test_evidence": (
            args.exit_code == 0 and report["tests"] > 0 and
            report["failures"] == 0 and report["errors"] == 0 and
            not report["invalid_reports"]
        ),
        "commander_game_verified": False,
        "android_gameplay_verified": False
    })
    args.out.parent.mkdir(parents=True, exist_ok=True)
    args.out.write_text(json.dumps(report, indent=2) + "\n", encoding="utf-8")
    print(json.dumps(report, indent=2))


if __name__ == "__main__":
    main()
