# Testing the Arcana ↔ Forge bridge

**Start here if you are changing anything in `forge-host/` or `arcana-overlay/`.** Every test below runs on every push to
the PR in the **Forge host proof** workflow (`.github/workflows/forge-host-proof.yml`, script
`scripts/forge_host_proof.sh`). A red run means a player-visible problem until proven otherwise.

## What runs, in order

| Step | Class (in `forge-host/src/main/java/org/arcanachimera/forgehost/`) | What it proves | Log line |
|---|---|---|---|
| 1 | `ProtocolGuardSelfTest` | The message checker itself rejects bad messages | `PROTOCOL GUARD RESULT` |
| 2 | `OneTurnProof` | A real 1v1 game: land, creature, targeted spell, attack, AI turn, a second game in the same session | `PROOF RESULT` |
| 3 | `PromptScenarios` A–G | Fixed boards for each bug found so far (below) | `SCENARIO …`, `SCENARIOS RESULT` |
| 4 | `FourSeatProof` | 4 Commander seats: opening hand → AI turns → back to you, with an explicit cleanup discard | `FOUR-SEAT ADAPTER RESULT` |
| 5 | `AllCardsCheck` | Every Forge card (33,612) builds; every card in Arcana's bundled decks (1,544) resolves to a Forge card | `ALLCARDS RESULT` |
| 6 | `SoakTest` | **Random play**: whole games on Arcana's bundled decks, 2–4 seats, random legal actions and random valid answers | `SOAK RESULT` |

`ProtocolGuard` watches every message in steps 2–4 and 6: malformed states/actions/decisions, a state sent mid-mulligan,
or three refusals in a row without progress fail the run.

## The soak test (step 6)

A bot plays seat 0; Forge AIs play the others. A game **fails** on:

| Code | Meaning |
|---|---|
| `STALL` | Nothing from the bridge for 45 s while the game isn't over (thread stacks are printed: look for the bridge or Forge thread that is waiting) |
| `UNBRIDGED` | Forge asked a question the bridge can't show ("can't show yet") |
| `DEFAULTED` | The bridge answered a question for the player (only the play/draw choice is allowed) |
| `HOST_ERROR` | An exception inside the bridge ("Forge host problem") |
| `PROTOCOL` | A `ProtocolGuard` finding |
| `NO_GAMEOVER` | The player lost but Arcana was never told |

A game that reaches the turn limit is fine. The summary line counts finished, turn-limited and failed games.

**Replay a failure:** every game prints `SOAK game seed=<n> …`. The seed fixes the bot's choices and Forge's shuffles and
AI choices (`MyRandom`), so the same seed replays the same game (thread timing can still vary slightly).

**Run more games on demand:** GitHub → Actions → **Forge host proof (milestone 1)** → *Run workflow*, and set
`soak_games`, `soak_seed`, `soak_turns`, `soak_players` (`2-4`, `4`, …) and `soak_ai` (`OFF`, `LITE`, `MAX`). Pushes use
6 games from seed 1, so a red push is repeatable.

**Locally** (after the script has built Forge once):
```
java -Xmx4g -Djava.awt.headless=true -cp "$OUT/classes:$CP" org.arcanachimera.forgehost.SoakTest \
  <forge>/forge-gui/ <arcana>/android/app/src/main/assets games=1 seed=<n> turns=20 players=4 ai=LITE
```

**The bot needs an answer for every decision type.** A new decision type in the bridge fails the soak with
"soak bot has no answer for …": add a case to `SoakTest.Game.answer`.

## Scenarios: one per real bug

| Scenario | Bug it guards (date found) |
|---|---|
| A Ghalta order | Simultaneous-trigger order never asked; both `order` call shapes; replacement-effect order; several non-card picks (Nov 8) |
| B Combat damage | Multi-blocker damage split; Forge's player messages reach Arcana (Nov 8) |
| C Mulligan | Forge's London order translated to Arcana's: new hand → keep → bottom cards; nothing sent mid-mulligan (Nov 8) |
| D Trample short | Lethal-first only when damage goes to the player (Nov 8) |
| E Stack | Abilities shown on the stack with targets; own spells resolve without a stop (Nov 8) |
| F Mana + Convoke | Tap sources yourself and float mana; Convoke choice; the Convoke cost check that froze the bridge (Nov 8) |
| G You lose | Game over reaches Arcana after Forge drops your seat (Nov 8) |

## Rules for changes

1. **No fix without a test that failed before it.** A phone bug becomes a scenario in `PromptScenarios` (fixed board via
   Forge's `GameState` script; list `humanlife`/`ailife` and libraries, which Forge otherwise sets to −1 and empty).
2. **Never answer for the player silently.** An unbridged prompt must show a notice, and the tests treat it as a failure.
3. **No new rules logic in the bridge.** Forge decides legality; the bridge translates.
4. **Read the phone diagnostics.** Every interactive state carries `engineMs` and `engineTimingsMs` (build ms, KB, heap,
   AI strength), and Arcana prints them in its diagnostic log.

Decisions and boundaries: [DECISIONS.md](DECISIONS.md). Review history and phone results: PR #16 comments.
