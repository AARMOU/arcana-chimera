#!/usr/bin/env python3
"""Read-only compiled-DEX inventory for an owner-controlled Chimera APK.

No third-party dependencies, decompilation, network or private class-name export.
Package counts and allowlisted bridge method names only. Not a runtime validator.
"""
from __future__ import annotations

import argparse
import hashlib
import json
from pathlib import Path
import re
import struct
import sys
import zipfile

MAX_APK = 1_073_741_824
MAX_DEX = 268_435_456
MAX_ZIP_ENTRIES = 30_000
MAX_DEX_FILES = 64
PREFIXES = {
    "forge": "Lforge/",
    "argentum": "Lcom/wingedsheep/",
    "arcana": "Lcom/arcanatable/",
    "chimera": "Lorg/arcanachimera/",
}
TARGETS = {
    "forge_host": "Lorg/arcanachimera/forgehost/ArcanaForgeHost;",
    "arcana_bridge": "Lcom/arcanatable/engine/LocalArgentumHost;",
    "forge_game_service": "Lcom/arcanatable/engine/ForgeGameService;",
    "forge_game": "Lforge/game/Game;",
    "forge_ai": "Lforge/ai/AiController;",
}
WATCH_METHODS = {
    "handle", "handleUnsafe", "prepare", "shutdown", "defaultAnswer",
    "autoSelectMinimum", "skipPhase", "decisionForInput", "emitDecision",
    "onStartCommand", "onTaskRemoved", "receive-Eg5GKEU",
}


def u32(data: bytes, offset: int) -> int:
    return struct.unpack_from("<I", data, offset)[0]


def uleb(data: bytes, offset: int) -> tuple[int, int]:
    value = 0
    for i in range(5):
        if offset >= len(data):
            raise ValueError("truncated DEX integer")
        b = data[offset]
        offset += 1
        value |= (b & 0x7F) << (7 * i)
        if not b & 0x80:
            return value, offset
    raise ValueError("invalid DEX integer")


def section(data: bytes, count_off: int, ptr_off: int, item_width: int) -> tuple[int, int]:
    count, ptr = u32(data, count_off), u32(data, ptr_off)
    if count > 5_000_000 or ptr > len(data) or count * item_width > len(data) - ptr:
        raise ValueError("invalid DEX index section")
    return count, ptr


def parse_dex(data: bytes) -> dict:
    if len(data) < 0x70 or len(data) > MAX_DEX or not re.match(rb"^dex\n0[0-9]{2}\x00", data[:8]):
        raise ValueError("invalid or unsupported DEX header")
    if u32(data, 0x20) != len(data) or u32(data, 0x24) < 0x70:
        raise ValueError("invalid DEX declared length")
    ns, so = section(data, 0x38, 0x3C, 4)
    nt, to = section(data, 0x40, 0x44, 4)
    nm, mo = section(data, 0x58, 0x5C, 8)
    nc, co = section(data, 0x60, 0x64, 32)
    strings = [u32(data, so + i * 4) for i in range(ns)]
    types = [u32(data, to + i * 4) for i in range(nt)]

    def dex_string(index: int) -> str:
        if index < 0 or index >= ns:
            raise ValueError("invalid DEX string reference")
        offset = strings[index]
        if offset >= len(data):
            raise ValueError("invalid DEX string offset")
        _, at = uleb(data, offset)
        finish = data.find(b"\x00", at, min(len(data), at + 2048))
        if finish < 0:
            raise ValueError("invalid DEX string terminator")
        return data[at:finish].decode("utf-8", "replace")

    result = {
        "class_definitions": nc,
        "package_counts": {k: 0 for k in PREFIXES},
        "targets": {},
    }
    for i in range(nc):
        ci = u32(data, co + i * 32)
        if ci >= nt:
            raise ValueError("invalid DEX class reference")
        desc = dex_string(types[ci])
        for name, prefix in PREFIXES.items():
            if desc.startswith(prefix):
                result["package_counts"][name] += 1
                break
        for alias, wanted in TARGETS.items():
            if desc != wanted:
                continue
            off = u32(data, co + i * 32 + 24)
            methods = []
            if off:
                static_count, off = uleb(data, off)
                instance_count, off = uleb(data, off)
                direct_count, off = uleb(data, off)
                virtual_count, off = uleb(data, off)
                if sum((static_count, instance_count, direct_count, virtual_count)) > 150_000:
                    raise ValueError("unreasonable DEX class data counts")
                for j in range(static_count + instance_count):
                    _, off = uleb(data, off)
                    _, off = uleb(data, off)
                for count in (direct_count, virtual_count):
                    last_method = 0
                    for j in range(count):
                        delta, off = uleb(data, off)
                        _, off = uleb(data, off)
                        _, off = uleb(data, off)
                        last_method += delta
                        if last_method >= nm:
                            raise ValueError("invalid DEX method reference")
                        name_idx = u32(data, mo + last_method * 8 + 4)
                        name = dex_string(name_idx)
                        if name in WATCH_METHODS:
                            methods.append(name)
            if alias in result["targets"]:
                raise ValueError("duplicate target class")
            result["targets"][alias] = sorted(set(methods))
    return result


def inspect(path: Path) -> dict:
    if path.suffix.lower() != ".apk" or not path.is_file() or not (0 < path.stat().st_size <= MAX_APK):
        raise ValueError("invalid APK input")
    total = 0
    counts = {k: 0 for k in PREFIXES}
    targets = {}
    with zipfile.ZipFile(path) as archive:
        if len(archive.infolist()) > MAX_ZIP_ENTRIES:
            raise ValueError("too many APK entries")
        files = sorted((f for f in archive.infolist() if re.fullmatch(r"classes(?:[2-9]|[1-9][0-9]+)?\.dex", f.filename)), key=lambda f: f.filename)
        if not files or len(files) > MAX_DEX_FILES:
            raise ValueError("invalid DEX member count")
        for file in files:
            if file.file_size > MAX_DEX or file.file_size == 0:
                raise ValueError("DEX member outside size bounds")
            data = archive.read(file)
            part = parse_dex(data)
            total += part["class_definitions"]
            for key in counts:
                counts[key] += part["package_counts"][key]
            for key, methods in part["targets"].items():
                if key in targets:
                    raise ValueError("duplicate cross-DEX target")
                targets[key] = methods
    digest = hashlib.sha256()
    with path.open("rb") as stream:
        for chunk in iter(lambda: stream.read(1 << 20), b""):
            digest.update(chunk)
    return {
        "schema": 1, "kind": "chimera-dex-inventory", "apk_sha256": digest.hexdigest(),
        "dex_files": len(files), "class_definitions": total, "package_class_definitions": counts,
        "allowlisted_targets": {key: {"present": key in targets, "watch_methods": targets.get(key, [])} for key in TARGETS},
        "limitations": "DEX class definitions, not execution or reachability; bundled libraries may be unused and methods may be called reflectively.",
    }


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("apk", help="private local APK path (never included in output)")
    parser.add_argument("--json", action="store_true")
    args = parser.parse_args()
    try:
        report = inspect(Path(args.apk))
    except (OSError, ValueError, struct.error, zipfile.BadZipFile):
        print(json.dumps({"status": "invalid_apk_or_dex"}), file=sys.stderr)
        return 2
    print(json.dumps(report, indent=None if args.json else 2, sort_keys=True))
    return 0


if __name__ == "__main__":
    sys.exit(main())
