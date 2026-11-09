# Chimera decisions and guardrails

## Owner decision: full Forge brain transplant, built in Chimera first (November 7, 2026, ~8:27 PM Mountain)

Recorded by Claude B from the owner's chat answers. The owner asked to **"take the argentum rule engine out of arcana and replace it with forge"** and answered Claude B's four questions:

1. **License:** GPL-3.0 accepted, **"as long as the apk is encrypted beforehand and I am able to decrypt it."** Delivery to the owner uses the encrypted-handoff route. GPL obligations attach only when a build is given to someone else, and then the corresponding source must go with it.
2. **Where:** **Option A.** Prove it in Chimera first, then move it into Arcana later as a separate, owner-approved step.
3. **Scope:** **"a full brain transplant"**: Forge replaces Argentum entirely as Arcana's rules engine and AI. No dual-engine switch is the goal (a temporary one may exist only as a development aid).
4. **Priority:** **"Don't concern yourself with main arcana for this project. This is your only job for right now."**

What this changes from the November 3 FrankenArcana scope:
- The target is **Arcana's real Compose app with its engine swapped**, not an Arcana-styled overlay on Forge's own Android app. The Chimera adapter must speak the same game-message contract Arcana's UI already uses (`LocalArgentumHost` → `/game`-style messages), so Arcana's table, hand, Studio and menus run unchanged on Forge.
- Arcana source is still **staged at build time into ignored space** from a pinned public commit and never mirrored into public Chimera history. Chimera commits only its own adapter code and minimal, clearly labelled patch files that swap the engine.
- **Owner (Nov 7, 2026): "Don't worry about encryption."** The Arcana + Forge debug APK is delivered as a plain 1-day GitHub Actions artifact. Chimera is public, so it is visible to signed-in GitHub users until it expires; if shared beyond the owner, GPL source obligations apply.
- **Owner chose (Nov 7, 2026):** build/test workflows run automatically on Claude B's **draft PR** pushes, on GitHub's free standard runners. Nothing is merged to `main` for this. Still no paid compute, no public APK or binary, no unencrypted artifact.

Unchanged: **no writes of any kind to `AARMOU/arcana_table`**, no spending, no automatic merges, no release.

## Owner override — November 3, 2026 (Mountain time)

The owner expressly authorized making this research-only repository **public** to attempt one **free** GitHub Actions Forge Commander game. This supersedes only the November 2 prohibition on publication and GitHub Actions in private repositories. Never start a paid runner. Use only standard `ubuntu-24.04` after the repository is public, with manual `workflow_dispatch`, no scheduling or automatic retries, and no publicly uploaded compiled engine or assets. Visibility must be changed by the owner in GitHub Settings; the integration cannot alter visibility.

The other boundaries remain in force: do not touch production Arcana, build APKs, deploy, distribute protected artwork or databases, spend AI/API credits, or merge unrelated work. Public visibility exposes repository history and old Actions logs.

## Owner follow-up — November 3, 2026

After successful two-AI Commander run #41662709183, owner said **"Go"** to the next Chimera experiments. Scope this step to preparing and attempting **one four-AI headless Commander match**, on a standard free public runner, manually dispatched and bounded. No automatic run, premium compute, API usage, artifact upload, or production integration is authorized. Four-AI simulation does not prove four-player remote/network/Android behavior.

## Owner isolation instruction — November 3, 2026

Owner confirmed a disposable Forge-powered Android proof-of-concept, completely separate from Arcana–Argentum. **Chimera may read Arcana's GitHub source but must contribute absolutely nothing to the Arcana repository or any Arcana service.** Any implementation/code/docs must stay inside Chimera. No more simulation-test marathon; focus on one human action and one AI response using Forge on a phone. See [FrankenArcana scope and hard write boundary](FRANKENARCANA.md). Previous prohibitions on public binary distribution, paid resources and Arcana writes remain in force.

## Owner's latest prototype scope — November 3, 2026

Owner instructed **“It's arcana with forge. I want the same ui as it currently is… as close as you can get for this trial run”**, followed by the instruction to **build it**. For the trial only, an original Arcana-style native Android overlay over Forge's real state/actions is permitted as a quick approximation, **not** a claim that the exact Compose UI has been ported. The minimal live-match bridge must be functional, and no Arcana write-back is allowed. Do not fabricate a game state to simulate success.

## Original November 2 direction (superseded only as noted above)

**Owner direction: November 2, 2026 (Mountain time).** Explore Forge in a dedicated private repository named `AARMOU/arcana-chimera`.

**Authorized:** Setup and documentation for an independent, read-only-first research experiment; preparation of local build probes and a test matrix.

**Not authorized:** Touch production Arcana; change the engine in B142; start paid workers; spend AI/API credits; create app releases; deploy services; make the repo public; or auto-merge anything.

**No cross-project control:** Chimera is not part of the Arcana production dispatch pipeline. The existing repair drill and current-orders board in Arcana remain authoritative for Arcana work.

**Next decision:** Whether to run manual Forge compilation and gameplay tests, then (only if they succeed) develop a throwaway one-turn Android proof of concept.

**Current result:** No Forge builds or gameplay tests executed by this repository's setup. All acceptance-matrix entries begin as `not_run`.

## 2026-11-08 — Arcana build 142 + Studio
Owner: build the Forge APK on the current Arcana build 142 including the latest Arcana Studio, with no changes to the Arcana repo.
Staged read-only at `63edcb77` (head of AARMOU/arcana_table#1013, `claude/arcana-studio-ux-20261106`, versionCode 142). Debug build: Studio on, label "Arcana Forge B142 Studio", package `org.arcanachimera.arcanaforge.b142studio` (installs beside earlier Forge builds).

## 2026-11-08 — No Argentum card check (priority 1: every card on Forge)
Owner: "I want the argentum card check gone. Verifying being able to utilize all cards in the forge engine is priority 1."
- `scripts/patch_arcana_for_forge.py` patches the staged Arcana at build time (nothing committed to arcana_table): offline decks keep every card (no Argentum filter, no basic-land fill for Argentum-missing cards), AI decks are not filtered by Argentum, and import no longer reports "not in Argentum". Forge reports any card it doesn't have when the game starts.
- `AllCardsCheck` (proof workflow) builds every unique card in Forge's database into a game object and looks up named cards.

## 2026-11-08 17:27 — Latest Arcana Studio
Owner: fetch the latest Arcana Studio updates and integrate them into Chimera. Arcana is now staged read-only at `3d9fce0d` (head of arcana_table#1013, still versionCode 142): 7 Studio commits since `63edcb77` (per-player IDs, Fill it up, presets, Light palette, game options in Studio, two-finger scroll, Show me, clear boards, Pass copies, Background setting). UI only; no engine-file changes, and the Forge build patches apply unchanged.
