#!/usr/bin/env bash
# Pulls the room server's live branch. A new version is built and fully tested
# in its own folder first; the running server is only switched if every test passes.
set -euo pipefail
BASE=/opt/arcana-rooms
. "$BASE/config.env"   # REPO_URL, BRANCH
exec 9>"$BASE/update.lock"; flock -n 9 || exit 0
LATEST="$(git ls-remote "$REPO_URL" "refs/heads/$BRANCH" | cut -f1)"
[ -n "$LATEST" ] || { echo "Branch $BRANCH not found"; exit 1; }
CURRENT="$(cat "$BASE/current/.commit" 2>/dev/null || true)"
[ "$LATEST" != "$CURRENT" ] || exit 0
[ "$LATEST" != "$(cat "$BASE/failed-commit" 2>/dev/null || true)" ] || exit 0
NEW="$BASE/releases/$LATEST"
rm -rf "$NEW"; mkdir -p "$BASE/releases"
git clone --quiet --depth 1 --filter=blob:none --sparse --branch "$BRANCH" "$REPO_URL" "$NEW"
git -C "$NEW" sparse-checkout set room-server tests
test "$(git -C "$NEW" rev-parse HEAD)" = "$LATEST" || { echo "Branch moved during update; retry next run"; rm -rf "$NEW"; exit 0; }
cd "$NEW/room-server"
if ! { npm ci --no-audit --no-fund && npm test; } > "$BASE/last-update.log" 2>&1; then
  echo "$LATEST" > "$BASE/failed-commit"
  echo "Update $LATEST FAILED its tests; still running $CURRENT. See $BASE/last-update.log"
  rm -rf "$NEW"; exit 1
fi
echo "$LATEST" > "$NEW/.commit"
chown -R root:root "$NEW"
ln -sfn "$NEW" "$BASE/current.next" && mv -T "$BASE/current.next" "$BASE/current"
install -m 0644 "$NEW/room-server/deploy/arcana-rooms.service" /etc/systemd/system/arcana-rooms.service
systemctl daemon-reload
systemctl restart arcana-rooms
echo "Room server updated to $LATEST"
# Keep the two newest releases for a quick manual rollback.
ls -1dt "$BASE"/releases/* | tail -n +3 | xargs -r rm -rf
