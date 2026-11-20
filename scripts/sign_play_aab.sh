#!/usr/bin/env bash
# Sign an UNSIGNED Chimera Play AAB that CI already built. Local only: no Forge build, no upload.
#
#   1. Run the "Arcana + Forge APK build" workflow with a Play versionCode and download the
#      artifact "chimera-play-aab-UNSIGNED-with-source" into an empty folder.
#   2. In a private shell set CHIMERA_UPLOAD_KEYSTORE, CHIMERA_UPLOAD_STORE_PASSWORD,
#      CHIMERA_UPLOAD_KEY_PASSWORD and CHIMERA_UPLOAD_ALIAS=arcana-upload.
#   3. bash scripts/sign_play_aab.sh /path/to/that/folder
#
# The keystore must be the ORIGINAL Play upload key: its certificate is checked against the
# fingerprint pinned in scripts/play_release_tools.py before and after signing. Passwords are
# read from the environment by keytool/jarsigner and are never printed or written to disk.
set -euo pipefail
ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
DIR="${1:?Folder holding the downloaded unsigned AAB, SHA256SUMS and source archive}"
cd "$DIR"
[ -s SHA256SUMS ] || { echo "BLOCKED: SHA256SUMS missing; download the whole artifact"; exit 2; }
sha256sum --check --quiet SHA256SUMS || { echo "BLOCKED: a downloaded file does not match SHA256SUMS"; exit 2; }
mapfile -t UNSIGNED < <(ls -- *-UNSIGNED.aab 2>/dev/null)
[ "${#UNSIGNED[@]}" -eq 1 ] || { echo "BLOCKED: expected exactly one *-UNSIGNED.aab here"; exit 2; }
IN="${UNSIGNED[0]}"; OUT="${IN%-UNSIGNED.aab}-SIGNED.aab"
[ ! -e "$OUT" ] || { echo "BLOCKED: $OUT already exists"; exit 2; }
python3 "$ROOT/scripts/play_release_tools.py" preflight
jarsigner -keystore "$CHIMERA_UPLOAD_KEYSTORE" \
  -storepass:env CHIMERA_UPLOAD_STORE_PASSWORD -keypass:env CHIMERA_UPLOAD_KEY_PASSWORD \
  -sigalg SHA256withRSA -digestalg SHA-256 -signedjar "$OUT" "$IN" "$CHIMERA_UPLOAD_ALIAS" > /dev/null
python3 "$ROOT/scripts/play_release_tools.py" verify --bundle "$OUT"
sha256sum "$OUT" | tee SIGNED-SHA256SUM
[ ! -s RELEASE-IDENTITY.txt ] || cat RELEASE-IDENTITY.txt
echo "SIGNED, NOT UPLOADED: $DIR/$OUT"
echo "Before uploading to Play: publish chimera-source-CANDIDATE.tar.gz and SHA256SUMS at the source_url above."
