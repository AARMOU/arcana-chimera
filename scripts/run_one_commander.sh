#!/usr/bin/env bash
# Chimera Experiment 001 — ONE headless Forge AI-vs-AI Commander match.
# Manual command. No Arcana access, deployments, GitHub Actions or paid tools.
set -euo pipefail
ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
FORGE_SHA="7c34365c749ca46ab71336c5f267340d6088e600"
FORGE="$ROOT/third_party/forge"
REPORTS="$ROOT/reports"
RUN_ID="$(date -u +%Y%m%dT%H%M%SZ)"
LOG="$REPORTS/commander-${RUN_ID}.log"
STATUS="$REPORTS/commander-${RUN_ID}.status.txt"

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
for deck in 'Buckle Up.dck' 'Counterpunch.dck'; do
  test -f "$DECK_DIR/$deck" || { echo "MISSING_DECK: $deck" >&2; exit 4; }
done

{
  echo "Forge SHA: $FORGE_SHA"
  echo "One game; AI vs AI; Commander; seed 8073; 300s internal timeout"
  echo "Decks: Buckle Up / Counterpunch (included in Forge's own resource tree)"
  echo "Started UTC: $(date -u +%FT%TZ)"
  java -version 2>&1 | head -3
  mvn -version | head -4
} > "$STATUS"

echo "Building Forge desktop assembly. This may use substantial local CPU/disk."
set +e
(cd "$FORGE" && timeout 1500s mvn -B -pl forge-gui-desktop -am package -DskipTests) > "$REPORTS/commander-build-${RUN_ID}.log" 2>&1
build_code=$?
set -e
if [ "$build_code" -ne 0 ]; then
  echo "BUILD_FAILED exit=$build_code" | tee -a "$STATUS"
  echo "Evidence: $REPORTS/commander-build-${RUN_ID}.log"
  exit "$build_code"
fi

shopt -s nullglob
jars=("$FORGE"/forge-gui-desktop/target/*jar-with-dependencies.jar)
if [ "${#jars[@]}" -ne 1 ]; then
  echo "EXPECTED_ONE_ASSEMBLY_JAR_FOUND_${#jars[@]}" | tee -a "$STATUS"
  exit 5
fi

echo "Running a real Forge Commander match; winner required for PASS."
set +e
(cd "$FORGE/forge-gui-desktop" && timeout 420s java -Xmx4g -Djava.awt.headless=true -jar "${jars[0]}" \
  sim -d 'Buckle Up.dck' 'Counterpunch.dck' -D "$DECK_DIR" -n 1 -f commander -s 8073 -c 300 -q) > "$LOG" 2>&1
game_code=$?
set -e

echo "Game process exit: $game_code" | tee -a "$STATUS"
echo "Game log: $LOG" | tee -a "$STATUS"

# Forge's own SimulateMatch.simulateSingleMatch emits this line only for a winner.
if [ "$game_code" -eq 0 ] && grep -E '^Game Result: Game 1 ended in [0-9]+ ms\. .+ has won!' "$LOG" >/dev/null \
  && ! grep -E 'Stopping slow match as draw|Uncaught exception|Could not load deck|match cannot start|OutOfMemoryError' "$LOG" >/dev/null; then
  echo 'PASS: AI Commander match finished with winner' | tee -a "$STATUS"
  grep -E '^Game Result: Game 1 ended in ' "$LOG" | tee -a "$STATUS"
else
  echo 'FAIL_OR_INCONCLUSIVE: no verified AI Commander victory' | tee -a "$STATUS"
  tail -n 70 "$LOG"
  exit 6
fi
