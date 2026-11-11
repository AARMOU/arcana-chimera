# Chimera GPL audit: deduplicated review queue (Nov 10, 2026)

**HOLD: NO FORGE APK/AAB DISTRIBUTION AUTHORIZED.** This document is an engineering audit, not legal advice or proof of compliance.

## Grounded evidence

Source: GitHub Actions run [42954804869](https://github.com/AARMOU/arcana-chimera/actions/runs/42954804869), artifact \`chimera-gpl-audit-evidence-NO-APK\` from draft PR #31, after the in-job APK/source-candidate integrity check.

- The report contains **144 staged JAR entries, but 72 distinct filenames** (Forge library location and Android copy). Deduplicate by filename and SHA-256 before considering license obligations.
- Of 72 distinct files: **17** have some direct POM license declarations; **51** have matching POMs but **no explicit child-level declaration** (often a sign to inspect parent POMs, not evidence of an unlicensed JAR); **4** are compiled \`forge-{ai,core,game,gui}\` snapshot modules without matching standalone cached POMs.
- **58/72** distinct files have no embedded \`LICENSE\`/\`NOTICE\` path found by the scanner. This does **not** necessarily mean notices are absent from their distribution elsewhere.
- **210 Gradle runtime coordinates** were inventoried: 140 with direct declarations, 69 no matching cached POM, 1 no explicit license. This is a separate dependency set; do not add these counts to the JAR count.
- The *latest* APK SHA-256 \`3729e744feff13d7f4158eb9e8c3db5a08bd4dd4b2a1f9a0f138f68c034f6056\` is recorded, but its binary was **not retained**. Source **candidate** SHA-256 is \`9c43c5703a03899bf778f33f713158f2e0902b76da061ecf7fb2b1a13b983b76\`. Hash consistency passed; a complete independent clean rebuild has not.
- The earlier Nov 10 test APK was inspected separately: **no \`assets/legal/*\` files** were present. It is NOT evidence of the current integrated APK's packaged notices.

## Highest leverage human/license review groups

| Exact dependency family from build | Count of distinct JARs | Review action |
| --- | ---: | --- |
| \`netty-*\` 4.1.115.Final | 35 | Confirm artifacts' Apache-2.0 license against exact upstream version, and preserve applicable NOTICE; resolve inherited POM declarations |
| \`jetty-*\` 9.4.57.v20241219 | 8 | Verify the exact Jetty version's Apache-2.0 / EPL licensing alternatives, chosen option, and NOTICE exceptions |
| \`commons-{lang3,math3,text}\` | 3 | Verify ASF upstream per-version notice files and Apache-2.0 license |
| \`forge-{ai,core,game,gui}\` SNAPSHOT | 4 | Check exact Forge pinned tree, GPLv3 and author notices, modifications and complete corresponding source |
| Other staged libraries | 22 | Investigate individually, especially \`javax.servlet-api\` CDDL/GPLv2+classpath, \`jgrapht-core\` EPL/LGPL options, \`mxparser\`, \`org.jupnp*\`, \`xstream\`, and native/shaded code |

Public upstream *starting references*, not approval of the exact packaged artifacts:
- Netty's official [license statement](https://netty.io/downloads.html) describes Apache-2.0 and a NOTICE obligation.
- The [Apache Foundation on GPLv3 compatibility](https://apache.org/licenses/GPL-compatibility.html) explains the general Apache 2.0 route.
- The [FSF compatibility analysis](https://www.gnu.org/licenses/license-compatibility.en.html) explains why choosing a dual-license option can matter.
- Check the exact upstream release/LICENSE files and binary notices rather than inferring licensing merely from these family-level statements.

## APK legal-notice failure identified

The integrated donor script appends Android packaging resource exclusions including \`META-INF/*\`. Therefore the scanner finding embedded JAR notice paths **does not establish** what survives in the compiled APK/AAB. The in-app \`GPL-RELEASE-NOTICES.md\` is a release-readiness document, **not** a substitute for a reviewed third-party notice inventory.

An offline inspector is staged at \`scripts/verify_apk_legal_assets.py\` with seven synthetic tests; it requires the GPL and source-access assets and a future **verified** \`assets/legal/THIRD-PARTY-NOTICES.txt\`. A missing, blank, invalid or duplicate asset fails closed, and even a complete file set is classified **NEEDS_EXTERNAL_HUMAN_REVIEW**, never approved.

Run on an **already available** APK without rebuilding:
\`\`\`sh
python3 -m unittest discover -s tests -p 'test_verify_apk_legal_assets.py' -v
python3 scripts/verify_apk_legal_assets.py --apk path/to/app-debug.apk --out legal-apk-report.json
\`\`\`

## Do not rerun Forge for these reviews

1. Resolve Maven parent/effective POM and upstream notice evidence from saved metadata. Group JAR duplicates; do not conflate unlisted child POM licenses with noncompliance.
2. Collect exact-version third-party licenses/NOTICE texts and human-reviewed inventory (and inventory Forge data, precons, images separately). Package and expose reviewed notices in ALL app skins.
3. Make the complete source candidate rebuildable in a clean environment and plan a durable, version-matched source URL; assess GPLv3 section 6 and Google Play terms with a qualified reviewer.
4. **Only then** run one full APK/AAB build, retain binary + same-build source in an authorized recipient-accessible channel, inspect the packaged APK/AAB and verify on Fold. Explicit owner approval is still required for merge, publication and Play upload.

The source-archive integrity check passing is not a clean rebuild; these gates remain open.
