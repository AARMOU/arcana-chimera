#!/usr/bin/env bash
# Local/offline recovery of the GitHub *encrypted* CI handoff into an APK.
# Never commit/store/send the private key, and never push the resulting APK to GitHub.
set -euo pipefail
if [ "$#" -ne 3 ]; then
  echo "Usage: $0 <unpacked-artifact-directory> <private-RSA-key> <output.apk>" >&2
  exit 2
fi
bundle="$1"
key="$2"
apk="$3"
for entry in FrankenArcana.apk.enc password.rsa APK.sha256; do
  test -s "$bundle/$entry" || { echo "MISSING_ENCRYPTED_BUNDLE_FILE: $entry" >&2; exit 3; }
done
test -s "$key" || { echo "PRIVATE_KEY_NOT_AVAILABLE" >&2; exit 4; }
command -v openssl >/dev/null
command -v sha256sum >/dev/null
command -v unzip >/dev/null
test ! -e "$apk" || { echo "REFUSING_TO_OVERWRITE_EXISTING_APK" >&2; exit 5; }
umask 077
password="$(mktemp)"
trap 'rm -f "$password"' EXIT
openssl pkeyutl -decrypt -inkey "$key" \
  -pkeyopt rsa_padding_mode:oaep \
  -pkeyopt rsa_oaep_md:sha256 \
  -pkeyopt rsa_mgf1_md:sha256 \
  -in "$bundle/password.rsa" -out "$password"
openssl enc -d -aes-256-cbc -salt -pbkdf2 -iter 250000 \
  -pass file:"$password" \
  -in "$bundle/FrankenArcana.apk.enc" -out "$apk" || {
    rm -f "$apk"
    exit 6
  }
expected="$(tr -d '\r\n[:space:]' < "$bundle/APK.sha256")"
actual="$(sha256sum "$apk" | cut -d' ' -f1)"
if ! [[ "$expected" =~ ^[0-9a-fA-F]{64}$ ]] || [ "$actual" != "$expected" ]; then
  echo "APK_CHECKSUM_MISMATCH" >&2
  rm -f "$apk"
  exit 7
fi
if ! unzip -Z -1 "$apk" | grep -qx 'AndroidManifest.xml'; then
  echo "NOT_AN_APK_ZIP" >&2
  rm -f "$apk"
  exit 8
fi
echo "APK_DECRYPTED_VERIFIED: $apk"
echo "SHA256: $actual"
echo "No APK is uploaded by this script; deliver privately to the owner's Google Drive."
