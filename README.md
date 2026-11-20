# Project Chimera 🧪

> **Workers: start with [docs/TESTING.md](docs/TESTING.md).** It lists every test of the Arcana ↔ Forge bridge (including the random-play soak test), how to run and replay them, and the rules for changes. The current work is the Forge brain transplant, draft PR #16.

**Independent Forge-versus-Argentum research experiment.** This is an evaluation harness, **not** an Android app or a working Forge port.

## Objective
Find out whether Forge's existing Magic rules engine and AI can outperform Argentum for an Arcana-style, phone-first Commander game, using measured evidence rather than assumptions.

## Boundaries
- **Never change `AARMOU/arcana_table`**, its builds, Cloudflare services, automation, or the existing engine-repair drill.
- No new paid workers, API spend, deployments, APK releases, or automatic merges.
- Do not commit Wizards artwork, copied card databases, or unreviewed third-party code.
- Forge is **GPL-3.0**, Argentum is **MIT**; this starter contains **no Forge source code**.
- Private hosting does not erase third-party copyright or licensing requirements.
- **Public-only manual test authorized November 3:** standard GitHub-hosted Ubuntu runner only, after the owner changes repo visibility to public. No automatic runs, premium runners, public game binaries, paid workers, or artifact uploads. The workflow skips execution if repository visibility isn't public. See [decisions](docs/DECISIONS.md).

## FrankenArcana — current one-shot objective

**Owner's next goal:** one disposable Android build using **Arcana's actual current Compose table UI (or as close as possible)** with **Forge as the live rules engine and AI**. A renamed Forge launcher is **not** sufficient. Clone Arcana only into ignored temporary space via `bash scripts/stage_arcana_ui.sh`; every change stays in Chimera. No changes or contributions to Arcana–Argentum, no more benchmark campaigns. See [the one-turn integration contract](docs/FRANKENARCANA.md).

## Current build candidate — not yet verified on Android

The Chimera-owned `prototype/android/ArcanaTableOverlay.java` now draws a playable **Arcana-colored table from Forge's live Android `GameView`**, without scripted/fake cards. It includes multiple seats, life, turn/phase, opponent and own battlefields, command zones, a tappable hand, real Forge OK/pass choices, and a switch back to native Forge controls for difficult prompts. The prototype still uses Forge's own setup menu and deliberately **approximates** Arcana's Compose layout rather than compiling a literal copy of it.

The build script `scripts/build_frankenarcana.sh` and manual-only `.github/workflows/frankenarcana-android.yml` prepare an **independent debug APK** from pinned Forge source with package `org.arcanachimera.frankenarcana`. **Compiled and signed successfully in [run #41690582004](https://github.com/AARMOU/arcana-chimera/actions/runs/41690582004)** (the run still showed red because an APK filename check failed after packaging, repaired in PR #10). That APK was discarded; no install or game turn has been verified. Binaries are neither committed nor uploaded. Arcana–Argentum remains untouched. See [device trial status](docs/FRANKENARCANA_DEVICE_TRIAL.md).

## Encrypted private APK handoff (one-time)

Once the owner manually starts [FrankenArcana encrypted APK handoff](.github/workflows/frankenarcana-encrypted-handoff.yml), CI will build the signed debug APK using a standard free runner, **encrypt it on the runner**, then upload only ciphertext and a one-time RSA-wrapped password as a one-day GitHub Actions artifact. The **unencrypted APK is never uploaded** to GitHub or a public release. The corresponding private key remains outside the repository. The encrypted artifact can then be retrieved and decrypted into a private conversation download. This is a deliberate exception to the previous no-artifact rule for **encrypted ciphertext only**, not for APK binaries. No automatic runs, paid resources or Arcana writes. **Not yet run or delivered.**

## Start here
1. Read [the experiment plan](docs/EVALUATION.md) and [licensing notes](docs/LICENSING_AND_IP.md).
2. To reproduce a **Forge source build on your own computer** (optional), install Git, JDK 17+, Maven, and Python 3.
3. On Windows PowerShell run `./scripts/probe_forge.ps1`; on Linux/macOS run `bash scripts/probe_forge.sh`.
4. For **one AI Commander game**, run `bash scripts/run_one_commander.sh` on your Linux machine, or use the **manual-only** workflow from GitHub's Actions tab after the repository is public and this change is merged. See [the execution notes](docs/RUN_ONE_GAME.md).
5. For the **next four-AI Commander experiment**, run `bash scripts/run_four_commander.sh` locally or manually dispatch the separate [four-seat workflow](.github/workflows/four-commander.yml) on the free public standard runner. Its pass criteria require one winner and three eliminated opponents; this is not a network or Android test.
6. Review ignored `reports/` output and record findings in [the acceptance matrix](scenarios/acceptance-matrix.csv). A Maven build by itself is **not** gameplay evidence.

## Forge baseline
Pinned upstream Forge commit: `583714b8bfbd9cb04f8bc39ad95861190caf7691` (source inspected November 2, 2026, Mountain time).

- [Forge repository](https://github.com/Card-Forge/forge)
- [Argentum repository](https://github.com/wingedsheep/argentum-engine)
- [Arcana baseline](https://github.com/AARMOU/arcana_table)

## Status
**Phase 1 — headless gameplay baseline.** [Experiment 002 / GitHub run #41662709183](https://github.com/AARMOU/arcana-chimera/actions/runs/41662709183) on November 3, 2026 **passed**: pinned Forge compiled, and `Buckle Up` defeated `Counterpunch` in a two-AI Commander game (7,922 ms game time; seed 8073). **Four-AI game observed:** [Experiment 003 / GitHub run #41665373638](https://github.com/AARMOU/arcana-chimera/actions/runs/41665373638) played 27 turns and finished in 39,123 ms (seed 8074); Reap the Tide won against three opponents, including a commander-damage elimination. GitHub reports this run as failed **solely because our log checker omitted the `has lost due to` wording**. The corrected checker was verified in [the green four-AI game run #41677025921](https://github.com/AARMOU/arcana-chimera/actions/runs/41677025921) (27 turns, 28,447 ms, Reap the Tide winner). No four-player remote-network validation, Android integration, device performance, or evidence that Forge outperforms Argentum yet.

Unofficial experiment, not affiliated with or endorsed by Wizards of the Coast.


## Licensing and Google Play readiness

The owner chose GPL-3.0 for the Forge-powered Chimera work. See [the root
license](LICENSE), [its scope and third-party notices](LICENSE_SCOPE.md),
[GPLv3 source-distribution gates](docs/GPLV3_RELEASE_READINESS.md), and
[the existing Arcana Table Play Store release procedure](docs/PLAY_STORE_RELEASE.md).

**No signed Play AAB or GPL-compliant release has yet been certified.** The
original Play upload keystore must be recovered and matched to the Play Console
upload certificate; no signing secrets belong in this public repository. No
Play upload, deployment or owner-unapproved merge is authorized by this
documentation.
