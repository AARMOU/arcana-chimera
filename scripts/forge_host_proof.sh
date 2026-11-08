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
timeout 60s java -Djava.awt.headless=true -cp "$OUT/classes:$CP" \
  org.arcanachimera.forgehost.ProtocolGuardSelfTest
timeout 60s java -Djava.awt.headless=true -cp "$OUT/classes:$CP" \
  org.arcanachimera.forgehost.BughuntTraceSelfTest
# Opt in locally with CHIMERA_TRACE=1. Public CI defaults to no per-action log emission.
TRACE_ARG=()
if [[ "${CHIMERA_TRACE:-0}" == "1" ]]; then TRACE_ARG=(-Dchimera.bughunt.trace=true); fi
timeout 600s java "${TRACE_ARG[@]}" -Xmx4g -Djava.awt.headless=true -cp "$OUT/classes:$CP" \
  org.arcanachimera.forgehost.OneTurnProof "$FORGE/forge-gui/"
timeout 900s java "${TRACE_ARG[@]}" -Xmx4g -Djava.awt.headless=true -cp "$OUT/classes:$CP" \
  org.arcanachimera.forgehost.PromptScenarios "$FORGE/forge-gui/"
# 4-player Commander startup and priority/handoff through the actual Arcana adapter.
timeout 300s java "${TRACE_ARG[@]}" -Xmx4g -Djava.awt.headless=true -cp "$OUT/classes:$CP" \
  org.arcanachimera.forgehost.FourSeatProof "$FORGE/forge-gui/"
# Owner priority 1: every card Forge ships must build into a game object; plus the cards Arcana used to drop
# from the owner's deck (Nov 8 09:45). Report-only for now: printed as ALLCARDS lines, doesn't fail the job.
# Every card in Arcana's bundled decks (precons + AI Commander decks), read at build time from the pinned
# Arcana commit (nothing copied into Chimera), must resolve to a Forge card through the host's own lookup.
ARCANA_SHA="${ARCANA_SHA:-$(sed -n 's/^ARCANA_SHA="\${ARCANA_SHA:-\([0-9a-f]*\)}"/\1/p' "$ROOT/scripts/build_arcana_forge_apk.sh")}"
ADECKS="$OUT/arcana-decks"; rm -rf "$ADECKS"; mkdir -p "$ADECKS"
git clone --quiet --filter=blob:none --no-checkout https://github.com/AARMOU/arcana_table.git "$ADECKS/repo"
git -C "$ADECKS/repo" -c advice.detachedHead=false checkout --quiet "$ARCANA_SHA" -- android/app/src/main/assets/precons android/app/src/main/assets/argentum/commander-decks.json
python3 - "$ADECKS/repo/android/app/src/main/assets" > "$ADECKS/names.txt" <<'PY'
import json, re, sys, pathlib
root = pathlib.Path(sys.argv[1]); names = set()
for f in (root / "precons").glob("*.txt"):
    for line in f.read_text().splitlines():
        m = re.match(r"\s*\d+x?\s+(.+?)\s*$", line)
        if m: names.add(m.group(1))
for d in json.loads((root / "argentum/commander-decks.json").read_text()):
    names.add(d["commander"]); names.update(d["cards"])
print("\n".join(sorted(names)))
PY
echo "Arcana decks at $ARCANA_SHA: $(wc -l < "$ADECKS/names.txt") distinct card names"
timeout 1500s java -Xmx6g -Djava.awt.headless=true -cp "$OUT/classes:$CP" \
  org.arcanachimera.forgehost.AllCardsCheck "$FORGE/forge-gui/" "@$ADECKS/names.txt" \
  # now a gate: every Forge card must build and every bundled deck card must resolve
