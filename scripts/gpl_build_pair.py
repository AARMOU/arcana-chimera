#!/usr/bin/env python3
"""Create an APK-matched source candidate from same-job Chimera/Arcana/Forge trees.

This does NOT certify GPL compliance or upload artifacts. A legal and build audit
is required before distributing any covered binary to another person.
"""
import argparse
import hashlib
import io
import json
import os
from pathlib import Path, PurePosixPath
import subprocess
import tarfile

ROOT = Path(__file__).resolve().parents[1]
ARCANA_PIN = "a5c428ad3da42b7325adf6630238ec992fee3113"
FORGE_PIN = "7c34365c749ca46ab71336c5f267340d6088e600"
ANDROID_INPUTS = ("android/app/src/main/java/", "android/app/src/main/assets/",
                  "android/app/src/main/res/", "android/app/libs/forge/")
SKIP_DIRS = {".git", ".gradle", "node_modules", "__pycache__", "build", "target", ".venv"}
SECRET_NAMES = {"local.properties", "google-services.json", "secrets.properties",
                ".env", ".env.local", "credentials.json", "id_rsa"}
SECRET_SUFFIXES = {".pem", ".key", ".p12", ".pfx", ".keystore", ".jks", ".apk", ".aab"}
# The repo ships this public-only RSA handoff key. Never whitelist other PEMs.
PUBLIC_PEM_ALLOWLIST = {"scripts/delivery/frankenarcana-public.pem"}
MAX_BYTES = 8 * 1024**3


def git(repo, *args):
    p = subprocess.run(["git", "-C", str(repo), *args], capture_output=True)
    if p.returncode:
        raise RuntimeError("Git checkout error in " + repo.name + ": " +
                           p.stderr.decode("utf-8", "replace")[:200])
    return p.stdout


def revision(repo):
    return git(repo, "rev-parse", "HEAD").decode().strip()


def sha256(path):
    h = hashlib.sha256()
    with path.open("rb") as stream:
        for block in iter(lambda: stream.read(1024 * 1024), b""):
            h.update(block)
    return h.hexdigest()


def files(repo, name):
    tracked = {os.fsdecode(x) for x in git(repo, "ls-files", "-z", "--cached").split(b"\0") if x}
    candidates = set(tracked)
    if name == "arcana":
        # Capture both tracked source and generated / copied app inputs ignored by Git.
        for prefix in ANDROID_INPUTS:
            folder = repo / prefix
            if folder.is_dir():
                candidates.update(
                    p.relative_to(repo).as_posix() for p in folder.rglob("*")
                    if p.is_file() or p.is_symlink()
                )
    for rel in sorted(candidates):
        path = PurePosixPath(rel)
        if path.is_absolute() or ".." in path.parts:
            raise RuntimeError("Unsafe source path: " + rel)
        if any(part in SKIP_DIRS for part in path.parts):
            continue
        full = repo / rel
        permitted_public_pem = (
            name == "chimera" and rel in PUBLIC_PEM_ALLOWLIST
            and full.is_file() and full.stat().st_size < 16384
            and full.read_bytes().lstrip().startswith(b"-----BEGIN PUBLIC KEY-----")
        )
        if path.name.lower() in SECRET_NAMES or (
            path.suffix.lower() in SECRET_SUFFIXES and not permitted_public_pem
        ):
            raise RuntimeError("Potential credential/release binary in source: " + rel)
        if full.is_symlink():
            raise RuntimeError("Symlink needs human audit: " + rel)
        if full.is_file():
            yield rel, full, (rel not in tracked)


