#!/usr/bin/env python3
"""Chimera-only offline official WotC Commander precon catalog compiler.

MTGJSON supplies original decklists; Forge supplies rules and AI. This never
changes Arcana, and does not copy card artwork or Oracle text.
"""
from __future__ import annotations
import argparse
import datetime as dt
import hashlib
import json
import re
import tarfile
import tempfile
import time
import urllib.request
from concurrent.futures import ThreadPoolExecutor
from pathlib import Path

API = "https://mtgjson.com/api/v5"
AGENT = "Arcana-Chimera-WotC-Commander-Catalog/1.0 (private offline decklist compiler)"
SIX = {"Forces of the Imperium", "Necron Dynasties", "The Ruinous Powers",
       "Tyranid Swarm", "Eldrazi Unbound", "Eldrazi Incursion"}
TYPES = {"Commander Deck", "Starter Commander Deck"}
# MTGJSON labels hundreds of small Secret Lair card drops the same way as
# full Commander products. Only the known official 100-card releases belong here.
SECRET_LAIR_COMMANDER = {
    "Heads I Win, Tails You Lose",
    "Angels: They're Just Like Us but Cooler and with Wings",
    "From Cute to Brute",
    "Raining Cats and Dogs",
    "20 Ways to Win",
    "Everyone's Invited!",
    "Hatsune Miku",
    "Goblin Storm",
    "Odds and Ends",
}
COLORS = "WUBRG"

def download(url: str, dst: Path, max_bytes: int = 250_000_000) -> None:
    if not url.startswith(API + "/"):
        raise ValueError("Refusing non-MTGJSON source")
    for attempt in range(3):
        try:
            req = urllib.request.Request(url, headers={"User-Agent": AGENT, "Accept": "application/octet-stream"})
            with urllib.request.urlopen(req, timeout=90) as src, dst.open("wb") as out:
                n = 0
                while chunk := src.read(1024 * 1024):
                    n += len(chunk)
                    if n > max_bytes:
                        raise ValueError("Download size cap exceeded")
                    out.write(chunk)
            return
        except (OSError, TimeoutError):
            if attempt == 2:
                raise
            time.sleep(attempt + 1)

def verified(name: str, folder: Path) -> tuple[Path, str]:
    file = folder / name
    checksum = folder / (name + ".sha256")
    download(f"{API}/{name}", file)
    download(f"{API}/{name}.sha256", checksum, 512)
    match = re.search(r"\b[0-9a-fA-F]{64}\b", checksum.read_text())
    if not match:
        raise ValueError(f"Missing checksum: {name}")
    expected = match.group().lower()
    with file.open("rb") as reader:
        actual = hashlib.file_digest(reader, "sha256").hexdigest()
    if actual != expected:
        raise ValueError(f"Checksum mismatch: {name}")
    return file, actual

def set_names(path: Path) -> dict[str, str]:
    data = json.loads(path.read_text("utf-8"))["data"]
    rows = data.values() if isinstance(data, dict) else data
    return {x["code"].upper(): x["name"] for x in rows
            if isinstance(x, dict) and x.get("code") and x.get("name")}

def cards(items: list[dict]) -> list[tuple[str, int]]:
    grouped: dict[str, int] = {}
    for c in items:
        name = str(c.get("name", "")).strip()
        qty = int(c.get("count", 0))
        if not name or not 1 <= qty <= 100 or "\n" in name or "\r" in name:
            raise ValueError("Invalid MTGJSON card name/count")
        grouped[name] = grouped.get(name, 0) + qty
    return list(grouped.items())

def is_commander_product(row: dict) -> bool:
    kind = row.get("type")
    return kind in TYPES or (kind == "Secret Lair Drop" and row.get("name") in SECRET_LAIR_COMMANDER)

def make_deck(raw: dict, names: dict[str, str], file: str, cutoff: str) -> dict | None:
    commander_cards = raw.get("commander")
    if not is_commander_product(raw) or not isinstance(commander_cards, list) or not commander_cards:
        return None
    release = raw.get("releaseDate") or ""
    if release and release > cutoff:
        return None
    commander = cards(commander_cards)
    main = cards(raw.get("mainBoard") or [])
    total = sum(q for _, q in commander + main)
    if total == 101 and len(commander) == 1:
        cmd = commander[0][0]
        main = [(n, q - 1 if n == cmd else q) for n, q in main]
        main = [(n, q) for n, q in main if q > 0]
        total = sum(q for _, q in commander + main)
    if total != 100:
        return None
    code = str(raw.get("code") or "").upper()
    title = str(raw.get("name") or "").strip()
    if not title or not code:
        return None
    identity = set()
    for card in commander_cards:
        identity.update(card.get("colorIdentity") or [])
    color = "".join(c for c in COLORS if c in identity)
    lines = (["Commander"] + [f"{q} {n}" for n, q in commander] +
             ["Deck"] + [f"{q} {n}" for n, q in main])
    return {"id": file, "name": title, "setName": names.get(code, code), "setCode": code,
            "releaseDate": release, "commander": commander[0][0], "colors": color,
            "decklist": "\n".join(lines) + "\n"}

