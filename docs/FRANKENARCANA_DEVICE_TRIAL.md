# FrankenArcana — one-phone live table trial

**Status: source code prepared. APK has not yet compiled, installed or been tested.**

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

The APK may fail to compile due to SDK/Maven/plugin compatibility. Forge may require first-run game assets or storage permissions. The Android overlay may need layout adjustments on the Fold. Some interactive decisions require the Forge Controls fallback. These are genuine unknowns, not passed tests.

## Delivery revision (2026-11-03)

An encrypted, short-lived delivery path now exists: `.github/workflows/frankenarcana-encrypted-handoff.yml`. This is manual-only, standard public runner, same local signed APK build. The runner uploads an artifact with **only** `FrankenArcana.apk.enc` (AES-256-CBC/PBKDF2), `password.rsa` (RSA-OAEP wrapped unique one-run password), `APK.sha256`, and source/licensing notes. Retention: **one day**. It NEVER uploads a plaintext APK. The private RSA key is held outside GitHub, so the public artifact is not an installable APK. After securely retrieving it, decrypt and verify its SHA-256, then provide a private APK directly to the owner. Until download and decryption are verified, do not claim successful APK delivery. No Arcana–Argentum writes or paid compute.
