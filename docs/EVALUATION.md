# Chimera experiment — evidence plan

## Question
Can Forge provide more reliable Magic/Commander rules and AI than Argentum **without losing Android performance, four-player functionality, maintainability, or acceptable licensing terms**?

## Known facts (source inspection)
| Aspect | Forge | Argentum in Arcana |
|---|---|---|
| Language | Java | Kotlin |
| License | GPL-3.0 | MIT |
| Card behavior | Scripted card definitions and game engine | JSONL card definitions plus custom overrides |
| AI | `forge-ai` uses Forge `Game`, `Player`, `SpellAbility` objects | Argentum `AIPlayer` uses `GameState`, `GameAction`, `ActionProcessor` |
| Android | Existing Forge Android client | Existing Arcana Android client |
| Multiplayer | Cross-platform host/client, networking described by Forge as work in progress | Arcana local and Cloudflare transport |

## Phase 0 — reproducible compilation (manual, no production changes)
- [ ] Fetch the pinned Forge commit in ignored local `third_party/`.
- [ ] Compile/test `forge-core`, `forge-game`, `forge-ai` using Maven.
- [ ] Capture Java/Maven versions, source SHA, exit code, duration, passing/failing test counts.
- [ ] Mark environmental failures separately from actual build defects.

## Phase 1 — real gameplay
- [ ] Start an unmodified Forge Commander match, human vs AI.
- [ ] Observe ten consecutive AI turns without a hang.
- [ ] Complete a game and record whether it ended correctly.
- [ ] Run one four-player Commander session; distinguish setup support from reliable remote play.
- [ ] Verify ten named, representative rules interactions (commander tax, replacement effects, attack assignment across opponents, etc.).
- [ ] Measure P50/P95 AI decision latency and peak memory on the target Android phone.
- [ ] Reproduce the same scenarios on Argentum as a fair comparison.

## Phase 2 — isolated integration feasibility
- [ ] Map Forge game-state events/actions to a **new** minimal adapter.
- [ ] Prototype one game turn with a throwaway client; do not fork Arcana itself.
- [ ] Measure APK size, startup latency, memory, network behavior, hidden-information handling.
- [ ] Verify GPL and third-party asset obligations before any release or repository import.

## Decision gates
Forge isn't a drop-in replacement for Argentum. Do not choose it based on its advertised card coverage alone. A candidate must show a completed game, stable AI turns, measurable device performance, a realistic multiplayer architecture, and an acceptable licensing approach. If evidence is absent, write **not tested**, not **passed**.

## Non-negotiable stop conditions
Any need to change production Arcana, use paid credits, deploy services, distribute Magic IP, bypass safeguards, or risk exposing private hands requires stopping for owner review first.

## Primary sources
- Forge [repository](https://github.com/Card-Forge/forge), [AI source](https://github.com/Card-Forge/forge/blob/master/forge-ai/src/main/java/forge/ai/AiController.java), [network play docs](https://github.com/Card-Forge/forge/blob/master/docs/Network-Play.md)
- Arcana [LocalArgentumHost.kt](https://github.com/AARMOU/arcana_table/blob/claude/b142-candidate-20261102/android/app/src/main/java/com/arcanatable/engine/LocalArgentumHost.kt)
- Argentum [source](https://github.com/wingedsheep/argentum-engine)
