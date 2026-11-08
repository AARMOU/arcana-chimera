#!/usr/bin/env python3
"""Local-only, read-only APK provenance and REA toolchain preflight for Chimera.

No APK extraction, external commands during inspect/compare, uploads or log bodies.
A fingerprint is NOT proof that the correct Forge classes or rules are present.
"""
from __future__ import annotations

import argparse
import hashlib
import json
import os
from pathlib import Path
import re
import shutil
import subprocess
import sys
import zipfile

MAX_APK_BYTES = 1_073_741_824
MAX_ENTRIES = 30_000
MAX_MEMBER_BYTES = 268_435_456
COMPONENTS = ("dex", "native", "manifest", "resources", "assets", "other")


def sha256_file(path: Path) -> str:
    digest = hashlib.sha256()
    with path.open("rb") as stream:
        for block in iter(lambda: stream.read(1 << 20), b""):
            digest.update(block)
    return digest.hexdigest()


def component(name: str) -> str:
    if re.fullmatch(r"classes(?:\d+)?\.dex", name):
        return "dex"
    if name.startswith("lib/") and name.endswith(".so"):
        return "native"
    if name == "AndroidManifest.xml":
        return "manifest"
    if name == "resources.arsc":
        return "resources"
    if name.startswith("assets/"):
        return "assets"
    return "other"


def snapshot(path: Path) -> dict:
    if path.suffix.lower() != ".apk" or not path.is_file():
        raise ValueError("input must be a readable .apk file")
    size = path.stat().st_size
    if size < 1 or size > MAX_APK_BYTES:
        raise ValueError("APK size outside bounded inspection limits")
    if not zipfile.is_zipfile(path):
        raise ValueError("APK is not a valid ZIP container")
    groups = {kind: [] for kind in COMPONENTS}
    with zipfile.ZipFile(path) as archive:
        infos = archive.infolist()
        if len(infos) > MAX_ENTRIES:
            raise ValueError("APK contains too many archive entries")
        seen = set()
        for info in infos:
            if info.filename in seen:
                raise ValueError("APK has duplicate archive entry names")
            seen.add(info.filename)
            if info.is_dir():
                continue
            if info.file_size < 0 or info.file_size > MAX_MEMBER_BYTES:
                raise ValueError("APK contains an oversized archive member")
            kind = component(info.filename)
            # Metadata fingerprint inventories all members; only code and manifest
            # are streamed and independently hashed. Central-directory CRC isn't
            # a cryptographic validation of content for other components.
            content_digest = ""
            if kind in ("dex", "native", "manifest", "resources"):
                digest = hashlib.sha256()
                with archive.open(info) as stream:
                    for chunk in iter(lambda: stream.read(1 << 20), b""):
                        digest.update(chunk)
                content_digest = digest.hexdigest()
            groups[kind].append((info.filename, info.file_size, info.CRC, content_digest))
    if not groups["manifest"] or not groups["dex"]:
        raise ValueError("APK lacks a manifest or classes.dex")
    result = {}
    for kind, records in groups.items():
        stable = json.dumps(sorted(records), ensure_ascii=True, separators=(",", ":"))
        result[kind] = {"count": len(records), "inventory_sha256": hashlib.sha256(stable.encode()).hexdigest()}
    return {
        "schema": 1,
        "kind": "chimera-apk-evidence",
        "sha256": sha256_file(path),
        "bytes": size,
        "zip_entries": len(infos),
        "components": result,
        "limitations": "Static provenance only; no package identity, JNI binding, runtime correctness or card coverage claim.",
    }


def compare(left: dict, right: dict) -> dict:
    return {
        "schema": 1, "kind": "chimera-apk-comparison",
        "before_sha256": left["sha256"], "after_sha256": right["sha256"],
        "same_binary": left["sha256"] == right["sha256"],
        "component_changes": {key: left["components"][key] != right["components"][key] for key in COMPONENTS},
        "limitations": "Changes prove different archive inventories, not a Forge behavior regression or fix.",
    }


def _version_ok(version: str) -> bool:
    match = re.fullmatch(r"v?(\d+)\.(\d+)\.(\d+)", version.strip())
    if not match:
        return False
    major, minor, patch = map(int, match.groups())
    return (major == 22 and (minor, patch) >= (23, 4) or
            major == 24 and (minor, patch) >= (22, 1) or
            major == 26 and (minor, patch) >= (12, 1))


def _node_version() -> str | None:
    if not shutil.which("node"):
        return None
    try:
        r = subprocess.run(["node", "--version"], capture_output=True, text=True, timeout=5, check=False)
        return r.stdout.strip() if r.returncode == 0 else None
    except (OSError, subprocess.TimeoutExpired):
        return None


def doctor() -> dict:
    version = _node_version()
    jadx_path = os.getenv("REA_JADX_MCP_JAR", "")
    checks = {
        "supported_node": bool(version and _version_ok(version)),
        "rea_cli_on_path": shutil.which("rea") is not None,
        "java_on_path": shutil.which("java") is not None,
        "javac_on_path": shutil.which("javac") is not None,
        "jadx_mcp_jar_configured": bool(jadx_path and Path(jadx_path).is_file()),
        "adb_on_path_optional": shutil.which("adb") is not None,
    }
    required = ("supported_node", "rea_cli_on_path", "java_on_path", "javac_on_path", "jadx_mcp_jar_configured")
    return {"schema": 1, "kind": "chimera-rea-preflight", "ready_for_android_probe": all(checks[k] for k in required),
            "checks": checks, "note": "Local executable presence only. After setup, run rea doctor --provider jadx --json; successful preflight is not proof APK analysis works."}


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("mode", choices=("inspect", "compare", "doctor"))
    parser.add_argument("paths", nargs="*", help="private local .apk paths; never printed")
    parser.add_argument("--json", action="store_true", help="compact machine-readable report")
    args = parser.parse_args(argv)
    try:
        need = {"inspect": 1, "compare": 2, "doctor": 0}[args.mode]
        if len(args.paths) != need:
            raise ValueError(f"{args.mode} expects {need} APK path(s)")
        if args.mode == "inspect":
            output = snapshot(Path(args.paths[0]))
        elif args.mode == "compare":
            output = compare(snapshot(Path(args.paths[0])), snapshot(Path(args.paths[1]))
        else:
            output = doctor()
    except (OSError, ValueError, zipfile.BadZipFile, RuntimeError) as exc:
        # Error output contains only the error type, never local paths or APK contents.
        print(json.dumps({"status": "input_error", "type": type(exc).__name__}), file=sys.stderr)
        return 2
    print(json.dumps(output, sort_keys=True, indent=None if args.json else 2))
    return 1 if args.mode == "doctor" and not output["ready_for_android_probe"] else 0


if __name__ == "__main__":
    raise SystemExit(main())
