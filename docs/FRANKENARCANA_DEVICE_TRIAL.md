# FrankenArcana — one-phone live table trial

**Status: APK compiled and signed in [run #41710340893](https://github.com/AARMOU/arcana-chimera/actions/runs/41710340893), delivered privately to the owner's Google Drive, installed, and played on a Fold 6 Android 17. Forge menus and rules/AI ran; Arcana-style table appeared when the match began, but its card/action buttons were not reliably playable. A candidate native-input bridge repair is pending compilation and phone test.**

This is a Chimera-only, disposable Android attempt. It does not change Arcana–Argentum.

## What is implemented

The launcher is Chimera-branded and opens a pinned real Forge Android game. During an active human-vs-AI match, the new independent ArcanaTableOverlay appears. It follows Arcana Table's dark green/Mint/Gold palette and its main battlefield arrangement: player seats and life, opponent battlefield, your battlefield, command zones, phase tracker, stack count, hand drawer, and bottom game-action buttons.

This is **NOT** fake/simulated cards. The overlay reads the real Forge GameView via MatchController, strictly on Forge's LibGDX thread. It counts opponents' hidden cards rather than exposing their names and masks other invisible card names. Tapping a card sends that exact card to Forge's real selectCard controller; the prompt buttons send actual OK/Cancel actions with text and enabled state from Forge's active prompt.

Because a full Arcana Compose port would be much larger, this trial uses an Arcana-styled native Android overlay over the working Forge runtime, instead of building the literal Compose source. The real Arcana source is available for read-only comparison via scripts/stage_arcana_ui.sh. Forge's own setup menus remain in place; the FORGE CONTROLS button hides the overlay to handle complex prompts, and ARCANA TABLE restores it.

## Isolation and build

- Forge source SHA: 7c34365c749ca46ab71336c5f267340d6088e600
- Arcana UI read-only reference SHA: 05cdd6dae84e7df4c88e630054cdc6a76dfa2964
- Debug application ID: org.arcanachimera.frankenarcana (not com.arcanatable)
- Only downloaded Forge and Chimera-created source are patched inside ignored third_party/forge-device.
- No commits, branches, PRs, comments, builds, deployments, or API writes to AARMOU/arcana_table.
- Temporary manifest clears upstream Sentry DSN and disables default PII reporting.
- No paid compute, API credits, deployment, public release or artifact upload.
- The manually triggered workflow .github/workflows/frankenarcana-android.yml uses a standard free public runner (55-minute cap, contents read-only) and does **not** upload the resulting APK.
- Forge GPLv3 obligations and third-party Magic content rights still need review before distributing any combined binary.

To build on a Linux machine with JDK 17, Git, Maven, Python 3 and Android SDK/build tools 35:

    ANDROID_SDK_ROOT=/path/to/sdk bash scripts/build_frankenarcana.sh

The script compiles pinned Forge's Android profile, checks the separate APK package ID, and prints its local checksum. The same script runs from the manual GitHub workflow, but CI discards the APK at job completion. A separate private APK delivery route requires owner approval.

## One test, not a campaign

1. Install the private debug APK beside normal Arcana.
2. From Forge's setup menu, start one Commander human-vs-AI match.
3. Confirm the Arcana-style table appears with the **actual** Forge game state.
4. Tap a card or respond to a prompt; verify Forge executes it.
5. Watch Forge's AI respond and the same table refresh.

Only a completed on-phone interaction passes. There is no claim of a working install or game yet. No mass simulations or cosmetic overhaul are required.

## Unproven risks

The previous Forge Android compilation and signing passed, but the encrypted-delivery workflow is untested. Forge may require first-run game assets or storage permissions. The Android overlay may need layout adjustments on the Fold. Some interactive decisions require the Forge Controls fallback. These are genuine unknowns, not passed tests.

## Delivery revision (2026-11-03)

An encrypted, short-lived delivery path now exists: `.github/workflows/frankenarcana-encrypted-handoff.yml`. This is manual-only, standard public runner, same local signed APK build. The runner uploads an artifact with **only** `FrankenArcana.apk.enc` (AES-256-CBC/PBKDF2), `password.rsa` (RSA-OAEP wrapped unique one-run password), `APK.sha256`, and source/licensing notes. Retention: **one day**. It NEVER uploads a plaintext APK. The private RSA key is held outside GitHub, so the public artifact is not an installable APK. After securely retrieving it, decrypt and verify its SHA-256, then provide a private APK directly to the owner. Until download and decryption are verified, do not claim successful APK delivery. No Arcana–Argentum writes or paid compute.


## Owner delivery directive — November 3, 2026

**If the current encrypted handoff run does not produce an installable, recoverable APK, then fix the concrete problem and retry with delivery to the owner's connected private Google Drive.** The owner specifically wants the **normal installable `.apk` uploaded to their Google Drive**, not merely an encrypted archive, GitHub status badge, code repository, or a build that deletes the APK at the end.

- Destination: owner's **My Drive** (root, unless a folder is explicitly specified later); keep sharing private. Do not publish a release, unencrypted APK, or public download in Chimera's public repository.
- After a corrected build, retrieve the *actual APK* through a supported private transfer, verify it has package `org.arcanachimera.frankenarcana`, and upload the APK through the connected Google Drive integration. Verify the returned Drive file and link before reporting delivery.
- If the transfer tool only provides an encrypted ZIP or an artifact whose matching private key is unavailable, **do not upload it and call it an APK**. Report the blocker and use a different lawful, private delivery route.
- No writes to Arcana–Argentum, no paid services, and no automatic build retry loops. Do not assert on-device success without an observed game turn.
- Current encrypted workflow remains in progress at the time of this instruction; do not interrupt it solely to change delivery preference.

## Recovery after failed encrypted run #41704869928 (2026-11-03)

[Run #41704869928](https://github.com/AARMOU/arcana-chimera/actions/runs/41704869928) failed **before packaging**, during the second Maven invocation, because Maven Central reset its connection while fetching the `maven-compiler-plugin:3.5.1` dependency of the Android Maven extension. Forge's first Maven build still passed. No encrypted artifact or installable APK was generated by that run.

A narrow repair now gives the second Maven invocation **one** retry after 15 seconds **only** for an explicitly logged Maven network transfer failure. Compilation, code and Android errors are not retried. Separately, the workflow's public RSA delivery key has been replaced with one whose matching private key was generated and tested outside GitHub; the public key alone is committed. `scripts/delivery/recover_apk.sh` can decrypt a future encrypted bundle and refuses an invalid APK or mismatched SHA-256.

**Owner's delivery requirement remains:** retrieve and verify the actual `.apk`, then upload the **normal APK** privately to their connected Google Drive (My Drive root), verify the returned file/link, and only then announce successful delivery. Do not post the decrypted binary in the public GitHub repo. The code/temporary run artifact is not a Google Drive delivery; **nothing has been uploaded to Drive yet**. No rerun has been triggered by this change.

## Fold 6 hands-on verdict — November 3, 2026

Owner directly observed that FrankenArcana first displayed native Forge menus, then automatically switched to the Arcana-style battlefield upon starting a match. **The overlay largely displayed but its gameplay buttons did not work reliably; owner had to press FORGE CONTROLS to return to Forge and play cards.** This establishes actual UI presentation but **not** a working gameplay-input bridge.

The phone logs (`forge-logs-20261103-133015.zip`) showed real turn progression, card plays and combat, without a fatal crash. They do not individually attribute those actions to overlay taps; the owner explicitly confirms they were made using the fallback Forge UI.

Root-cause candidate from upstream code review: Chimera was dispatching `IGameController.selectCard()` directly on LibGDX's **render thread**, but native Forge's `VCardDisplayArea.CardAreaPanel.tap()` invokes `ThreadUtil.invokeInGameThread()` and then `CardAreaPanel.selectCard(false)` for real card actions. This repair uses Forge's actual `CardAreaPanel` selection, correctly dispatched to the game-thread pool. Our OK/Cancel buttons now invoke the actual Forge `FButton.trigger()`, preserving Undo/End Turn semantics; rejection produces brief visible feedback instead of silent nothing.

**Not yet compiled, delivered, or phone-tested:** the patched bridge. Controls for complex interactions (targets, mana, attacks, blocks) may still require FORGE CONTROLS. Do not count Phase Tracker labels as interactive phase-change buttons. Do not report success without the owner confirming a card played using the Arcana-style UI itself. Only Chimera changed; Arcana–Argentum remains untouched.
