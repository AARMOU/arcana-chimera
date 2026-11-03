# FrankenArcana — one-shot disposable Forge transplant

Owner scope: November 3, 2026. **This is an entirely separate experiment in `AARMOU/arcana-chimera`, not a change request for Arcana–Argentum.**

## Hard write boundary

- **Read-only source of reference:** `AARMOU/arcana_table`. Inspect relevant public/authorized source to understand the UI, game-message protocol, deck selection and seat handling.
- **Zero contributions back:** no commits, pushes, branches, PRs, issues, comments, workflow changes, merges, releases, tags, labels, dispatches, deployments, or configuration changes in `AARMOU/arcana_table`.
- Never give Chimera CI credentials that can write to Arcana, or invoke Arcana CI as a shortcut. Do not change the Arcana–Argentum upgrade, automation pipeline, active builds, engine version, or APK.
- All prototype code and discussion live in `AARMOU/arcana-chimera`. Keep its work on an isolated development branch; no reliance on private workstation or PC uptime.
- `AARMOU/arcana-chimera` is public. **Do not mirror Arcana source files, secrets, deck libraries, private assets, copyrighted card artwork/text packs, or downloaded Forge binary/assets into its Git history.** Read Arcana to reproduce the minimal compatible interface in *original* Chimera code.
- Fetch pinned public Forge source only into ignored temporary space. Review GPLv3 and third-party content obligations before distributing combined APKs. **No public APK/binary/artifact upload**, no production release, no paid compute/API, and no new cloud deployment. A private device-build/delivery path requires explicit approval of the packaging route.
- If any task needs writing to Arcana, spending money, a public binary, or weakening these guardrails, stop and ask the owner.

## One-shot goal — not a certification campaign

Make one deliberately rough, disposable prototype, ultimately on the owner's Android phone:

1. **Keep Arcana's actual current table UI as the visual source**, as close to pixel-identical as the disposable trial allows: board, hand, player seats, phase/priority strip, card presentation and bottom PASS / HAND / TABLE controls. Clone its pinned public source into ignored `third_party/arcana-ui` using `scripts/stage_arcana_ui.sh`. It is permitted to experiment with this source **only inside a Chimera-owned temporary workspace**; never push patches to Arcana and never commit an Arcana source mirror into public Chimera.
2. Wire that table to one real local Forge game (actual Forge rules + AI), selecting one human and one AI with upstream runtime deck data. **Do not substitute the stock Forge Android game screen or a decorative mock table for this integration.** An early build can omit unsupported Arcana menus, networking and deck editing, but its playable screen must remain Arcana's UI.
3. User taps one genuine legal action (e.g. play a land / pass priority). The adapter passes that action to Forge. The UI receives and displays the real resulting game state.
4. Opposing Forge AI takes a real turn and the new state renders. That is the one-turn acceptance gate.

**Success:** one human input -> Forge executes it -> Forge AI responds -> visible update on an Android device, without Arcana production involvement.

**Failure:** record the first concrete blocker (e.g. Android packaging, initialization, action bridge, memory, card resources) and stop. No million-game benchmark; no attempt to polish online rooms, full deckbuilder, networking or publishing.

## Integration design hypothesis (not yet proven)

Read-only Arcana reference (pinned source `05cdd6dae84e7df4c88e630054cdc6a76dfa2964`): `TableMainLayout.kt`, `TableBoardViews.kt`, `TableModel.kt`, `BattlefieldLayout.kt` implement the actual Compose game table. `android/app/src/main/java/com/arcanatable/engine/LocalArgentumHost.kt` translates offline rules operations into `/game`-style messages (connected, stateUpdate, gameStarted/gameOver) and masks per-player information. Forge's `AiController` is coupled to Forge `Game`, `Player`, `SpellAbility`, so the prototype must use both **Forge rules and Forge AI**, behind a **new Chimera-owned adapter**. This is an experimental protocol/visual contract, *not* an import or replacement inside Arcana.

Read-only Forge: `Card-Forge/forge` at commit `7c34365c749ca46ab71336c5f267340d6088e600`. Forge already has Android/mobile modules, although whether they fit a small native prototype remains to be demonstrated.

## Existing proof, not extra gate

- [Two-AI Commander game, green](https://github.com/AARMOU/arcana-chimera/actions/runs/41662709183).
- [Four-AI Commander game, green](https://github.com/AARMOU/arcana-chimera/actions/runs/41677025921): 27 turns, Reap the Tide victory in 28,447 ms.

These prove headless gameplay only, not APK integration. **Do not schedule another batch of game simulations instead of the phone prototype.**

## Owner visual-correction override — November 3, 2026

The owner explicitly rejected a stock Forge launcher/game screen as the target: **“It's arcana with forge. I want the same ui as it currently is. ... as close as you can get for this trial run.”** The earlier donor-launcher-only spike is abandoned as insufficient and must not be merged as a successful FrankenArcana prototype. The relevant UI source is staged read-only from Arcana, while the Forge adapter and every experimental patch remain in Chimera. A PASS cannot be declared just because Forge's unmodified Android screen opens.

## Implemented device-spike candidate (not yet build-verified)

To move toward the owner's requested quick proof, Chimera now has original source files for an **Arcana-style Android overlay on top of Forge's real mobile game**. The overlay follows Arcana's dark green/Mint/Gold palette and common table flow (seats, field, commander, hand, phases, pass controls), but **is not the actual Compose layout**. It obtains all displayed game state from Forge's real `MatchController.getGameView()` on the LibGDX thread, masks hidden opponent hands, and sends inputs to Forge's own game controller. The full native Forge controls remain available for complex decisions. See [implementation and build details](FRANKENARCANA_DEVICE_TRIAL.md).

**No compiled APK and no verified on-device action/AI turn yet.** A manual build and device test remain essential. This shortcut prioritizes a real match on the user's phone over immediate pixel-perfect UI parity. Do not claim it replaces Arcana or has been validated until proven.

## Owner directive: do the private delivery without publicly publishing the APK

A separate manual `frankenarcana-encrypted-handoff.yml` workflow packages only *encrypted* data as a one-day artifact, not an APK or unencrypted binary. This is the narrow delivery mechanism for the owner’s requested on-phone trial; the earlier build-only workflow remains. The workflow does not push to Arcana, publish an installable APK, run automatically, or incur paid compute. The RSA private key stays outside GitHub. Source and legal obligations remain applicable when the owner receives the decrypted file.
