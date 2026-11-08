# Chimera Bug-Hunting Foundation (Goose, 2026-11-08)

**Scope:** Chimera only. No changes or CI in AARMOU/arcana_table. The transplant remains Claude's unmerged draft. This infrastructure does not merge code, adopt a new Forge pin, ship an APK, dispatch paid agents, or change rules.

## What is implemented

1. **Source-risk regression tripwire** — Run:
       python3 scripts/chimera_bughunt.py audit
   Compares adapter source against explicit known-risk patterns in bughunt/source-risk-baseline.json. New matches fail; reductions are reported. Known matches remain real bugs. This is a narrow syntactic tripwire, **not** a semantic static analyzer.
2. **Live protocol contract guard** — ProtocolGuard is attached to the existing OneTurnProof and PromptScenarios message streams. Checks state versions, 2–4 unique seats, active/priority seat IDs, legal-action payloads, and interactive pending-decision envelopes. It observes actual Forge adapter messages and never answers Forge or changes game rules. ProtocolGuardSelfTest checks failure behavior.
3. **Game trace triage** — chimera_bughunt.py trace reads synthetic/sanitized JSONL, and diagnostic reads locally saved Arcana diagnostic text. It detects non-monotonic versions, actions with no observable change after a later timestamp, and explicit errors. A newer state version by itself is not progress.
4. **Replay samples** — A progressing combat fixture passes; a stalled zero-blocker handoff is deliberately flagged. These are **invented samples**, not private phone logs.
5. **Lightweight CI** — A read-only Chimera workflow runs Python tests and fixtures on the PR. Existing Forge host proof separately runs the actual adapter tests with the protocol guard. No seed farm, schedule, or autonomous repair loop.

## Run it locally

    python3 -m unittest discover -s tests -p 'test_*.py' -v
    python3 scripts/chimera_bughunt.py audit
    python3 scripts/chimera_bughunt.py trace bughunt/fixtures/progress-combat.jsonl --stall-ms 3000
    python3 scripts/chimera_bughunt.py trace bughunt/fixtures/stalled-blockers.jsonl --stall-ms 3000
    python3 scripts/chimera_bughunt.py diagnostic /path/to/private-diagnostic.txt --stall-ms 10000

The intentionally stalled fixture returns exit 1; good traces return 0; bad input returns 2. Add --json for a machine-readable summary. No output contains card names, deck lists, network addresses, room codes, or raw logs.

The larger headless live-Forge proof remains:

    bash scripts/forge_host_proof.sh

It downloads/builds the pinned Forge source and runs OneTurnProof, PromptScenarios, FourSeatProof and card checks. FourSeatProof is a **bounded four-seat Commander startup/one-round handoff** using 99 basic Forests per player, one Commander each, and the real Arcana message adapter. It verifies four seats, a human keep-hand, pass/combat choices, a forced cleanup discard resolved through a real SelectCardsDecision, and return of control after the AI round. It does **not** certify complete four-player Commander gameplay, card diversity, networking, or Android performance.

## Opt-in live tracing from the real Forge adapter

The real headless drivers (OneTurnProof and PromptScenarios) now attach a
BughuntTrace observer in addition to ProtocolGuard. Recorder data is bounded
to 2,048 events per session and never contains card names, cards/hand lists,
deck names, prompts, player names/IDs, action payloads or error text. It records
only event kind, relative milliseconds, anonymous session, game step, version,
abstract seat roles, legal-action count, pending decision type, and a coarse
**public-state revision counter**. A tick is emitted after an unanswered
headless poll. The recorder is not present in the Android runtime adapter.

Default: **no event stream in stdout**. For an explicit local diagnostic:

    CHIMERA_TRACE=1 bash scripts/forge_host_proof.sh | tee /tmp/chimera-forge-proof.log
    python3 scripts/chimera_bughunt.py live-log /tmp/chimera-forge-proof.log --session one-turn --stall-ms 90000

Other test sessions appear as scenario-1 and scenario-2. If the log has
multiple sessions, --session is required to prevent false nonmonotonic
timestamps across independent games. Headless one-turn proof may answer a
legal action many times; flagged stalls remain *suspects* until a specific
deterministic failure is reproduced. No automatic upload; /tmp is an example
local private path. **Never attach raw host logs or personal phone logs to
a public GitHub issue**; publish only a reviewed, minimal sanitized summary.

TraceRecorderSelfTest is named BughuntTraceSelfTest in the Java proof and
verifies that deliberately inserted "SECRET" player, card and prompt data
cannot appear in output events. Actual game messages are independently
checked by ProtocolGuard. This is not a substitute for a full rules oracle.

## Reproducible full-Forge Commander simulation (manual only)

The existing four-AI Commander workflow now accepts one explicit decimal seed
(default 8074) in the GitHub Actions Run workflow interface. It runs **one**
bounded game on a free standard runner, not a seed farm. Its rules/winner
checker requires four seats, exactly one winner, and three losses.

Local replay:

    CHIMERA_SEED=42 bash scripts/run_four_commander.sh

No-network seed validation:

    CHIMERA_SEED=42 bash scripts/run_four_commander.sh --check-seed

Allowed range: 0..2147483647, decimal without leading zeroes. Bad values fail
before cloning/building anything. Preserve the seed, source SHA, outcome,
failure signature, and run URL in any ensuing bug report. This is **Forge's
standalone AI simulator**, not the full Arcana UI/host adapter; a green result
cannot certify Chimera's interaction or Android behavior.

## Normalized trace event contract

