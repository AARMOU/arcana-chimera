#!/usr/bin/env python3
"""Write assets/legal/SOURCE-ACCESS.txt for the build that is about to be compiled.

The app shows this on Licenses & source > Source. It names where the corresponding
source for this exact version is offered. The archive's checksum is published beside
the archive: it cannot be inside the app, because the archive records the app's hash.

CHIMERA_SOURCE_URL wins when set. In GitHub Actions the default is the run's own page,
where the source archive is uploaded next to the APK. Otherwise the record stays
UNPUBLISHED and the app says so.
"""
import argparse
import os
from pathlib import Path
import re
import sys


def effective_version_name(gradle_text):
    names = re.findall(r'^\s*versionName\s*=\s*"([^"]+)"', gradle_text, flags=re.M)
    if not names:
        raise ValueError("versionName not found in build.gradle.kts")
    return names[-1]


def source_url(env):
    url = env.get("CHIMERA_SOURCE_URL", "").strip()
    if not url and env.get("GITHUB_RUN_ID") and env.get("GITHUB_REPOSITORY"):
        url = "https://github.com/%s/actions/runs/%s" % (env["GITHUB_REPOSITORY"], env["GITHUB_RUN_ID"])
    if url and (not url.startswith("https://") or any(c.isspace() for c in url)):
        raise ValueError("Source URL must be a single https:// address")
    return url


def record(version, url):
    if not url:
        return "SOURCE_VERSION_NAME=UNPUBLISHED\nSOURCE_URL=UNPUBLISHED\n"
    return "SOURCE_VERSION_NAME=%s\nSOURCE_URL=%s\n" % (version, url)


def main(argv=None):
    p = argparse.ArgumentParser(description=__doc__)
    p.add_argument("--app", type=Path, required=True, help="staged android/app directory")
    a = p.parse_args(argv)
    try:
        version = effective_version_name((a.app / "build.gradle.kts").read_text(encoding="utf-8"))
        text = record(version, source_url(os.environ))
    except (OSError, ValueError) as exc:
        print("BLOCKED: " + str(exc), file=sys.stderr)
        return 2
    out = a.app / "src/main/assets/legal/SOURCE-ACCESS.txt"
    out.parent.mkdir(parents=True, exist_ok=True)
    out.write_text(text, encoding="utf-8")
    print(text.strip())
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