def build(archive: Path, sets: Path, cutoff: str) -> dict:
    names = set_names(sets)
    decks = []
    seen = set()
    with tarfile.open(archive, "r:gz") as tar:
        for member in tar:
            if not member.isfile() or not member.name.endswith(".json"):
                continue
            if member.size > 15_000_000:
                raise ValueError("Unexpectedly large deck record")
            filename = Path(member.name).stem
            if filename in seen:
                raise ValueError(f"Duplicate MTGJSON filename: {filename}")
            seen.add(filename)
            src = tar.extractfile(member)
            if src is None:
                continue
            raw = json.load(src)
            raw = raw.get("data", raw)
            if not isinstance(raw, dict):
                continue
            entry = make_deck(raw, names, filename, cutoff)
            if entry is not None:
                decks.append(entry)
    missing = SIX - {x["name"] for x in decks}
    if missing:
        raise ValueError(f"Missing original six official decklists: {sorted(missing)}")
    if len(decks) < 100:
        raise ValueError(f"Only {len(decks)} decks; refusing incomplete catalog")
    if len({x["id"] for x in decks}) != len(decks):
        raise ValueError("Duplicate deck IDs")
    decks.sort(key=lambda d: (-int(d["releaseDate"][:4] or "0"),
                              d["setName"].casefold(), d["name"].casefold(), d["id"]))
    return {"schemaVersion": 1, "decks": decks}

def build_remote(index: Path, sets: Path, cutoff: str, folder: Path,
                 fetcher=download) -> dict:
    """Download only Commander products; the full archive is hundreds of MB."""
    listing = json.loads(index.read_text("utf-8"))["data"]
    if isinstance(listing, dict):
        listing = list(listing.values())
    selected = [row for row in listing if isinstance(row, dict)
                and is_commander_product(row)
                and (not row.get("releaseDate") or row["releaseDate"] <= cutoff)]
    if not 100 <= len(selected) <= 400:
        raise ValueError(f"Suspicious Commander catalog size: {len(selected)}")
    if len({row["fileName"] for row in selected}) != len(selected):
        raise ValueError("Duplicate MTGJSON index filenames")
    names = set_names(sets)

    def one(row: dict) -> dict | None:
        file = row["fileName"]
        if not re.fullmatch(r"[A-Za-z0-9_-]+", file):
            raise ValueError(f"Unexpected MTGJSON filename: {file}")
        local = folder / (file + ".json")
        fetcher(f"{API}/decks/{file}.json", local, 8_000_000)
        payload = json.loads(local.read_text("utf-8"))
        raw = payload.get("data", payload)
        if raw.get("name") != row["name"] or raw.get("code") != row["code"]:
            raise ValueError(f"Deck index/source mismatch: {file}")
        return make_deck(raw, names, file, cutoff)

    # Bounded concurrency; 4 modest requests, no server-side bulk extraction.
    with ThreadPoolExecutor(max_workers=4) as pool:
        entries = list(pool.map(one, selected))
    decks = [entry for entry in entries if entry is not None]
    missing = SIX - {entry["name"] for entry in decks}
    if missing:
        raise ValueError(f"Official original six missing: {sorted(missing)}")
    if len(decks) < 100:
        raise ValueError(f"Only {len(decks)} complete Commander precons")
    decks.sort(key=lambda d: (-int(d["releaseDate"][:4] or "0"),
                              d["setName"].casefold(), d["name"].casefold(), d["id"]))
    return {"schemaVersion": 1, "decks": decks}


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("--archive", type=Path, help="Offline tar fixture or legacy archive")
    parser.add_argument("--sets", type=Path)
    parser.add_argument("--out", type=Path, required=True)
    parser.add_argument("--as-of", default=dt.date.today().isoformat())
    args = parser.parse_args()
    with tempfile.TemporaryDirectory(prefix="chimera-precon-") as folder:
        tmp = Path(folder)
        sets, sets_sha = ((args.sets, sha(args.sets)) if args.sets
                          else verified("SetList.json", tmp))
        if args.archive:
            catalog = build(args.archive, sets, args.as_of)
            source_sha = sha(args.archive)
            source_file = "AllDeckFiles.tar.gz"
        else:
            index, source_sha = verified("DeckList.json", tmp)
            catalog = build_remote(index, sets, args.as_of, tmp)
            source_file = "DeckList.json"
        catalog["source"] = {"provider": "MTGJSON", "license": "MIT - https://mtgjson.com/license/",
                             "catalogIndex": source_file, "indexSha256": source_sha,
                             "setListSha256": sets_sha, "asOf": args.as_of}
        args.out.parent.mkdir(parents=True, exist_ok=True)
        args.out.write_text(json.dumps(catalog, ensure_ascii=False, separators=(",", ":")) + "\n",
                            encoding="utf-8")
        print(f"WOTC COMMANDER PRECONS: {len(catalog['decks'])} bundled (original six included)")


def sha(path: Path) -> str:
    with path.open("rb") as reader:
        return hashlib.file_digest(reader, "sha256").hexdigest()

if __name__ == "__main__":
    main()
