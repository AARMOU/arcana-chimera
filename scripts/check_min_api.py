#!/usr/bin/env python3
"""Fail the build if shipped Java code calls APIs that minSdk 26 (Android 8.0) devices may lack.

Owner, Nov 11-12: a Pixel 5 on Android 13 crashed with NoSuchMethodError in humanTypes() because
Matcher.appendTail(StringBuilder) only exists on Android 14 (API 34), and String.isBlank() is a Java 11
call. This is a denylist, not a full lint: it catches the known-bad calls and keeps them from coming back.

Usage: check_min_api.py FILE_OR_DIR [...]   (exit 1 on any hit). Run it on shipped sources only.
"""
import re
import sys
from pathlib import Path

# (pattern, why)
DENYLIST = [
    (re.compile(r"\.isBlank\(\)"),
     "String.isBlank() is Java 11; use trim().isEmpty()"),
    (re.compile(r"\.strip\(\)|\.stripLeading\(\)|\.stripTrailing\(\)"),
     "String.strip() family is Java 11; use trim()"),
    (re.compile(r"\.repeat\(\s*\d|String\.repeat\("),
     "String.repeat() is Java 11; build the string another way"),
    (re.compile(r"\bList\.of\(|\bMap\.of\(|\bSet\.of\("),
     "List.of/Map.of/Set.of are Java 9; use Arrays.asList or new HashMap"),
]


def builder_calls(text):
    """Matcher.appendTail/appendReplacement given a StringBuilder variable (API 34 only on Android)."""
    names = set(re.findall(r"StringBuilder\s+(\w+)\s*[=;,)]", text))
    hits = []
    for name in names:
        call = re.compile(r"\.(appendTail|appendReplacement)\(\s*" + re.escape(name) + r"\b")
        for number, line in enumerate(text.splitlines(), 1):
            if call.search(line):
                hits.append((number, "Matcher.appendTail/appendReplacement(StringBuilder) needs API 34; use StringBuffer"))
    return hits


def scan(roots):
    hits = []
    for root in roots:
        target = Path(root)
        paths = [target] if target.is_file() else sorted(target.rglob("*.java"))
        for path in paths:
            text = path.read_text(encoding="utf-8")
            for number, why in builder_calls(text):
                hits.append(f"{path}:{number}: {why}")
            for number, line in enumerate(text.splitlines(), 1):
                if line.lstrip().startswith(("//", "*", "/*")):
                    continue
                for pattern, why in DENYLIST:
                    if pattern.search(line):
                        hits.append(f"{path}:{number}: {why}\n    {line.strip()}")
    return hits


if __name__ == "__main__":
    if len(sys.argv) < 2:
        raise SystemExit("usage: check_min_api.py DIR [DIR ...]")
    found = scan(sys.argv[1:])
    if found:
        print("\n".join(found))
        print(f"\n{len(found)} call(s) need a newer Android than minSdk 26. Fix them or the build fails.")
        raise SystemExit(1)
    print("No API calls above minSdk 26 found")
