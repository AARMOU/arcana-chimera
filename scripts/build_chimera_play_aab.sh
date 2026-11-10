#!/usr/bin/env bash
# Local-only signed Google Play AAB preparation. Never uploads, releases or merges.
# Set the required CHIMERA_UPLOAD_* variables in a trusted local
# signing environment. The reported UPLOAD certificate SHA-256 is pinned in
# scripts/play_release_tools.py; CHIMERA_EXPECTED_UPLOAD_CERT_SHA256 is an
# optional cross-check, never an override. Never use the app-signing key.
# Never commit or print any credentials or keystore contents.
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

# Ship the GPL text and scope with the bundle, even before the in-app
# About/Licenses UI is finished. This alone does NOT satisfy notice duties.
mkdir -p "$APP/src/main/assets/legal"
cp "$ROOT/LICENSE" "$APP/src/main/assets/legal/GPL-3.0.txt"
cp "$ROOT/LICENSE_SCOPE.md" "$APP/src/main/assets/legal/CHIMERA-LICENSE-SCOPE.md"
cp "$ROOT/docs/GPLV3_RELEASE_READINESS.md" "$APP/src/main/assets/legal/GPL-RELEASE-NOTICES.md"

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
