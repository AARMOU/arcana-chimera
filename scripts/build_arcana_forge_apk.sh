#!/usr/bin/env bash
# Builds Arcana's real app with Forge as its rules engine (Chimera overlay), as a debug APK.
# Arcana source is staged read-only from a pinned public commit into ignored third_party/; nothing is pushed to Arcana.
set -euo pipefail
ARCANA_SHA="${ARCANA_SHA:-f6a149cb202587e5aa8128191beb630a7d94935d}"
FORGE_SHA="${FORGE_SHA:-7c34365c749ca46ab71336c5f267340d6088e600}"
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
# Verify host-survival patch invariants before downloading and compiling Forge.
python3 -m unittest discover -s "$ROOT/tests" -p "test_chimera_host_survival.py" -v
python3 -m unittest discover -s "$ROOT/tests" -p "test_engine_running_default.py" -v
python3 -m unittest discover -s "$ROOT/tests" -p "test_chimera_legal_navigation.py" -v
# The approved animations are identical-sized transparent GIF canvases. Fail
# before a costly Forge compile when either is absent or unexpectedly altered.
for gif in gooseclippy_idle.gif gooseclippy_react.gif; do
  [ -s "$ROOT/arcana-overlay/app/src/main/assets/$gif" ] || {
    echo "ERROR: Missing approved GooseClippy asset $gif"
    exit 1
  }
done
(cd "$ROOT/arcana-overlay/app/src/main/assets" &&
 printf '%s  %s\n' \
  '16dde375e89d564b90b160cb460ee42b69dfd2763caaff6d91ce0e3ca8979e92' 'gooseclippy_idle.gif' \
  '295cb5ad25e3656f9a7d22beb44b9e54633cd26dc61c709cd11a350ea6c11f3b' 'gooseclippy_react.gif' |
 sha256sum --check --status) || {
    echo "ERROR: GooseClippy animation mismatch. Require corrected, size-matched 240x360 GIFs."
    exit 1
 }
WORK="$ROOT/third_party"; mkdir -p "$WORK"
ARCANA="$WORK/arcana"; FORGE="$WORK/forge"
[ -d "$ARCANA/.git" ] || git clone --quiet --filter=blob:none --no-checkout https://github.com/AARMOU/arcana_table.git "$ARCANA"
git -C "$ARCANA" checkout --quiet --detach "$ARCANA_SHA"
[ -d "$FORGE/.git" ] || git clone --quiet --filter=blob:none --no-checkout https://github.com/Card-Forge/forge.git "$FORGE"
git -C "$FORGE" checkout --quiet --detach "$FORGE_SHA"

