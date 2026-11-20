# Chimera GPL audit evidence — non-binary export

This draft retains a narrowly scoped, reviewable **metadata-only** audit artifact from a passing Chimera/Forge build, without weakening the **no-APK distribution hold**.

- GitHub Actions artifact name: `chimera-gpl-audit-evidence-NO-APK`; retention: seven days.
- Artifact contains **one JSON file only**: `audit-evidence.json`.
- Report includes APK hash (not APK bytes), candidate source archive hash (not source bytes), same-build source integrity flag, staged Forge/Android JAR names and hashes, embedded notice paths, Maven POM license declarations, and unresolved review counts.
- The exporter requires the actual same-job APK/source digest agreement, license-inventory schema, and explicit `distribution_approved=false` flags. It refuses symlinks, oversized input and secret-like text.
- The workflow **does not upload an APK, AAB, source archive, signing key, or sensitive Gradle/Maven cache**. It does not sign, merge, deploy, or publish a Play build.

Run the offline exporter tests independently:

```sh
python3 -m unittest discover -s tests -p 'test_export_gpl_audit_evidence.py' -v
```

A successful artifact is **review evidence, not legal sign-off**. A qualified reviewer must still independently validate each actual dependency's authoritative license and NOTICE, transitive/shaded/native contents, Forge resource/data rights, complete corresponding source and clean rebuild, durable version-specific source access, GPLv3 section 6 and Google Play considerations, app/device QA, and the owner's explicit release decision.

Original Claude B branch and Arcana main remain untouched. This work is intentionally stacked on Chimera PR #30's POM metadata scanner.
