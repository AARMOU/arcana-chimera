#!/usr/bin/env bash
# Chimera Experiment 003 — one FOUR-player AI Commander match.
# Manual public-only standard runner or local machine; no Arcana changes or paid services.
set -euo pipefail
ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
FORGE_SHA="583714b8bfbd9cb04f8bc39ad95861190caf7691"
FORGE="$ROOT/third_party/forge"
REPORTS="$ROOT/reports"
RUN_ID="$(date -u +%Y%m%dT%H%M%SZ)"
LOG="$REPORTS/four-commander-${RUN_ID}.log"
STATUS="$REPORTS/four-commander-${RUN_ID}.status.txt"

for binary in git java mvn timeout tee grep; do
  command -v "$binary" >/dev/null || { echo "MISSING: $binary" >&2; exit 2; }
done
mkdir -p "$ROOT/third_party" "$REPORTS"
if [ ! -d "$FORGE/.git" ]; then
  git clone --quiet --filter=blob:none --no-checkout https://github.com/Card-Forge/forge.git "$FORGE"
fi
git -C "$FORGE" fetch --quiet --depth 1 origin "$FORGE_SHA"
git -C "$FORGE" checkout --quiet --detach "$FORGE_SHA"
test "$(git -C "$FORGE" rev-parse HEAD)" = "$FORGE_SHA" || { echo 'PIN_MISMATCH' >&2; exit 3; }

DECK_DIR="$FORGE/forge-gui/res/quest/precons"
# Forge desktop resolves res/ relative to its working directory in snapshot builds.
# Fail before the Maven build if the required English resources are missing.
if [ ! -f "$FORGE/forge-gui/res/languages/en-US.properties" ]; then
  echo 'MISSING_ENGLISH_RESOURCES: forge-gui/res/languages/en-US.properties' >&2
  exit 4
fi
for deck in 'Buckle Up.dck' 'Counterpunch.dck' 'Undead Unleashed.dck' 'Reap the Tides.dck'; do
  test -f "$DECK_DIR/$deck" || { echo "MISSING_DECK: $deck" >&2; exit 4; }
done

{
  echo "Forge SHA: $FORGE_SHA"
  echo "One game; FOUR AI Commander seats; seed 8074; 300s internal timeout"
  echo "Decks: Buckle Up / Counterpunch / Undead Unleashed / Reap the Tides"
  echo "Started UTC: $(date -u +%FT%TZ)"
  java -version 2>&1 | head -3
  mvn -version | head -4
} > "$STATUS"

echo "Building Forge desktop assembly. This may use substantial local CPU/disk."
set +e
(cd "$FORGE" && timeout 1500s mvn -B -pl forge-gui-desktop -am package -DskipTests) > "$REPORTS/four-commander-build-${RUN_ID}.log" 2>&1
build_code=$?
set -e
if [ "$build_code" -ne 0 ]; then
  echo "BUILD_FAILED exit=$build_code" | tee -a "$STATUS"
  echo "Evidence: $REPORTS/four-commander-build-${RUN_ID}.log"
  exit "$build_code"
fi

shopt -s nullglob
jars=("$FORGE"/forge-gui-desktop/target/*jar-with-dependencies.jar)
if [ "${#jars[@]}" -ne 1 ]; then
  echo "EXPECTED_ONE_ASSEMBLY_JAR_FOUND_${#jars[@]}" | tee -a "$STATUS"
  exit 5
fi

echo "Running one four-AI Commander match; require exactly one winner and three eliminated opponents."
set +e
# Release/snapshot builds use cwd for assets (GuiDesktop.getAssetsDir() == "").
# Starting in forge-gui also works for versions that use ../forge-gui/.
(cd "$FORGE/forge-gui" && timeout 420s java -Xmx4g -Djava.awt.headless=true -jar "${jars[0]}" \
  sim -d 'Buckle Up.dck' 'Counterpunch.dck' 'Undead Unleashed.dck' 'Reap the Tides.dck' -D "$DECK_DIR" -n 1 -f commander -s 8074 -c 300 -q) > "$LOG" 2>&1
game_code=$?
set -e

echo "Game process exit: $game_code" | tee -a "$STATUS"
echo "Game log: $LOG" | tee -a "$STATUS"

# An exit code 0 or a winning message alone cannot prove four-player gameplay.
# Forge emits these result lines for each winning/losing seat (quiet mode).
wins="$(grep -Ec '^Game Outcome: Ai\([1-4]\)-.* has won because ' "$LOG" || true)"
# Forge reports commander-damage elimination as "has lost due to", not "because".
losses="$(grep -Ec '^Game Outcome: Ai\([1-4]\)-.* has lost (because |due to )' "$LOG" || true)"
echo "Winner outcomes: $wins; eliminated opponents: $losses" | tee -a "$STATUS"

if [ "$game_code" -eq 0 ] \
  && grep -Eq '^Ai\(1\)-.* vs Ai\(2\)-.* vs Ai\(3\)-.* vs Ai\(4\)-.* - one game of Commander seed 8074$' "$LOG" \
  && [ "$wins" -eq 1 ] \
  && [ "$losses" -eq 3 ] \
  && grep -Eq '^Game Result: Game 1 ended in [0-9]+ ms\. .+ has won!' "$LOG" \
  && ! grep -Eq 'Stopping slow match as draw|Uncaught exception|Could not load deck|match cannot start|OutOfMemoryError|Exception in thread' "$LOG"; then
  echo 'PASS: four AI Commander seats; one winner and three eliminated opponents' | tee -a "$STATUS"
  grep -E '^Game Result: Game 1 ended in |^Game Outcome: |^Match Result:' "$LOG" | tee -a "$STATUS"
else
  echo 'FAIL_OR_INCONCLUSIVE: four-player Commander game not verified' | tee -a "$STATUS"
  tail -n 80 "$LOG"
  exit 6
fi