echo "== Forge libraries"
LIBS="$WORK/forge-libs"; rm -rf "$LIBS"; mkdir -p "$LIBS"
(cd "$FORGE" && mvn -B -q -pl forge-gui -am package dependency:copy-dependencies -DskipTests -DincludeScope=runtime -DoutputDirectory="$LIBS")
cp "$FORGE"/forge-gui/target/forge-gui-*.jar "$LIBS"/
rm -f "$LIBS"/*-sources.jar "$LIBS"/*-tests.jar "$LIBS"/json-*.jar   # Android ships org.json
rm -f "$LIBS"/slf4j-api-*.jar "$LIBS"/listenablefuture-*.jar   # Arcana already ships slf4j and Guava
# Keep only the newest version when Forge's modules pull in two versions of one library.
for base in $(ls "$LIBS" | sed -E 's/-[0-9][0-9.]*(-[A-Za-z0-9.]+)?\.jar$//' | sort | uniq -d); do
  ls "$LIBS"/"$base"-[0-9]*.jar | sort -V | head -n -1 | xargs -r rm -f
done
# GPL audit (Nov 10): jUPnP is CDDL-1.0, which cannot be combined with GPLv3 Forge in one app. It only serves
# Forge's own UPnP network hosting, which Chimera never starts; Jetty and the servlet API are only its transport.
# forge-host/src/stubs supplies the one type Forge's interfaces name. third_party_notices.py refuses these JARs.
rm -f "$LIBS"/org.jupnp*.jar "$LIBS"/jetty-*.jar "$LIBS"/javax.servlet-api-*.jar
STUB="$ROOT/forge-host/src/stubs/java/org/jupnp/UpnpServiceConfiguration.java"
ls -1 "$LIBS"; du -sh "$LIBS"

echo "== Forge data (card scripts as one zip Forge reads directly)"
DATA="$WORK/forge-data"; rm -rf "$DATA"; mkdir -p "$DATA/res/cardsfolder"
RES="$FORGE/forge-gui/res"
du -sh "$RES"/* | sort -h | tail -15
rsync -a --exclude cardsfolder --exclude adventure --exclude skins --exclude sound --exclude music \
  --exclude puzzle --exclude quest --exclude conquest --exclude 'planechase' --exclude images "$RES"/ "$DATA/res"/
(cd "$RES/cardsfolder" && zip -q -r -9 "$DATA/res/cardsfolder/cardsfolder.zip" . -x 'cardsfolder.zip')
(cd "$DATA" && rm -f "$WORK/forge-res.zip" && zip -q -r -1 "$WORK/forge-res.zip" res)
du -sh "$DATA/res" "$WORK/forge-res.zip"

echo "== Overlay onto Arcana"
APP="$ARCANA/android/app"
cp "$ROOT/arcana-overlay/app/src/main/java/com/arcanatable/engine/LocalArgentumHost.kt" "$APP/src/main/java/com/arcanatable/engine/LocalArgentumHost.kt"
mkdir -p "$APP/src/main/java/org/arcanachimera/forgehost" "$APP/libs/forge" "$APP/src/main/assets"
cp "$ROOT/forge-host/src/main/java/org/arcanachimera/forgehost/ArcanaForgeHost.java" "$APP/src/main/java/org/arcanachimera/forgehost/"
rm -f "$APP"/libs/forge/*.jar   # a reused staging tree must not keep JARs this build no longer ships
cp "$LIBS"/*.jar "$APP/libs/forge/"
mkdir -p "$APP/src/main/java/org/jupnp" && cp "$STUB" "$APP/src/main/java/org/jupnp/"
cp "$WORK/forge-res.zip" "$APP/src/main/assets/forge-res.zip"
python3 "$ROOT/scripts/patch_arcana_for_forge.py" "$APP" # owner: no Argentum card check; Forge decides
python3 "$ROOT/scripts/patch_commander_art_editor.py" "$APP" # Chimera: commander art picker and persistent previews
echo "== Chimera official WotC Commander decklists (bundled offline; no Arcana writes)"
mkdir -p "$APP/src/main/assets/precons"
python3 "$ROOT/scripts/build_wotc_commander_precons.py" --out "$APP/src/main/assets/precons/wotc-commander-precons.json"
cp "$ROOT/arcana-overlay/app/src/main/java/com/arcanatable/ChimeraPrecons.kt" "$APP/src/main/java/com/arcanatable/"
cp "$ROOT/arcana-overlay/app/src/main/java/com/arcanatable/ChimeraPreconTree.kt" "$APP/src/main/java/com/arcanatable/"
python3 "$ROOT/scripts/patch_commander_precons.py" "$APP"
python3 "$ROOT/scripts/patch_ai_deck_selector.py" "$APP" # Chimera #39: saved/imported and WotC precons for AI lobby seats
python3 "$ROOT/scripts/patch_offline_ai_decks.py" "$APP" # Chimera #39: independent offline Forge AI decks
echo "== Card pictures for Forge's cards (Scryfall bulk data; skipped without network)"
python3 "$ROOT/scripts/scryfall_images.py" "$WORK/scryfall-images.tsv" || : > "$WORK/scryfall-images.tsv"
echo "== Forge's cards for Arcana's deck import and editor (ForgeCatalogExport)"
(cd "$FORGE" && mvn -B -q dependency:copy -Dartifact=org.json:json:20240303 -DoutputDirectory="$WORK/jvm")
mkdir -p "$WORK/jvm/classes"
javac --release 17 -encoding UTF-8 -nowarn -d "$WORK/jvm/classes" -cp "$LIBS/*:$WORK/jvm/json-20240303.jar" \
  "$STUB" \
  "$ROOT/forge-host/src/main/java/org/arcanachimera/forgehost/ArcanaForgeHost.java" \
  "$ROOT/forge-host/src/main/java/org/arcanachimera/forgehost/ForgeCatalogExport.java"
(cd "$FORGE/forge-gui" && java -Xmx3g -Djava.awt.headless=true -cp "$WORK/jvm/classes:$LIBS/*:$WORK/jvm/json-20240303.jar" \
  org.arcanachimera.forgehost.ForgeCatalogExport "$FORGE/forge-gui/" "$APP/src/main/assets/argentum" "images=$WORK/scryfall-images.tsv" \
  "$APP/src/main/assets/argentum/all-cards.index.tsv" "$APP/src/main/assets/argentum/custom-cards.index.tsv")
wc -l "$APP/src/main/assets/argentum/forge-cards.index.tsv"
echo "== ManaBox deck link reader on the owner's deck (informational)"
python3 "$ROOT/scripts/manabox_check.py" "https://manabox.app/decks/AaEEwuDaehSDHbcRSSDwBw" || true
echo "== One small name -> picture file for games (mulligan screen, table)"
python3 "$ROOT/scripts/chimera_pictures.py" "$APP/src/main/assets/argentum"
cp "$ROOT/arcana-overlay/app/src/main/java/com/arcanatable/ChimeraDeckImport.kt" "$APP/src/main/java/com/arcanatable/"
cp "$ROOT/arcana-overlay/app/src/main/java/com/arcanatable/engine/ForgeGameService.kt" "$APP/src/main/java/com/arcanatable/engine/"
cp "$ROOT/arcana-overlay/app/src/main/java/com/arcanatable/engine/ChimeraPictures.kt" "$APP/src/main/java/com/arcanatable/engine/"
cp "$ROOT/arcana-overlay/app/src/main/java/com/arcanatable/engine/ChimeraLoading.kt" "$APP/src/main/java/com/arcanatable/engine/"
cp "$ROOT/arcana-overlay/app/src/main/java/com/arcanatable/ChimeraArt.kt" "$APP/src/main/java/com/arcanatable/"
cp "$ROOT/arcana-overlay/app/src/main/java/com/arcanatable/ChimeraHandFit.kt" "$APP/src/main/java/com/arcanatable/"
cp "$ROOT/arcana-overlay/app/src/main/java/com/arcanatable/ChimeraDrawer.kt" "$APP/src/main/java/com/arcanatable/"
for f in ChimeraView ChimeraHand ChimeraHandCards ChimeraBackdrops ChimeraPresets ChimeraPlay ChimeraStudio ChimeraSkins ChimeraChoice ChimeraTerminal ChimeraTutorialBoards ChimeraHaptics ChimeraFeatures ChimeraXp ChimeraLegal GooseClippyLines GooseClippyFeature ChimeraRoomHostGuardService; do
  cp "$ROOT/arcana-overlay/app/src/main/java/com/arcanatable/$f.kt" "$APP/src/main/java/com/arcanatable/"
done
mkdir -p "$APP/src/main/assets/chimera_xp" && cp "$ROOT"/arcana-overlay/app/src/main/assets/chimera_xp/*.jpg "$APP/src/main/assets/chimera_xp/" # XP Desktop wallpapers
# Copy independent assistant code, tests and both approved animations into the
# temporary donor. No files are written to the actual Arcana Git repository.
mkdir -p "$APP/src/test/java/com/arcanatable"
cp "$ROOT"/arcana-overlay/app/src/test/java/com/arcanatable/GooseClippy*Test.kt "$APP/src/test/java/com/arcanatable/"
cp "$ROOT"/arcana-overlay/app/src/test/java/com/arcanatable/ChimeraLegal*Test.kt "$APP/src/test/java/com/arcanatable/"
for gif in gooseclippy_idle.gif gooseclippy_react.gif; do
  cp "$ROOT/arcana-overlay/app/src/main/assets/$gif" "$APP/src/main/assets/"
done
# Every Chimera skin, including debug builds, includes the actual GPLv3 text.
# Source-access metadata remains explicitly UNPUBLISHED until an audited
# version-matched archive has been hosted and release checks are approved.
mkdir -p "$APP/src/main/assets/legal"
cp "$ROOT/LICENSE" "$APP/src/main/assets/legal/GPL-3.0.txt"
cp "$ROOT/LICENSE_SCOPE.md" "$APP/src/main/assets/legal/CHIMERA-LICENSE-SCOPE.md"
cp "$ROOT/third_party_licenses/Forge-GPL-3.0.txt" "$APP/src/main/assets/legal/Forge-GPL-3.0.txt"
cp "$ROOT/docs/GPLV3_RELEASE_READINESS.md" "$APP/src/main/assets/legal/GPL-RELEASE-NOTICES.md"
# SOURCE-ACCESS.txt and THIRD-PARTY-NOTICES.txt are written just before the Gradle build, below.
python3 "$ROOT/scripts/patch_arcana_nov9.py" "$APP" # owner's Nov 9 list: drawer, Commander, presets, fan/catalogue, backgrounds, log detail
python3 "$ROOT/scripts/patch_arcana_nov9c.py" "$APP" # owner's third Nov 9 list: choices, pictures, combat, mulligan grid, home picture
python3 "$ROOT/scripts/patch_arcana_tutorial.py" "$APP" # the tutorial's text for Arcana with Forge (owner, Nov 10)
python3 "$ROOT/scripts/patch_arcana_wording.py" "$APP" # owner, Nov 9: no "Argentum" on screen (fails the build if any is left)
python3 "$ROOT/scripts/patch_chimera_host_survival.py" "$APP" # host lock + directed relay recovery; Worker deploy separate
python3 "$ROOT/scripts/patch_chimera_restart_host.py" "$APP" # Host Lobby ends a frozen game's open session (owner, Nov 11)
python3 "$ROOT/scripts/patch_chimera_always_yes.py" "$APP" # ALWAYS on trigger questions = Forge's always-accept (owner, Nov 11)
python3 "$ROOT/scripts/patch_view_table.py" "$APP" # owner, Nov 12: declare attackers LATER reads VIEW TABLE
python3 "$ROOT/scripts/patch_chimera_legal_navigation.py" "$APP" # notices and source in Options; no intrusive menu shortcuts
python3 "$ROOT/scripts/patch_engine_running_default.py" "$APP" # engine processing banner OFF unless owner opts in
# Foreground service: keeps the Forge game alive while the player is in another app.
python3 - "$APP/src/main/AndroidManifest.xml" <<'PY'
import sys; p = sys.argv[1]; s = open(p).read()
if 'ForgeGameService' not in s:
    s = s.replace('<application', '<uses-permission android:name="android.permission.FOREGROUND_SERVICE_SPECIAL_USE" />\n    <application', 1)
    s = s.replace('</application>', '    <service android:name=".engine.ForgeGameService" android:exported="false" android:foregroundServiceType="specialUse">\n            <property android:name="android.app.PROPERTY_SPECIAL_USE_FGS_SUBTYPE" android:value="Keeps an offline card game running while the app is in the background" />\n        </service>\n    </application>', 1)
    open(p, 'w').write(s)
PY
grep -q 'android:largeHeap' "$APP/src/main/AndroidManifest.xml" || sed -i 's/<application/<application android:largeHeap="true"/' "$APP/src/main/AndroidManifest.xml"
cat >> "$APP/build.gradle.kts" <<'GRADLE'

// CHIMERA OVERLAY: Forge replaces Argentum as the rules engine; installs beside real Arcana.
android {
    defaultConfig { applicationId = "org.arcanachimera.arcanaforge" }
    packaging { resources { excludes += setOf("META-INF/*", "**/module-info.class") } }
}
dependencies { implementation(fileTree(mapOf("dir" to "libs/forge", "include" to listOf("*.jar")))) }
configurations.all { exclude(group = "com.google.guava", module = "listenablefuture") } // Forge brings full Guava
GRADLE

