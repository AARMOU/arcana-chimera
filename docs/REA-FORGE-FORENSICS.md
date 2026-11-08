# REA for Forge — Chimera-only APK forensic lane

**Status: prepared; not run against a real APK.** The objective is to connect compiled-APK evidence to Chimera's existing deterministic Forge bug hunter (see [BUG-HUNTING.md](BUG-HUNTING.md)). REA is **optional**, local-only, and read-only; it is not a replacement for source inspection or a Magic rules oracle.

## What to investigate

Forge is open source: begin with its real Java source, not decompilation. REA is useful for answering a distinct question: what actually shipped in the APK, where do the compiled Arcana Compose → Forge host references point, and does the build match the source branch under test?

Chimera candidates to investigate:
- Forge host: forge-host/src/main/java/org/arcanachimera/forgehost/ArcanaForgeHost.java — action/priority dispatch, prompt fallbacks, serialized game state.
- Arcana message bridge: arcana-overlay/app/src/main/java/com/arcanatable/engine/LocalArgentumHost.kt.
- Service/lifecycle boundary: arcana-overlay/app/src/main/java/com/arcanatable/engine/ForgeGameService.kt.
- Actual Forge classes observed in compiled DEX, particularly game action, input-prompt, AI and state transitions.

Known concerns documented in Chimera: silent default answers, priority skips, incomplete game-state serialization, stale-host lifecycle and on-device AI latency. These are **risks**, not all proven defects. Do not mark a problem as repaired until the failing exact-head test subsequently passes.

## Prerequisites and scope

- Use the official [REA repository](https://github.com/morluto/rea) and [Android guide](https://github.com/morluto/rea/blob/main/.agents/skills/reverse-engineer-anything/references/android-applications.md).
- REA currently requires a supported Node.js release: 22.23.4+, 24.22.1+, or 26.12.1+, plus npm. For Android JADX analysis supply a **full JDK 17+** and the headless **JADX MCP JAR 0.7.1**; set environment variable REA_JADX_MCP_JAR to its absolute path. ADB is optional.
- Run REA installation from a trusted directory outside the repository using: npx rea-agents setup. Review what the installer proposes to change; then restart the selected local agent. Nothing is installed or downloaded by this repository's CI.
- All personal APKs, REA raw findings, phone logs, deck content and game-action histories stay **private and local**. Never upload to public Chimera. Do not write to AARMOU/arcana_table, merge, release, deploy, run a paid agent or modify Forge rules in this lane.

## Read-only provenance commands

From a Chimera checkout, use Python 3:

    python3 scripts/rea_forge_evidence.py doctor --json
    rea doctor --provider jadx --json

The first command checks basic local executable presence; it doesn't prove a JDK version, a correct JADX provider or a successful APK inspection. The second is REA's actual provider readiness check.

Keep a user-provided APK in a private directory, then record its binary identity without extracting anything:

    python3 scripts/rea_forge_evidence.py inspect "/private/path/chimera.apk" --json

Compare two debug APKs when testing a specific integration change:

    python3 scripts/rea_forge_evidence.py compare "/private/path/before.apk" "/private/path/after.apk" --json

Exit codes: 0 success; 1 for doctor readiness failing; 2 for an invalid input. Output contains archive fingerprints and counts, not filenames, private paths, player data or raw log contents. The full APK SHA-256 identifies the binary. DEX/native/manifest/resources component inventories hash each extracted member; assets/other inventories use ZIP metadata, **not** cryptographic verification of each asset's bytes. These fingerprints do **not** prove a Forge class exists or that gameplay is correct.

## Manual REA investigation (one suspect at a time)

1. Call the advertised REA MCP tool **inspect_android_package** against the *absolute local APK path*. Record its manifest package ID, digest, coverage and diagnostics. A failed or partial session cannot prove a class is absent.
2. Search actual DEX types via **search_android_classes** for ArcanaForgeHost, LocalArgentumHost, ForgeGameService and relevant forge classes. Do not assume source filenames are still the compiled class identities.
3. Use **inspect_android_class** to obtain method members, **inspect_android_method** for precise overloads, then **trace_android_references** to follow action/decision callbacks. These are static relationships; reflection, dynamic loading and generated code may escape them.
4. Choose one deterministic symptom: frozen AI turn, human prompt silently default-answered, same rejected action recurring, priority window skipped, or broken callback after lifecycle restart.
5. Correlate source line, compiled method, anonymous state/version/phase trace and—only with device consent—private ADB logs or a Java thread dump. APK/JADX analysis does **not** execute the app or identify thread deadlocks by itself.
6. Build a failing reproduction, implement a minimal fix in a separate authorized Chimera development lane, rerun deterministic Forge host proof and verify the specific interaction on-device. Record the exact Forge, Arcana and Chimera SHAs plus APK SHA-256. Do not infer success from a newer state version alone.

## Privacy-safe private findings template

    Symptom, time and minimal reproducible steps:
    Forge source SHA / staged Arcana SHA / Chimera commit:
    APK SHA-256:
    REA class, method, evidence reference and incomplete coverage:
    Runtime trace/thread correlation:
    Expected state / observed state:
    Classification: source | bridge | packaging | lifecycle | UI | inconclusive
    Counterevidence and confidence:
    Failing regression test:
    Passing exact-head verification:
    Data redaction review:

## Current execution status

The evidence helper was checked with six synthetic Python tests, including path/name redaction and malicious ZIP edge cases. **No real APK was inspected with REA**, no app was installed on a phone, and no Forge issue was declared fixed. This lane intentionally adds no GitHub Actions workflow or autonomous spending.
