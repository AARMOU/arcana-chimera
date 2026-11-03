#!/usr/bin/env bash
# Fetch Arcana's real Compose UI as a READ-ONLY reference for FrankenArcana builds.
# No pushes, PRs, comments or Actions are ever made in AARMOU/arcana_table.
set -euo pipefail
ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
ARCANA_SHA="05cdd6dae84e7df4c88e630054cdc6a76dfa2964"
SRC="$ROOT/third_party/arcana-ui"
mkdir -p "$ROOT/third_party"
if [ ! -d "$SRC/.git" ]; then
  git -c credential.helper= clone --quiet --filter=blob:none --no-checkout \
    https://github.com/AARMOU/arcana_table.git "$SRC"
fi
# Disable all pushes in the local copy, even if someone later changes credentials.
git -C "$SRC" remote set-url --push origin DISABLED_CHIMERA_NO_ARCANA_WRITES
git -c credential.helper= -C "$SRC" fetch --quiet --depth 1 origin "$ARCANA_SHA"
git -C "$SRC" checkout --quiet --detach --force "$ARCANA_SHA"
test "$(git -C "$SRC" rev-parse HEAD)" = "$ARCANA_SHA" || {
  echo ARCANA_SOURCE_MISMATCH >&2
  exit 3
}
# Sanity check: these ARE the actual Arcana table screens, not a recreated landing page.
for f in TableMainLayout.kt TableBoardViews.kt TableModel.kt BattlefieldLayout.kt; do
  test -s "$SRC/android/app/src/main/java/com/arcanatable/$f" || {
    echo "MISSING_ARCANA_UI: $f" >&2
    exit 4
  }
done
echo "ARCANA_UI_STAGED_READ_ONLY: $ARCANA_SHA"
echo "Arcana UI is staged exclusively in ignored third_party/arcana-ui."
echo "Writes may occur only in Chimera-owned experimental overlays; NEVER push to Arcana."
