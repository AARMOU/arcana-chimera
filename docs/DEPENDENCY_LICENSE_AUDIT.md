# Chimera dependency license audit — pending human review

This lane records evidence only, never declares GPL or Play release compliance.

After the exact Forge/Arcana Android donor staging build completes, collect the
Android release runtime dependency graph and calculate JAR evidence:

    mkdir -p reports/license-audit
    (cd third_party/arcana/android && ./gradlew --no-daemon :app:dependencies --configuration releaseRuntimeClasspath) > reports/license-audit/runtime.txt
    python3 scripts/license_inventory.py --forge-libs third_party/forge-libs --app-libs third_party/arcana/android/app/libs/forge --gradle-report reports/license-audit/runtime.txt --out reports/license-audit/evidence.json

The script lists source artifact coordinates, actual staged JAR SHA-256 checksums,
embedded license and notice filenames, and Maven properties where available.
It does not determine license rights from filenames. Reports remain local.

Human auditor must inspect original and transitive Gradle/Maven library licenses,
embedded notice *contents*, Forge bundled resources and card data/art separately,
the exact AAB, GPLv3 compatibility, recipient-visible attribution, and
corresponding source distribution. No keystore, secrets, APK or AAB publication.

RELEASE HOLD: An evidence inventory alone is not GPL compliance.