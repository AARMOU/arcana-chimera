#!/usr/bin/env bash
# FrankenArcana: live Arcana-style Android game table over pinned Forge rules/AI.
# All writes are in Chimera's ignored third_party/; never reads or writes Arcana.
# Builds a local throwaway debug APK. NEVER publishes/uploads binaries.
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
FORGE_SHA="583714b8bfbd9cb04f8bc39ad95861190caf7691"
FORGE="$ROOT/third_party/forge-device"
APP_ID="org.arcanachimera.frankenarcana"
MAVEN_PLUGIN_VERSION="4.6.2"

for binary in git python3 java mvn curl timeout sha256sum; do
  command -v "$binary" >/dev/null || { echo "MISSING: $binary" >&2; exit 2; }
done
test -n "${ANDROID_SDK_ROOT:-}" && test -d "$ANDROID_SDK_ROOT" || {
  echo "MISSING_ANDROID_SDK: set ANDROID_SDK_ROOT" >&2
  exit 2
}

mkdir -p "$ROOT/third_party" "$ROOT/reports"
echo "Building Arcana-style game UI driven by Forge's real game. No Arcana writes."
if [ ! -d "$FORGE/.git" ]; then
  git clone --quiet --filter=blob:none --no-checkout https://github.com/Card-Forge/forge.git "$FORGE"
fi
git -C "$FORGE" fetch --quiet --depth 1 origin "$FORGE_SHA"
git -C "$FORGE" checkout --quiet --detach -f "$FORGE_SHA"
test "$(git -C "$FORGE" rev-parse HEAD)" = "$FORGE_SHA" || {
  echo PIN_MISMATCH >&2
  exit 3
}
python3 "$ROOT/scripts/prepare_frankenforge.py" "$FORGE"

# Forge's official Android build requires a pinned fork of the Maven plugin.
# This follows the project's own 4.6.2 build recipe.
PLUG="$HOME/.m2/repository/com/simpligility/maven/plugins/android-maven-plugin/$MAVEN_PLUGIN_VERSION"
mkdir -p "$PLUG"
for ext in jar pom; do
  output="$PLUG/android-maven-plugin-$MAVEN_PLUGIN_VERSION.$ext"
  if [ ! -s "$output" ]; then
    curl --fail --location --retry 2 --silent --show-error \
      "https://github.com/Card-Forge/android-maven-plugin/releases/download/$MAVEN_PLUGIN_VERSION/android-maven-plugin-$MAVEN_PLUGIN_VERSION.$ext" \
      -o "$output"
  fi
  test -s "$output" || { echo "MISSING_MAVEN_PLUGIN_$ext" >&2; exit 4; }
done

echo "Building pinned Forge libraries before the Android APK."
(
  cd "$FORGE"
  timeout 1800s mvn -B -Dmaven.test.skip=true install
)
echo "Building Android APK using upstream android-test-build profile."
build_android_once() {
  (
    cd "$FORGE"
    timeout 2400s mvn -U -B -P android-test-build verify \
      -Dandroid.sdk.path="$ANDROID_SDK_ROOT" \
      -Dandroid.buildToolsVersion=35.0.0 \
      -Dmaven.test.skip=true
  )
}
# Maven Central occasionally resets a dependency transfer. Retry just that
# transient network error once; never repeat compilation or source errors.
android_log="$(mktemp "$ROOT/third_party/android-maven.XXXXXXXX.log")"
trap 'rm -f "$android_log"' EXIT
if build_android_once 2>&1 | tee "$android_log"; then
  echo "ANDROID_MAVEN_SUCCESS_FIRST_ATTEMPT"
elif grep -Eiq 'Could not transfer artifact.*(Connection reset|Read timed out|Connection timed out|502 Bad Gateway|503 Service Unavailable|Temporary failure in name resolution)' "$android_log"; then
  echo "TRANSIENT_MAVEN_NETWORK_ERROR: one retry after 15 seconds; no loop"
  sleep 15
  build_android_once
  echo "ANDROID_MAVEN_SUCCESS_AFTER_SINGLE_NETWORK_RETRY"
else
  echo "ANDROID_MAVEN_NON_NETWORK_FAILURE: not retrying" >&2
  exit 10
fi

shopt -s nullglob
# The pinned Forge uber-apk-signer outputs *-aligned-debugSigned.apk (v2/v3 signed).
apks=("$FORGE"/forge-gui-android/target/*-aligned-debugSigned.apk)
if [ "${#apks[@]}" -ne 1 ]; then
  echo "EXPECTED_ONE_DEBUG_SIGNED_APK_FOUND_${#apks[@]}" >&2
  find "$FORGE/forge-gui-android/target" -maxdepth 1 -type f -name "*.apk" -printf "%f\n" >&2
  exit 5
fi
apk="${apks[0]}"
AAPT="$ANDROID_SDK_ROOT/build-tools/35.0.0/aapt"
test -x "$AAPT" || { echo MISSING_AAPT >&2; exit 5; }
badging="$("$AAPT" dump badging "$apk")"
echo "$badging" | grep -q "package: name='$APP_ID'" || {
  echo "WRONG_APK_PACKAGE: expected $APP_ID" >&2
  exit 6
}
echo "$badging" | grep -E '^package:|^application-label:' | head -4
sha256sum "$apk"
echo "APK_COMPILED: ${apk##*/}"
echo "NOT_DELIVERED: no artifact uploads, public releases, or deployments"
echo "A CI runner discards its local APK when this job ends."