echo "sdk.dir=${ANDROID_HOME:-/usr/local/lib/android/sdk}" > "$ARCANA/android/local.properties"
echo "== Licence notices and source location packaged in the app"
mkdir -p "$ROOT/reports/license-audit"
(cd "$ARCANA/android" && ./gradlew --no-daemon --max-workers=2 :app:dependencies --configuration releaseRuntimeClasspath) > "$ROOT/reports/license-audit/runtime.txt"
python3 "$ROOT/scripts/third_party_notices.py" --jars "$APP/libs/forge" --jars "$APP/libs" \
  --gradle-report "$ROOT/reports/license-audit/runtime.txt" \
  --out "$APP/src/main/assets/legal/THIRD-PARTY-NOTICES.txt" --report "$ROOT/reports/license-audit/notices.json"
python3 "$ROOT/scripts/write_source_access.py" --app "$APP"
echo "== Build"
(cd "$ARCANA/android" && ./gradlew --no-daemon --max-workers=2 :custom-cards:exportCards :app:assembleDebug "-Parcana.debugLabel=Arcana Forge B142 Studio")
APK="$(ls "$APP"/build/outputs/apk/debug/*.apk | head -1)"
ls -la "$APK"
echo "APK=$APK" >> "${GITHUB_ENV:-/dev/null}"