One JSON object per line, with monotonic integer at_ms:

    {"at_ms":0,"kind":"state","version":46,"turn":10,"phase":"DECLARE_BLOCKERS","active":"offline-ai","priority":"offline-you","legal":1,"board_revision":18}
    {"at_ms":100,"kind":"action","name":"DeclareBlockers"}
    {"at_ms":130,"kind":"state","version":47,"turn":10,"phase":"DECLARE_BLOCKERS","active":"offline-ai","priority":"offline-you","legal":1,"board_revision":18}
    {"at_ms":10130,"kind":"tick"}

Kinds: state, action, error, tick. Optional state fields: turn, phase, active, priority, pending, legal, board_revision. A board revision (when later emitted) should fingerprint **only public observable state**. No card identities are required. A tick means we were still observing then, not a gameplay action. No later observation means **no proven stall**. Same-phase, same-seat actions can also be legitimate, so findings are reproduction candidates, not proof that Forge AI itself is stuck.

Diagnostic mode recognizes Arcana text logs' state versions and timestamps but **cannot** recover unlogged pending decisions, priority ownership, hidden state or thread stacks. It does not establish whether a stall is in Forge, the adapter, or the UI. Verify with a deterministic replay.

## Execution and privacy rules

- Chimera is a public repository. Never commit/upload private phone diagnostics, screenshots, deck contents, card identities, credentials or raw action histories.
- Analyze personal diagnostics only in an explicitly authorized local/private environment; the parser itself makes no network calls or writes.
- CI uses free standard GitHub runners, bounded jobs, read-only permissions, no schedules, no paid worker dispatch and no automatic merging.
- A successful proof is not an approval to merge, release, install engine binaries into Arcana, or deploy services.

## Source-risk ledger (snapshot: transplant 0c7f1f0)

| Priority | Observed exposure | Evidence required to close |
|---|---|---|
| P0 | Unhandled Forge GUI and input prompts choose defaults | Prompt inventory, explicit user choice or recoverable error, deterministic cases |
| P0 | Mana pool, commander damage, combat, counters, keywords, and more are placeholders | Real Forge state parity and viewer-mask tests |
| P0 | Priority phase skip policy may hide response windows | Opponent-turn instant, stack, upkeep, combat response tests |
| P0 | AI took 20–40 seconds on phone for 3-player game | P50/P95 per decision, on-device memory and latency profiles |
| P1 | Action affordability is not full legality; complex mana auto-payment | Costs/targets/tax/X/restricted mana regressions |
| P1 | Static current-host pointer and match shutdown incomplete | Race, teardown, second game, fold/background, stale-epoch tests |
| P1 | Wi-Fi guest pathway reports unsupported | Two-device, reconnect and remote game certification |
| P1 | Some cards may be indexed and constructed without complete rules/AI behavior | Targeted mechanic scenarios and outcome checks |

Already improved in draft PR #16: Ghalta and replacement ordering, multi-block damage allocation, buffered decision answers, and blocked-declaration feedback. Do not redo closed work without reproducing against the newest exact head. The 33,612/33,612 card construction and 1,544/1,544 bundled lookup results mean **construction and name coverage**, not all cards correctly playable.

## Next authorized-phase proposals, NOT implemented yet

1. Extend the new opt-in headless recorder to consent-based Android instrumentation, with bounded heartbeat and explicit progress-owner attribution; do not print personal data.
2. Extend the newly added four-seat adapter startup/handoff smoke to **full seeded 2/3/4-player Commander games**, not only pure Forge four-AI simulations. Capture one minimal reproduction per failure.
3. Property/mutation testing for target selection, replacement effects, optional costs, combat, stack priority and multiplayer elimination. Compare against stock Forge when appropriate.
4. Per-decision P50/P95 AI latency and heap profile on the Fold, plus equivalent Argentum scenarios.
5. Only after those gates are stable, controlled seed rotation, baseline comparisons and optional worker handoff. No autonomous paid loops or merge authority.

**Evidence standard:** (a) exists in card index; (b) instantiates; (c) follows Magic rules in a tested scenario; (d) AI plays it sensibly; (e) its state/choices work through Arcana. These are different gates.

## Four-seat regression: anonymous cleanup discard input

The initial four-seat adapter test exposed a previously unseen defaulted
human decision. Pinned Forge creates an anonymous InputSelectCardsFromList
subclass for compulsory cleanup discards. Anonymous Java classes have no
simple name, so the original Chimera dispatcher labeled it "" and chose
cards automatically. The adapter now discovers the nearest named superclass,
and the four-seat test explicitly expects and answers a cleanup
SelectCardsDecision. The original failing smoke is run 42510565386.
This change still requires exact-head CI verification before calling it fixed.

## Actual phone trample failure — upstream repair adopted, no private log committed

A private user diagnostic exposed an infinite combat-damage reassignment loop. On the latest Chimera transplant branch, the Forge host now makes lethal damage conditional on **assigning positive damage to the defending player**; a short-power attacker is permitted to allocate all its damage to blockers. Upstream scenario D-trample-short tests both the illegal player-damage allocation and the legal blocker-only allocation, including game continuation. The test passed in upstream Forge proof run 42512635578. This draft uses **that** change, not an independent competing implementation. Do not put the original personal diagnostic in this public repository.

## Repeat-rejection detector (private phone findings → synthetic regression)

A user may submit an action, receive an explicit Forge error, and then be
shown the *same* decision with a newer state version. Three consecutive
rejections of the same action type without observable game progress are now
flagged P0 REPEATED_REJECTED_ACTION. One or two invalid attempts, followed
by a valid correction, remain P1 observations and do not fail the gate.

The checked-in rejected-damage-loop fixture is invented, has no real player,
card, or deck information, and is intentionally expected to fail detection.
Never publish raw user diagnostics in the public Chimera repository.
