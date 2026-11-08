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
(cd "$FORGE" && mvn -B -q -pl forge-gui -am install -DskipTests)
V="$(cd "$FORGE" && mvn -B -q -N help:evaluate -Dexpression=project.version -DforceStdout)"
echo "Forge $FORGE_SHA version $V"
(cd "$ROOT/forge-host" && mvn -B -q package -Dforge.version="$V")
cd "$FORGE/forge-gui"
timeout 600s java -Xmx4g -Djava.awt.headless=true \
  -cp "$ROOT/forge-host/target/forge-host-0.1.0.jar:$ROOT/forge-host/target/lib/*" \
  org.arcanachimera.forgehost.OneTurnProof "$FORGE/forge-gui/"
