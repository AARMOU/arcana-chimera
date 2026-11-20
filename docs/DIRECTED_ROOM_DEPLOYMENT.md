# Chimera directed-room Fold-lock deployment — independent lane

Owner request, November 11, 2026: host phone must survive folding/locking. This is **only the Cloudflare room Worker component**. The Android foreground-host and in-game Engine running default-off changes are separately staged in Chimera PR #36. Neither lane changes `AARMOU/arcana_table` main.

## Proven locally

The worker patch in `scripts/patch_directed_room_resumption.py` applies strictly to pinned Arcana donor commit `a5c428ad3da42b7325adf6630238ec992fee3113` in an ephemeral tree, without pushing to Arcana.

`chimera-directed-worker-smoke.yml` clones that precise Worker, applies the patch, typechecks it, starts local Wrangler, runs the donor directed-relay privacy suite plus `tests/verify-directed-room-all-offline.mjs`, and deploys **nothing**. The smoke tests already passed in [Chimera Actions #42980216060](https://github.com/AARMOU/arcana-chimera/actions/runs/42980216060) using the identical scripts.

The patch preserves an unexpectedly disconnected directed room and private seat tokens for a bounded 45-minute resume window, fixes stale close eviction, keeps ordinary non-directed room cleanup, and prevents false turn skip when a directed seat loses its socket. It does not host Forge in the cloud.

## Production deployment (owner approval and credentials required)

After this small standalone PR is reviewed and **explicitly merged by the owner**, the GitHub manual deploy workflow becomes available from Chimera's default branch:

1. In `AARMOU/arcana-chimera`, set scoped repository secrets `CLOUDFLARE_API_TOKEN` and `CLOUDFLARE_ACCOUNT_ID`, if absent. Never paste these secrets in chat or commit them.
2. On the GitHub **Actions** tab, open **Deploy Chimera directed-room recovery (manual owner approval)**.
3. Select **Run workflow**, enter `DEPLOY_CHIMERA_ROOM_RECOVERY` and run. The `production` environment may require additional owner review.
4. The workflow runs same-code typecheck and local WebSocket tests before `wrangler deploy`, and checks that the exact target name is `arcana-table-rooms` with existing `GameRoom` SQLite Durable Object migration.
5. Review logs for a successful deploy and run a real two-phone Fold lock/unlock test (5, 15 and 30 minutes), with original seat recovery and unchanged game state. Verify the Worker is actually the service configured in the app. If host process is force-stopped, a full game restore is not yet implemented.

**Important:** No Cloudflare deployment or PR merge is performed merely by creating this PR. This is designed to avoid merging PRs #31–#36 just to deploy the Worker and avoid paying for unnecessary APK rebuilds.
