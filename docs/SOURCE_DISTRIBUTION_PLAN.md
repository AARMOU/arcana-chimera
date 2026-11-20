# Chimera exact-source candidate: integrity audit and hosting contract

This lane validates integrity only, and cannot declare GPLv3 compliance.
Do not publish binaries or source archive, merge, or handle upload keys.

After the exact same-build source candidate is generated, run:

    python3 scripts/gpl_source_archive_audit.py --binary PATH/TO/app-release.aab --source reports/gpl-play-aab/chimera-source-CANDIDATE.tar.gz --manifest reports/gpl-play-aab/pair-manifest.json --out reports/gpl-play-aab/archive-integrity.json

Checks: binary SHA-256, source digest, manifest SHA-256, build provenance,
source tree paths, required build scripts, Forge license, missing components,
secret filenames, symlinks and archive traversal. Reports remain local.

Passing integrity is **not** a proof of rebuildability or Complete
Corresponding Source. Clean rebuild must run from archive in a separately
isolated environment with required dependencies and build tooling; check
the rebuilt package and behavior against the candidate. Inventory licenses
and any source for external Gradle/Maven dependencies separately.

Before *any* Play distribution, produce a final archive of corresponding
source and version-matched SHA-256, verify all required notices and modifications,
and provide an accessible durable URL for actual recipients of that version.
Record AAB digest, source digest, release version, tested reconstruct steps,
toolchain, human reviewer and link. No hosting has been authorized or performed.
Signing keys and other secrets never belong inside Complete Corresponding
Source automatically. GPLv3 installation-information legal review remains open.
