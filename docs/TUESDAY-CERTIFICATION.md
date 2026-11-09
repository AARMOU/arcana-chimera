# Operation Frankenstein — Tuesday certification ledger

Owner-authorized Chimera-only solo sprint, November 8–10, 2026. **This is a test plan, not a release approval.**

## Frozen starting point (Nov 8, 17:00 Mountain)

- Forge transplant draft [#16](https://github.com/AARMOU/arcana-chimera/pull/16): `f6565263e54aa3a97fe1d110e67fe8e6f890c23e`.
- Goose bughunt draft [#17](https://github.com/AARMOU/arcana-chimera/pull/17) is stacked on an older transplant commit; Claude independently incorporated the anonymous cleanup discard fix, ProtocolGuard and FourSeatProof into #16. **Do not blindly merge or cherry-pick duplicated files.**
- Exact transplant-head Forge host proof: [42537589223](https://github.com/AARMOU/arcana-chimera/actions/runs/42537589223), completed success.
- Exact transplant-head debug APK build: [42537589224](https://github.com/AARMOU/arcana-chimera/actions/runs/42537589224), completed success. This is a CI build, **not a phone acceptance test**.
- Keep the source-risk baseline, redacted trace CLI, deterministic seed runner and other unique #17 changes isolated until reconciled/tested.

## What the current tests do and do not certify

- `OneTurnProof`: one turn and second-session smoke, **not** a complete phone game.
- `FourSeatProof`: four seats, cleanup discard, AI rotation back to human, **not** four-player game completion or networking.
- `AllCardsCheck`: 33,612 Forge objects constructed and 1,544 bundled names resolved in earlier runs; **not** proof of every mechanic.
- `SoakTest`: deterministic random legal-action bot, 2–4 seats; default push runs six games with 12-turn cap and AI OFF. **TURN LIMIT counts as pass**. Thus a green default run does not prove any game reached a valid winner or that LITE/MAX AI survives on the phone.
- `gameOver.winnerId = null` is **legitimate** when the human is eliminated while other seats continue, and potentially for a drawn game. Never label all null winners a bug. Instead distinguish `reason=ELIMINATED`, draw, and completed match; verify winner when Forge reports a single actual winner.

## Tuesday acceptance evidence required

| Gate | Evidence required | Status at baseline |
| --- | --- | --- |
| Headless protocol and fixed scenarios A–G | Exact SHA, workflow link, scenario results | CI success at f656526; inspect detailed logs for individual scenarios |
| Random 2–4-seat bridge play | 24 seeded runs across 2, 3, 4 seats; log seeds, outcomes, decisions, failures | NOT RUN at this target depth |
| AI LITE and MAX | At least one bounded run each; latency, stalls, heap recorded | NOT CERTIFIED |
| Real complete 2-player game | GameOver reason and winner semantics validated; not merely turn limit | NOT CERTIFIED |
| Four-seat interactive game | Mulligan, prompts, AI rotation, cleanup, elimination, state consistency | Smoke only |
| Android B142 Studio | Exact-head debug APK, owner phone launch, Studio controls and gameplay | APK CI success; phone validation outstanding |
| Prompt completeness | Zero silent defaults (except explicit play/draw exception), zero unbridged choices | Not fully certified |
| Failure diagnostics | Rejected-action loops, 45s stalls, missing gameOver and thread traces actionable | Instrumented; soak evidence needed |

## Execution order and safe regression protocol

1. **Reconcile before editing**: inspect fresh heads of #16 and #17 and CI; avoid duplicated changes. Work only on Goose-owned branch, with separate PR to #16 if code changes warrant it.
2. **Increase test rigor without changing gameplay first**: inspect `SOAK RESULT` and each `SOAK game seed=` from existing proof logs. Record the exact fraction of `GAME OVER` vs `TURN LIMIT`; do not treat all green games as completed.
3. **Target a reproducible defect**: a phone diagnostic or soak failure must identify its state, expected/actual behavior and seed or synthetic scenario. Write the failing test first; then fix adapter translation (never implement Magic rules in the adapter).
4. **Prioritize**: silent decisions/defaults, action rejection loops, game-over semantics, combat/stack/mana, AI handoff and performance. Preserve prompt identity and card visibility.
5. **Final evidence**: pin one candidate SHA, rerun Forge proof and APK workflow for that SHA, inspect job conclusions and log markers, then request phone validation. Never claim phone pass from CI.
6. **Tuesday decision**: PASS only if the above gates are met; otherwise CONDITIONAL with a bounded defect list. No automatic production promotion.

## Next independent task

Inspect exact-head proof log for default six-game soak outcomes and any unbridged/turn-limited games. Then design a deterministic test that fails when a completed **non-draw** two-player match lacks a winner, while permitting `ELIMINATED` with null winner in a continuing four-player game. Validate against Forge's real event semantics before changing the host.

## Boundaries

No writes to `AARMOU/arcana_table`, no production release, no paid compute, no automatic merges, no uploading private phone diagnostics or protected assets. Only owner-approved merges. Existing Chimera free standard runner and 1-day debug artifacts remain within the current authorization.
