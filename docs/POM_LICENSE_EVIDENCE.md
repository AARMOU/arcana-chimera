# Chimera dependency-license metadata — human audit queue

This is a second-stage review aid for the 72 Forge-side JARs seen in the
successful Nov 10 Chimera CI log, plus Android/Gradle runtime dependencies.
**Maven POM license declarations are evidence, not legal authority or a grant
of permission.** A library may have parent-POM licenses, dual-license choices,
separate files, or nonmatching effective dependency versions. Any ambiguous,
missing, or declared license stays in human review.

Once the real Forge/Android donor build has created the prior
reports/license-audit/evidence.json, run **offline** from the build job:

    python3 scripts/pom_license_evidence.py \
      --inventory reports/license-audit/evidence.json \
      --maven-cache "$HOME/.m2/repository" \
      --gradle-cache "$HOME/.gradle/caches/modules-2/files-2.1" \
      --forge-source third_party/forge \
      --out reports/license-audit/pom-declarations.json

The scanner reads only .pom / pom.xml from these explicit Maven, Gradle and
Forge paths. It records each candidate coordinate, license declaration, source
type, and specific JAR/Gradle dependencies still needing reconciliation.
No credentials, source archive or APK is published. The report remains local,
and its release flag is unconditionally false.

Human review next:
1. Retrieve and confirm each **actual** dependency's license/NOTICE from
   upstream and effective POM, including the Maven/Gradle transitive graph,
   Forge source modules and nested/shaded dependencies.
2. Preserve notices and attribution in the final app/source; check GPLv3
   compatibility and any exception or copyleft/dual-license conditions.
3. Inspect all resources shipped inside forge-res.zip: card scripts, decks,
   metadata, images and third-party text/art are separate from engine GPL.
4. Cross-check AAB runtime contents, publication records and complete
   rebuildable corresponding source. Do not infer compliance from a passed test.
5. Obtain qualified human/legal review before binary distribution.

The current audit does NOT sign, merge, host, upload or authorize distribution.
