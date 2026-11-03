# Project Chimera 🧪

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

## Start here
1. Read [the experiment plan](docs/EVALUATION.md) and [licensing notes](docs/LICENSING_AND_IP.md).
2. To reproduce a **Forge source build on your own computer** (optional), install Git, JDK 17+, Maven, and Python 3.
3. On Windows PowerShell run `./scripts/probe_forge.ps1`; on Linux/macOS run `bash scripts/probe_forge.sh`.
4. For **one AI Commander game**, run `bash scripts/run_one_commander.sh` on your Linux machine, or use the **manual-only** workflow from GitHub's Actions tab after the repository is public and this change is merged. See [the execution notes](docs/RUN_ONE_GAME.md).
5. For the **next four-AI Commander experiment**, run `bash scripts/run_four_commander.sh` locally or manually dispatch the separate [four-seat workflow](.github/workflows/four-commander.yml) on the free public standard runner. Its pass criteria require one winner and three eliminated opponents; this is not a network or Android test.
6. Review ignored `reports/` output and record findings in [the acceptance matrix](scenarios/acceptance-matrix.csv). A Maven build by itself is **not** gameplay evidence.

## Forge baseline
Pinned upstream Forge commit: `7c34365c749ca46ab71336c5f267340d6088e600` (source inspected November 2, 2026, Mountain time).

- [Forge repository](https://github.com/Card-Forge/forge)
- [Argentum repository](https://github.com/wingedsheep/argentum-engine)
- [Arcana baseline](https://github.com/AARMOU/arcana_table)

## Status
**Phase 1 — headless gameplay baseline.** [Experiment 002 / GitHub run #41662709183](https://github.com/AARMOU/arcana-chimera/actions/runs/41662709183) on November 3, 2026 **passed**: pinned Forge compiled, and `Buckle Up` defeated `Counterpunch` in a two-AI Commander game (7,922 ms game time; seed 8073). **Four-AI game observed:** [Experiment 003 / GitHub run #41665373638](https://github.com/AARMOU/arcana-chimera/actions/runs/41665373638) played 27 turns and finished in 39,123 ms (seed 8074); Reap the Tide won against three opponents, including a commander-damage elimination. GitHub reports this run as failed **solely because our log checker omitted the `has lost due to` wording**. The corrected checker was verified in [the green four-AI game run #41677025921](https://github.com/AARMOU/arcana-chimera/actions/runs/41677025921) (27 turns, 28,447 ms, Reap the Tide winner). No four-player remote-network validation, Android integration, device performance, or evidence that Forge outperforms Argentum yet.

Unofficial experiment, not affiliated with or endorsed by Wizards of the Coast.
