#!/usr/bin/env bash
# Explicitly invoked local probe; no Arcana access, CI, release or deployment.
set -euo pipefail
ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
FORGE_SHA="583714b8bfbd9cb04f8bc39ad95861190caf7691"
WORKTREE="$ROOT/third_party/forge"
REPORTS="$ROOT/reports"
for binary in git java mvn python3; do
  if ! command -v "$binary" >/dev/null 2>&1; then
    echo "Missing required command: $binary" >&2
    exit 2
  fi
done
mkdir -p "$ROOT/third_party" "$REPORTS"
if [ ! -d "$WORKTREE/.git" ]; then
  git clone --quiet --filter=blob:none --no-checkout https://github.com/Card-Forge/forge.git "$WORKTREE"
fi
git -C "$WORKTREE" fetch --quiet --depth 1 origin "$FORGE_SHA"
git -C "$WORKTREE" checkout --quiet --detach "$FORGE_SHA"
actual="$(git -C "$WORKTREE" rev-parse HEAD)"
if [ "$actual" != "$FORGE_SHA" ]; then
  echo "Unexpected Forge source revision $actual" >&2
  exit 3
fi
{
  date -u +"Timestamp UTC: %Y-%m-%dT%H:%M:%SZ"
  java -version 2>&1 | head -n 3
  mvn -version | head -n 4
} | tee "$REPORTS/environment.txt"
start="$(date +%s)"
set +e
(cd "$WORKTREE" && mvn -B -pl forge-ai -am test) 2>&1 | tee "$REPORTS/forge-build.log"
status=${PIPESTATUS[0]}
set -e
duration=$(( $(date +%s) - start ))
python3 "$ROOT/scripts/collect_maven_results.py" \
  --root "$WORKTREE" --out "$REPORTS/forge-build.json" \
  --sha "$FORGE_SHA" --exit-code "$status" --duration-seconds "$duration"
if [ "$status" -ne 0 ]; then
  echo "Forge build failed; see reports/forge-build.log" >&2
  exit "$status"
fi
echo "Forge Maven build/test completed. This is NOT proof of gameplay or Android readiness."
