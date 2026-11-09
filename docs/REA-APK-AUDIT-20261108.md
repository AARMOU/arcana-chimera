# Chimera APK binary inspection — 2026-11-08

**Status: compiled binary inventory verified; REA/JADX decompilation and runtime reproduction not run.** Contains no raw APK, log, cards, decks or personal information.

## Exact evidence

| Property | Observation |
|---|---|
| Chimera source SHA | f6565263e54aa3a97fe1d110e67fe8e6f890c23e (Claude's draft PR #16) |
| APK CI | https://github.com/AARMOU/arcana-chimera/actions/runs/42537589224 — success |
| Companion Forge host proof | https://github.com/AARMOU/arcana-chimera/actions/runs/42537589223 — success |
| Artifact | ID 13640132401 (one-day retention) |
| ZIP SHA-256 | 196d81d8c842dcffafe1c4296192f5fea5bbee6e384333564298d436ca73f30d |
| Extracted APK SHA-256 | dca86c5d3bfc6629b4972516ea1ff67b042da2be8d55e7e8a980558354aee5e8 |
| Older comparison | SHA 4630915c2554efa8611f24304fbf4d2714c265be, run 42535304227, APK SHA-256 29f22a5eeba62aadb961428718208aa53e527ae7ed98fb2af9382d4c49dd4217 |
| Changes newer vs older | DEX and assets inventory changed. Manifest, native libraries, resources and other inventory unchanged |

## Reproducible, read-only commands

    python3 scripts/rea_forge_evidence.py inspect /private/path/app-debug.apk --json
    python3 scripts/rea_dex_inventory.py /private/path/app-debug.apk --json

The second command reads DEX string/type/class/method indexes, reporting only package counts and an allowlist of bridge method names. It does not execute, decompile, or upload the APK. Use a private, locally supplied APK; do not commit the package.

### Newer compiled APK facts

- 15 DEX files; **69,353 class definitions** (including synthetic/compiler-generated).
- Forge class prefix: **4,026**; Chimera bridge prefix: **33**.
- Arcana class prefix: **4,593**.
- Legacy Argentum-related com.wingedsheep class prefix: **18,190**, comprising engine/SDK/other packages. Presence does not prove runtime execution.
- Exact class definitions found: ArcanaForgeHost, LocalArgentumHost, ForgeGameService, forge.game.Game, forge.ai.AiController.
- ArcanaForgeHost compiled methods: prepare, handle, handleUnsafe, shutdown, decisionForInput, emitDecision, defaultAnswer, autoSelectMinimum, skipPhase.
- LocalArgentumHost has receive-Eg5GKEU and shutdown; ForgeGameService has onStartCommand and onTaskRemoved.
- The package contains assets/forge-res.zip and legacy Argentum assets. Asset presence cannot establish which resources are consumed at runtime.

All findings refer to the SHA above. They may not describe a later build. No REA executable or JADX provider was available here: Node v22.16.0 is below the current REA minimum and external npm resolution failed.

## Evidence-backed investigation targets (not newly confirmed gameplay defects)

**F-01 — Legacy engine classes remain (P2 packaging cleanup candidate).** The compiled APK retains 18,190 com.wingedsheep definitions despite Arcana's local host source routing gameplay to Forge. Some SDK/DTO types are still needed by the UI, so do not strip dependencies blindly. Audit Gradle references, separate engine-only and shared types, then prove a Chimera-only size/startup improvement.

**F-02 — Human prompt defaults are compiled (P0 behavior risk).** At this exact source SHA, the GUI proxy has a fallback to defaultAnswer, and unbridged Forge input may call autoSelectMinimum followed by OK. Both methods are compiled in the APK. Notices and defaulted-prompt recording exist. Need a deterministic prompt that reaches each path, with evidence of intended vs actual outcome. This is the Chimera adapter, not proof of a Forge core rules bug.

**F-03 — Phase priority skip is compiled (P0 response-window risk).** skipPhase remains in the APK. The source uses different stop logic for human and AI turns. Need a stack-response / opponent instant / upkeep trigger test and on-device validation. Skipping a pause is not itself proof of suppressing legality.

**F-04 — Lifecycle and stale callback exposure (P1 follow-up).** Bridge shutdown and foreground-service lifecycle are compiled. Reproduce end game → launch new game → background → foreground with bounded anonymous thread/session traces. Avoid claiming a new freeze without reproduction.

## Validation boundaries

- Six existing APK evidence synthetic tests and six new DEX inventory synthetic tests passed in the isolated local runtime.
- Both real compiled APKs were inspected privately and their differing DEX/asset inventories compared. No private artifact uploaded to Chimera.
- The recent Forge host proof passed in CI. It is not a device test, full-card certification, or proof of every response window.
- REA/JADX, Android device tracing, and deterministic reproduction of F-02–F-04 are pending. The evidence here is static.

## Next exact-head experiment

1. Install official REA and JADX locally outside the repository. Validate with rea doctor --provider jadx --json.
2. Analyze the APK matching the SHA above; trace compiled defaultAnswer, autoSelectMinimum, skipPhase and their callers.
3. Correlate a single failing case with a private Android thread dump or sanitized Forge host replay.
4. Add a failing regression before any code change and verify against the pinned Forge/Arcana/Chimera commits.
5. Never write to Arcana production, merge, release or deploy without fresh owner authorization. Do not run paid agents or publish private logs/APKs.
