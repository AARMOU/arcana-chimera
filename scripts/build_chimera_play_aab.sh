#!/usr/bin/env bash
# Local-only signed Google Play AAB preparation. Never uploads, releases or merges.
# Set the five CHIMERA_UPLOAD_* / CHIMERA_EXPECTED_* variables in a trusted local
# signing environment. Never commit or print any credentials or keystore contents.
set -euo pipefail
ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$ROOT"

: "${CHIMERA_PLAY_VERSION_CODE:?Supply a versionCode greater than ALL existing Play tracks}"
CHIMERA_PLAY_VERSION_NAME="${CHIMERA_PLAY_VERSION_NAME:-0.35.0-chimera-${CHIMERA_PLAY_VERSION_CODE}}"

# Reject absent/mismatched credentials BEFORE the costly Gradle/Forge build.
python3 scripts/play_release_tools.py preflight
echo "Building isolated Chimera donor staging tree; no writes to AARMOU/arcana_table."
bash scripts/build_arcana_forge_apk.sh

STAGED="$ROOT/third_party/arcana/android"
APP="$STAGED/app"
python3 scripts/play_release_tools.py prepare \
  --gradle "$APP/build.gradle.kts" \
  --version-code "$CHIMERA_PLAY_VERSION_CODE" \
  --version-name "$CHIMERA_PLAY_VERSION_NAME"

(cd "$STAGED" && ./gradlew --no-daemon --max-workers=2 :app:bundleRelease)
AAB="$APP/build/outputs/bundle/release/app-release.aab"
python3 scripts/play_release_tools.py verify --bundle "$AAB"

# Candidate only: exact modified sources and pinned Forge/Arcana checkouts from
# this AAB build. Never upload until the GPL audit is completed.
python3 scripts/gpl_build_pair.py --aab "$AAB" --out "$ROOT/reports/gpl-play-aab"

printf '\nLOCALLY BUILT, NOT RELEASED: %s\n' "$AAB"
sha256sum "$AAB"
echo "HOLD: This AAB cannot be distributed until corresponding GPL source, dependency notices, in-app license/source access, and human approval are complete."
echo "HOLD: Verify package com.arcanatable, versionCode, and target API from the actual AAB with bundletool and Play Console before any upload."