def make_pair(*, apk, chimera, arcana, forge, out, expected_arcana, expected_forge, artifact_type="apk"):
    if artifact_type not in ("apk", "aab"):
        raise RuntimeError("Unsupported binary format")
    if apk.suffix.lower() != "." + artifact_type:
        raise RuntimeError("Binary extension does not match requested artifact type")
    if not apk.is_file() or not apk.stat().st_size:
        raise RuntimeError("Completed nonempty APK is required")
    if not (chimera / "third_party_licenses/Forge-GPL-3.0.txt").is_file():
        raise RuntimeError("Forge GPLv3 text missing")
    repos = {"chimera": chimera, "arcana": arcana, "forge": forge}
    commits = {name: revision(folder) for name, folder in repos.items()}
    for name, expected in (("arcana", expected_arcana), ("forge", expected_forge)):
        if commits[name] != expected:
            raise RuntimeError(name + " revision mismatch: expected " + expected +
                               " but got " + commits[name])
    out.mkdir(parents=True, exist_ok=True)
    archive = out / "chimera-source-CANDIDATE.tar.gz"
    if archive.resolve() == apk.resolve():
        raise RuntimeError("Source archive cannot overwrite APK")
    counts = {}
    added = {}
    total = 0
    apk_hash = sha256(apk)
    try:
        with tarfile.open(archive, "w:gz") as dst:
            for name, folder in repos.items():
                num, extra = 0, 0
                for rel, path, untracked in files(folder, name):
                    size = path.stat().st_size
                    total += size
                    if total > MAX_BYTES:
                        raise RuntimeError("Source snapshot exceeds 8 GiB limit")
                    info = tarfile.TarInfo("sources/" + name + "/" + rel)
                    info.mode = 0o644
                    info.mtime = info.uid = info.gid = 0
                    info.size = size
                    with path.open("rb") as src:
                        dst.addfile(info, src)
                    num += 1
                    extra += int(untracked)
                if not num:
                    raise RuntimeError("Missing source files in " + name)
                counts[name] = num
                added[name] = extra
            embedded = {
                "schema": "chimera-gpl-build-pair-v1",
                ("apk_sha256" if artifact_type == "apk" else "aab_sha256"): apk_hash,
                "commits": commits,
                "file_counts": counts,
                "untracked_android_inputs": added["arcana"],
                "warning": "Not release-ready: full dependency/license/rebuild/Play/source-access audit required",
            }
            content = (json.dumps(embedded, indent=2, sort_keys=True) + "\n").encode()
            item = tarfile.TarInfo("BUILD-PROVENANCE.json")
            item.mode = 0o644
            item.mtime = item.uid = item.gid = 0
            item.size = len(content)
            dst.addfile(item, io.BytesIO(content))
        result = {
            **embedded,
            ("apk_name" if artifact_type == "apk" else "aab_name"): apk.name,
            "source_name": archive.name,
            "source_sha256": sha256(archive),
            "source_size": archive.stat().st_size,
            "distribution_approved": False,
            "blockers": [
                "Prove full corresponding source and reproducible build dependencies",
                "Review bundled dependencies and third-party art/content licenses",
                "Show all required copyright and licensing notices",
                "Provide durable corresponding-source access with any APK",
                "Review GPLv3 installation information and Google Play requirements",
                "Record explicit owner and legal/release reviewer signoff",
            ],
        }
        (out / "pair-manifest.json").write_text(json.dumps(result, indent=2, sort_keys=True) +
                                                 "\n", encoding="utf-8")
        return result
    except Exception:
        archive.unlink(missing_ok=True)
        raise


def main():
    ap = argparse.ArgumentParser(description=__doc__)
    binary = ap.add_mutually_exclusive_group(required=True)
    binary.add_argument("--apk", type=Path)
    binary.add_argument("--aab", type=Path)
    ap.add_argument("--chimera", type=Path, default=ROOT)
    ap.add_argument("--arcana", type=Path, default=ROOT / "third_party/arcana")
    ap.add_argument("--forge", type=Path, default=ROOT / "third_party/forge")
    ap.add_argument("--out", type=Path, default=ROOT / "reports/gpl-release")
    ap.add_argument("--expected-arcana", default=os.getenv("ARCANA_SHA", ARCANA_PIN))
    ap.add_argument("--expected-forge", default=os.getenv("FORGE_SHA", FORGE_PIN))
    a = ap.parse_args()
    try:
        m = make_pair(apk=(a.apk or a.aab), chimera=a.chimera, arcana=a.arcana, forge=a.forge,
                      out=a.out, expected_arcana=a.expected_arcana, expected_forge=a.expected_forge,
                      artifact_type=("aab" if a.aab else "apk"))
    except (OSError, RuntimeError) as exc:
        ap.exit(2, "BLOCKED: " + str(exc) + "\n")
    print("Binary SHA-256:", m["aab_sha256"] if a.aab else m["apk_sha256"])
    print("Source candidate SHA-256:", m["source_sha256"])
    print("Distribution: BLOCKED. No APK/AAB uploaded.")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
