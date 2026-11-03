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

## Start here
1. Read [the experiment plan](docs/EVALUATION.md) and [licensing notes](docs/LICENSING_AND_IP.md).
2. To reproduce a **Forge source build on your own computer** (optional), install Git, JDK 17+, Maven, and Python 3.
3. On Windows PowerShell run `./scripts/probe_forge.ps1`; on Linux/macOS run `bash scripts/probe_forge.sh`.
4. For **one AI Commander game**, run `bash scripts/run_one_commander.sh` on your Linux machine, or use the **manual-only** workflow from GitHub's Actions tab after the repository is public and this change is merged. See [the execution notes](docs/RUN_ONE_GAME.md).
5. Review the ignored `reports/` output. A successful Maven build is **not** proof that Commander gameplay, multiplayer, Android performance, or Forge-to-Arcana integration works.
6. Record gameplay findings in [the acceptance matrix](scenarios/acceptance-matrix.csv). Mark items passed **only with evidence**.

## Forge baseline
Pinned upstream Forge commit: `7c34365c749ca46ab71336c5f267340d6088e600` (source inspected November 2, 2026, Mountain time).

- [Forge repository](https://github.com/Card-Forge/forge)
- [Argentum repository](https://github.com/wingedsheep/argentum-engine)
- [Arcana baseline](https://github.com/AARMOU/arcana_table)

## Status
**Phase 0 — setup.** This project does not yet have a compiled Forge engine, **completed AI Commander game**, Android integration, or validated performance comparison. A manual match runner has been prepared, **not executed**. Do not claim an engine winner on repository inspection alone.

Unofficial experiment, not affiliated with or endorsed by Wizards of the Coast.
