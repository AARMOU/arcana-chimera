# Chimera / Arcana × Forge — GPLv3 release-readiness playbook

**Status: preparation only; NOT a declaration of GPL compliance or approval to distribute.**
**Scope:** GPLv3 and third-party software licenses only. No conclusions about third-party game content or trademarks.
**Owner approval remains required** for merging, deployments, and releases. This file is intentionally separate from Claude B's draft brain-transplant branch and does not change Arcana or any workflows.

## Current architecture (audit of draft PR #16 on 2026-11-09)

- Chimera's current public repository provides adapter/overlay code, but *not* the full combined APK source tree. The draft build script `scripts/build_arcana_forge_apk.sh` stages Arcana and Forge at build time under ignored `third_party/`.
- At audit time the script's defaults were Arcana `a5c428ad3da42b7325adf6630238ec992fee3113` and Forge `7c34365c749ca46ab71336c5f267340d6088e600`. These are **defaults, not timeless facts**; the actual artifact must record resolved commits and hashes.
- It copies Forge-derived JARs, a Forge resource ZIP, and Chimera adapter/patch code into a staged, modified Arcana Android project, then compiles an APK.
- The draft workflow `.github/workflows/arcana-forge-apk.yml` uploads the debug APK to a one-day GitHub Actions artifact in a *public* repository. Do not assume a one-day expiry or "debug" label removes distribution obligations.
- Existing `docs/LICENSING_AND_IP.md` is preliminary. The complete GPLv3 license text, preserved verbatim from pinned upstream Forge, is included at `third_party_licenses/Forge-GPL-3.0.txt`. This file is a **third-party license copy**, not a unilateral license grant over all independently authored Chimera files.

## Release blockers — resolve BEFORE allowing a Forge APK to reach other users

1. **Identify the exact covered work.** Review Forge and combined Arcana/Forge integration under GPLv3, note authors and preserved copyright notices, and audit all included Maven/Gradle dependencies and assets for other licenses. Do not assume a JAR's license based on Forge's top-level license.
2. **Package the Complete Corresponding Source for the *same APK*.** Include the exact Forge source revision, Arcana source (including all modifications actually used in the build), Chimera overlay/patch scripts and code, build-control scripts, and any other source/build inputs the GPL requires. An upstream source link **alone**, or an unmodified Arcana checkout plus a built APK, is insufficient unless the complete exact modifications and reconstruction instructions are supplied.
3. **Record build evidence.** For each build record APK SHA-256, size, source commit IDs and verified checkouts, Chimera commit, build toolchain versions, and dependency resolutions. `python3 scripts/gpl_release_preflight.py --apk PATH_TO_APK` creates a read-only local audit manifest but **does not create the complete source bundle** and **does not certify compliance**.
4. **Make the license and notices available with the distribution.** Retain author/copyright notices, label modifications, include the unaltered GPLv3 text, and provide a clear license/source-access path in the distribution and in an in-app About/Licenses area. Ensure any other bundled licenses/notices are satisfied.
5. **Deliver source access alongside binaries.** For GitHub Actions artifacts or later Google Play closed testing, recipients must have a clear route to obtain the corresponding source for their **specific installed build** on GPL-consistent terms. A short-lived public APK without equivalent source access is a blocker. The source location and retrieval instructions need to remain available for the applicable distribution period. Consider a versioned permanent source archive + SHA-256 digest on a release or equivalent channel.
6. **Check platform distribution terms.** Verify that Google Play terms, any device restrictions, app signing, and update/installation constraints do not impose conflicting additional restrictions. GPLv3 section 6 Installation Information is context-dependent; **do not automatically assume either that releasing signing secrets is required or that Android signing never matters**. Obtain qualified legal review for an actual release policy.
7. **Human release gate.** Before any Forge-powered APK distribution, a reviewer verifies (a) the source is complete and rebuildable, (b) the manifest matches the APK, (c) the notices are visible, (d) the source URL is live, and (e) dependencies/licenses and Play terms were checked. Record the reviewer, date, APK SHA, source package SHA, source URL, and intended recipients.

## How to prepare the source bundle (manual release engineering)

- Produce the bundle in the *same CI build* from the same checked-out source and patched project used for the APK; do not attempt to reconstruct it from whichever versions are current days later.
- Capture Forge's exact source, not just the redistributed JARs. Capture the **post-patch** Arcana sources (or a complete exact base revision plus every patch, generated source input, and reproducible application order). Include Chimera scripts, adapters, and all modifications, along with build instructions and requisite dependency/source inventories.
- Exclude credentials, signing secrets, user profiles, `.git/` object stores, caches, and unrelated private data. Excluding secrets does not remove a separate obligation to assess Installation Information.
- Record immutable digest(s) for APK and source archive. Retain the paired artifacts and provenance in a durable location; do not rely solely on the one-day workflow artifact.
- Source packaging should **fail closed** when checked-out commit IDs don't match the pinned revisions. Do not publish unreviewed source archives that might contain private or unrelated data.

## Required release record template

```text
Release / APK identifier:
APK SHA-256:
Chimera commit:
Arcana base commit:
Forge base commit:
Patch/overlay commit:
Source archive URL and SHA-256:
Third-party dependency license review:
License / About-screen notice:
Installation-information review:
Reviewer and date:
Recipients / distribution channel:
```

## Source and license references

- [Forge source and upstream LICENSE](https://github.com/Card-Forge/forge/blob/master/LICENSE)
- [GNU GPLv3 text, especially sections 4–6](https://www.gnu.org/licenses/gpl-3.0.html)
- [GNU GPL FAQ on matching source and binary access](https://www.gnu.org/licenses/gpl-faq.html.en)
- [Chimera draft integration PR #16](https://github.com/AARMOU/arcana-chimera/pull/16)
- [Existing Chimera preliminary licensing notes](LICENSING_AND_IP.md)

**Important:** The research repository being public, and including a verbatim copy of GPLv3, is not in itself proof that the shipped Arcana/Forge APK satisfies GPLv3. GPL compliance depends on the *actual* combined work and what is provided to its recipients. Seek software-licensing counsel before Play Store release.

## Build-integrated source candidate — separate Nov 10 draft

The separate review branch goose/gpl-source-pair-gate-20261110 is based on
Claude B's draft PR #16 without modifying his branch. The proposed workflow
runs offline packaging tests and, after an APK build, captures the exact
patched Arcana checkout, pinned Forge source, Chimera scripts, and generated
Android overlay/asset inputs from that same job.

Outputs kept locally under ignored reports/gpl-release/:
- chimera-source-CANDIDATE.tar.gz
- pair-manifest.json (APK/source SHA-256 and source commits)

FAIL CLOSED: Missing APK, incorrect source revision, suspect secrets,
symlinks and excessive source inputs stop packaging. No binaries or source
are uploaded in this review workflow. Claude's original build stays untouched.

IMPORTANT: This candidate does not prove complete corresponding source,
a reproducible build, external dependency source/license clearance, required
in-app notices, durable source hosting, or GPLv3/Play compatibility. These
remain explicit human/legal review release blockers. No merge or distribution
without owner approval.
