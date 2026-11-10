# GooseClippy — Chimera-only implementation
Owner direction (Nov 10, 2026): **GooseClippy and the XP skin live only in Chimera. Do not put them in Arcana Table's GitHub repository.**

## Implementation
- Based directly on Claude B's current Chimera PR #16 XP Desktop: `ChimeraXp.kt`.
- `arcana-overlay/app/src/main/java/com/arcanatable/GooseClippyFeature.kt`: persistent on/off setting, draggable assistant, animated transparent idle/talking image, masked game-state observer.
- `arcana-overlay/app/src/main/java/com/arcanatable/GooseClippyLines.kt`: 228 quips, 19 categories, shuffle-bag anti-repetition.
- `ChimeraXpDesktop` displays it as an independent overlay; XP Start menu contains GooseClippy On/Off. Disabling never changes the selected skin or affects gameplay.
- Source and tests copied during Chimera's existing build-time overlay (without writing to Arcana source repository). No Forge engine, rules, Magic artwork, or card-renderer changes.
- Test files are isolated here under `arcana-overlay/app/src/test/java/com/arcanatable/`.

## Required owner-approved GIF assets (must be committed to Chimera to pass APK build)
1. `arcana-overlay/app/src/main/assets/gooseclippy_idle.gif` — transparent stationary/blinking, 240 x 360, 5 frames, 119,843 bytes, SHA-256 `16dde375e89d564b90b160cb460ee42b69dfd2763caaff6d91ce0e3ca8979e92`.
2. `arcana-overlay/app/src/main/assets/gooseclippy_react.gif` — transparent animated reaction, 240 x 360, 12 frames, 170,569 bytes, SHA-256 `40e4833fdb5b69ce1184859a0f14e176571f22cc25cbdf00f2cec9f771c6d959`.

The user has a separately packaged `gooseclippy-android-assets.zip` containing both. Extract the two files to the paths above. **They are not included as binary files in this PR.** The build intentionally fails with a clear missing-asset error instead of silently shipping an unrelated paperclip or placeholder. Verify hashes upon inclusion.

## Test + delivery
- Compile exact Chimera PR branch against the pinned Arcana donor, test on Fold (cover and unfolded), and verify both images animate properly in transparent overlays.
- Verify ON/OFF survives restart; tap gets new quip; live Forge turn/stack/life/hand changes select appropriate category; no short repetition loop; controls remain reachable.
- Existing Arcana main remains unchanged.
- Do not merge into Claude B's PR #16 or deploy/release without separate owner approval.
