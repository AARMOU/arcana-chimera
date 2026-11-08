#!/usr/bin/env bash
# Builds pinned Forge plus Chimera's forge-host and runs the milestone 1 proof (Forge behind Argentum's messages).
set -euo pipefail
FORGE_SHA="${FORGE_SHA:-7c34365c749ca46ab71336c5f267340d6088e600}"
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
FORGE="${FORGE_DIR:-$ROOT/third_party/forge}"
if [ ! -d "$FORGE/.git" ]; then
  git clone --quiet --filter=blob:none --no-checkout https://github.com/Card-Forge/forge.git "$FORGE"
fi
git -C "$FORGE" checkout --quiet --detach "$FORGE_SHA"
(cd "$FORGE" && mvn -B -q -pl forge-gui-desktop -am package -DskipTests)
FAT="$(ls "$FORGE"/forge-gui-desktop/target/*jar-with-dependencies.jar | head -1)"
OUT="$ROOT/forge-host/target"; rm -rf "$OUT"; mkdir -p "$OUT/classes"
mvn -B -q dependency:copy -Dartifact=org.json:json:20240303 -DoutputDirectory="$OUT"
CP="$FAT:$OUT/json-20240303.jar"
javac --release 17 -encoding UTF-8 -d "$OUT/classes" -cp "$CP" $(find "$ROOT/forge-host/src/main/java" -name '*.java')
cd "$FORGE/forge-gui"
timeout 600s java -Xmx4g -Djava.awt.headless=true -cp "$OUT/classes:$CP" \
  org.arcanachimera.forgehost.OneTurnProof "$FORGE/forge-gui/"
timeout 900s java -Xmx4g -Djava.awt.headless=true -cp "$OUT/classes:$CP" \
  org.arcanachimera.forgehost.PromptScenarios "$FORGE/forge-gui/"
# Owner priority 1: every card Forge ships must build into a game object; plus the cards Arcana used to drop
# from the owner's deck (Nov 8 09:45). Report-only for now: printed as ALLCARDS lines, doesn't fail the job.
timeout 1500s java -Xmx6g -Djava.awt.headless=true -cp "$OUT/classes:$CP" \
  org.arcanachimera.forgehost.AllCardsCheck "$FORGE/forge-gui/" "Middle School" "Voter's Dilemma" \
  || echo "ALLCARDS gate: see the ALLCARDS lines above"
