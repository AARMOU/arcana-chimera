# Chimera → existing Arcana Table Play Store entry: signed AAB procedure

**Status: preparation only; NOT yet authorized for distribution or Play upload.**
The release must contain GPL-compliant corresponding source and notices, and must
pass owner review. This draft does not merge Claude B's work or touch Arcana main.

## Identity and Android policy

- Existing Play listing: application ID `com.arcanatable`, NOT
  `org.arcanachimera.arcanaforge` (the Chimera test build identity).
- Existing original upload-key alias: `arcana-upload`. The alias is **not the
  keystore** and is not proof of certificate identity.
- Build 138 Play release checklist records the original key. Build 142 donor has
  `targetSdk = 36` and `versionCode = 142`. As of August 31, 2026,
  Google Play requires API 36 for ordinary phone app updates.
- Supply an **unused** versionCode above the highest code on ALL Play tracks.
  The script requires at least 143 but does not query Play Console.
- The resulting file must be a *release-signed AAB*. The debug Chimera APK
  does not update the existing listing.

## Status of the signing key

**Original keystore NOT LOCATED.** Prior private release records demonstrate
that a key with alias `arcana-upload` was used; that record is not the key file.
A separate Library document records credentials for a **NEW** candidate upload
key with alias `arcanatable-upload`; do not substitute that key unless the
Google Play Console accepted a new upload certificate. No password, keystore,
base64 key or credentials belong in this repository, PR, logs, or chat.

Recover the *original* keystore from its trusted prior signing machine or
encrypted backup. On a phone, open Play Console → the existing Arcana Table app
→ **Test and release → App integrity** (the exact menu label can vary) →
**App signing** → **Upload key certificate**. Copy the **SHA-256 fingerprint**
of the UPLOAD certificate (not the separate Play app-signing certificate).
Compare the recovered key fingerprint before building.

The existing original keystore cannot be reconstructed from its alias,
password text, public certificate, old AAB or Play app-signing certificate.
If genuinely lost, request an upload-key reset through Play Console, then
record/verify the new accepted certificate. Do not reset without owner action.

## Local signing inputs (in a trusted environment only)

Keep the key file outside the repository. Export these environment variables
through a private local secrets mechanism, without recording them in shell
history or Git:

| Environment variable | Meaning |
| --- | --- |
| `CHIMERA_UPLOAD_KEYSTORE` | Absolute path to the **original** keystore |
| `CHIMERA_UPLOAD_STORE_PASSWORD` | Keystore password (secret) |
| `CHIMERA_UPLOAD_KEY_PASSWORD` | Key password (secret) |
| `CHIMERA_UPLOAD_ALIAS` | Must be `arcana-upload` |
| `CHIMERA_EXPECTED_UPLOAD_CERT_SHA256` | 64-hex fingerprint from Play Console UPLOAD certificate |
| `CHIMERA_PLAY_VERSION_CODE` | An unused higher integer (at least 143) |
| `CHIMERA_PLAY_VERSION_NAME` | Optional, safe display string |

When the environment has the validated original key, execute:

```sh
bash scripts/build_chimera_play_aab.sh
```

The shell script checks the keystore's certificate **before** building,
stages pinned Arcana and Forge sources into ignored `third_party/`, appends
a release-only Gradle signing block to that staged Android project, builds
`:app:bundleRelease`, and checks the resulting AAB's signature and certificate.
The source repo's debug build package remains distinct. Nothing is uploaded,
released or committed. The original secret file is read from the local path;
the build never copies the keystore into the repository.

After the signed AAB is checked, the script also stages a **local AAB-matched\nsource candidate** under ignored `reports/gpl-play-aab/`, with checksums.\nThis is NOT a completed or audited GPL source distribution. No file uploads.\n\nThe prepare script also refuses to run if the donor's target SDK is below 36,
the app-id override is not recognized, or the Play version code is below 143.
Use `bundletool dump manifest` on the final AAB to separately confirm
`package=com.arcanatable`, the exact versionCode, and target SDK; verify
the Play Console accepts that code and certificate.

## GPLv3 gate — must pass before Play closed testing or public release

The #21 and #22 GPL drafts contain the verbatim Forge GPLv3 license and a
same-job *APK* source-archive candidate. These are foundations **only**.
The Play build now stages an AAB-specific same-job source **candidate** as well.\nNeither candidate establishes that a signed Play AAB is GPL compliant. No APK or AAB should reach third parties until all of these pass:

1. Inventory all included Forge, Arcana, Chimera and other dependencies
   (including transitive Gradle/Maven libraries) and audit their license
   compatibility; retain required notices and modification attribution.
2. Create Complete Corresponding Source for the **exact AAB** with the
   generated/modified donor Android project, exact Forge/Arcana revisions,
   resources and controls/scripts needed to rebuild. Review and test a
   clean rebuild from that archive. The AAB-specific source candidate\n   manifest is **not** a compliance certificate.
3. Make GPLv3 and other required notices visible in-app, with a link or
   instructions to obtain the source for the installed version.
4. Preserve a durable, accessible, version-specific source archive and
   hash for recipients, not just a 1-day CI artifact; ensure no credentials
   or restricted files leak into it.
5. Review Android/Google Play distribution restrictions and GPLv3
   Installation Information obligations, and obtain legal/owner sign-off.
6. Run full device tests, version/package/certificate checks, and an
   upload validation in the intended Play track. Upload and rollout are
   separately owner-controlled.

The build script therefore **never automatically uploads** the AAB. Even
a passing signed AAB remains in HOLD status until the GPL review is complete.

Related PRs: [Forge transplant #16](https://github.com/AARMOU/arcana-chimera/pull/16),
[GPL preflight #21](https://github.com/AARMOU/arcana-chimera/pull/21), [source candidate #22](https://github.com/AARMOU/arcana-chimera/pull/22).
