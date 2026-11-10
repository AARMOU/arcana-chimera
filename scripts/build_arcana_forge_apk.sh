#!/usr/bin/env bash
# Builds Arcana's real app with Forge as its rules engine (Chimera overlay), as a debug APK.
# Arcana source is staged read-only from a pinned public commit into ignored third_party/; nothing is pushed to Arcana.
set -euo pipefail
ARCANA_SHA="${ARCANA_SHA:-a5c428ad3da42b7325adf6630238ec992fee3113}"
FORGE_SHA="${FORGE_SHA:-7c34365c749ca46ab71336c5f267340d6088e600}"
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
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
cp "$LIBS"/*.jar "$APP/libs/forge/"
cp "$WORK/forge-res.zip" "$APP/src/main/assets/forge-res.zip"
python3 "$ROOT/scripts/patch_arcana_for_forge.py" "$APP" # owner: no Argentum card check; Forge decides
echo "== Chimera official WotC Commander decklists (bundled offline; no Arcana writes)"
mkdir -p "$APP/src/main/assets/precons"
python3 "$ROOT/scripts/build_wotc_commander_precons.py" --out "$APP/src/main/assets/precons/wotc-commander-precons.json"
cp "$ROOT/arcana-overlay/app/src/main/java/com/arcanatable/ChimeraPrecons.kt" "$APP/src/main/java/com/arcanatable/"
cp "$ROOT/arcana-overlay/app/src/main/java/com/arcanatable/ChimeraPreconTree.kt" "$APP/src/main/java/com/arcanatable/"
python3 "$ROOT/scripts/patch_commander_precons.py" "$APP"
echo "== Card pictures for Forge's cards (Scryfall bulk data; skipped without network)"
python3 "$ROOT/scripts/scryfall_images.py" "$WORK/scryfall-images.tsv" || : > "$WORK/scryfall-images.tsv"
echo "== Forge's cards for Arcana's deck import and editor (ForgeCatalogExport)"
(cd "$FORGE" && mvn -B -q dependency:copy -Dartifact=org.json:json:20240303 -DoutputDirectory="$WORK/jvm")
mkdir -p "$WORK/jvm/classes"
javac --release 17 -encoding UTF-8 -nowarn -d "$WORK/jvm/classes" -cp "$LIBS/*:$WORK/jvm/json-20240303.jar" \
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
for f in ChimeraView ChimeraHand ChimeraHandCards ChimeraBackdrops ChimeraPresets ChimeraPlay ChimeraStudio ChimeraSkins; do
  cp "$ROOT/arcana-overlay/app/src/main/java/com/arcanatable/$f.kt" "$APP/src/main/java/com/arcanatable/"
done
python3 "$ROOT/scripts/patch_arcana_nov9.py" "$APP" # owner's Nov 9 list: drawer, Commander, presets, fan/catalogue, backgrounds, log detail
python3 "$ROOT/scripts/patch_arcana_wording.py" "$APP" # owner, Nov 9: no "Argentum" on screen (fails the build if any is left)
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

echo "== Build"
echo "sdk.dir=${ANDROID_HOME:-/usr/local/lib/android/sdk}" > "$ARCANA/android/local.properties"
(cd "$ARCANA/android" && ./gradlew --no-daemon --max-workers=2 :custom-cards:exportCards :app:assembleDebug "-Parcana.debugLabel=Arcana Forge B142 Studio")
APK="$(ls "$APP"/build/outputs/apk/debug/*.apk | head -1)"
ls -la "$APK"
echo "APK=$APK" >> "${GITHUB_ENV:-/dev/null}"
