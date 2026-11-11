# Fold-lock multiplayer host repair — isolated Chimera candidate

**Owner request:** folding and locking the Samsung Fold must not terminate an active Commander room. Related issue: [#33](https://github.com/AARMOU/arcana-chimera/issues/33).

## Diagnosis from the exact November 10 APK source

The failing internet path uses `ArgentumCloudMultiHost` / `ArgentumDirectedLink`, **not the older `CloudRoomClient`**. The source has a definite recovery bug: on a refused host resume it sets `firstWelcome=false`, immediately invokes `disconnected()`, then takes the *initial connection failure* path, destroying an established game. The server also deletes a directed room when its final socket disappears, losing all guest seat tokens. Neither proves every screen-lock failure, but both violate the expected recovery behavior.

## Proposed isolated changes

- `ChimeraRoomHostGuardService.kt`: dedicated, user-started Android connected-device foreground service with notification, partial CPU wake lock and high-performance Wi-Fi lock, held **only while hosting** and explicitly released on `stopWifi()`. It is not tied to Compose teardown.
- `patch_chimera_host_survival.py`: fail-closed patches on **temporary staged Arcana sources**. New LAN and internet lobbies start host guard before opening a transport, and refuse to host if Android blocks it. Directed relay has one reconnect timer, 45-minute grace matching the proposed Worker, and never recreates a fresh room with different private seat credentials after a rejected resume.
- `patch_directed_room_resumption.py`: separate patch on a **temporary copy** of `android/cloud-room/src/index.ts`; holds an unexpectedly disconnected directed room for up to 45 minutes, with verified seat tokens and no automatic turn skip. Intentional final-player leave remains a shutdown. Ignores stale closes after a successor seat has authenticated. Its source exists here **but has NOT been deployed to Cloudflare**.

**Important:** The phone service and retained room improve reliability, but Android Doze can still limit network access; a foreground service is not absolute immunity. No automated process-death restoration or cloud-hosted Forge authority is claimed. Even with the Worker patch, the Forge game still lives on the host's phone.

## Verification procedure

1. Run `python3 -m unittest discover -s tests -p test_chimera_host_survival.py -v` (no Forge compile) and inspect build-time patch results against the pinned donor.
2. Compile a Chimera APK from this branch **once** and confirm Android manifest, Kotlin compilation and the existing Forge tests. Keep the APK and corresponding source, not a CI-only hash.
3. **Before any live Worker deployment**, create an isolated copy of the pinned Arcana Worker, apply `python3 scripts/patch_directed_room_resumption.py /path/to/temporary/cloud-room/src/index.ts`, run `npm ci`, `npm run typecheck`, and existing `node scripts/verify-directed-relay.mjs` against local dev. Add a two-device test where every socket disconnects and the host securely rejoins with its original token.
4. Request explicit **owner authorization before deploying Cloudflare**; do not edit Arcana main or Claude B's branch.
5. On the Fold with a second real player connected, lock/fold for 5, 15 and 30 minutes, verify guest continuity, automatic resync, distinct action IDs (no duplicates), notification, CPU/battery usage, and reliable unlock. Also test Android power restrictions and intentional host exit.

## Release restrictions

This is a **draft fix**, not a verified success on the owner's phone. No PR merge, Worker deployment, signing-key usage, Google Play upload or public APK release is authorized by these changes. GPL duties remain separate.
