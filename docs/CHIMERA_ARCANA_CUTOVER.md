# Recommended Chimera → Arcana cutover (proposal; NO transfer authorized yet)

**Status: architectural proposal, not a merge, release, or declaration of GPL compliance.**
**Owner question (November 10, 2026):** should everything move from Chimera into
`AARMOU/arcana_table`? **Recommendation: only tested production code and release
infrastructure**, once the Forge replacement is proven.

## One release owner, one engine

Once the owner approves cutover, the *Arcana* repository should become the
single authoritative home of the Android application that goes to the existing
`com.arcanatable` Play listing. The app should contain **Forge only** for
gameplay; avoid silently retaining a second rules engine or untested fallback.
The Chimera repository remains the experimental source/prototype archive.

**Never put the experimental Chimera debug applicationId on the existing Play
listing.** The release bundle needs `com.arcanatable`, a version code greater
than every current Play track, API level 36 or higher for 2026 updates, and an
upload signature matching the original Play Console upload certificate.

## Promote to an Arcana integration branch, after review

| Promote | Where it belongs |
| --- | --- |
| Proven Forge host/bridge and its protocol tests | Arcana Android source, using the same pinned Forge revision |
| Tested Arcana UI adaptations needed for Forge | Native Arcana app sources (not an independent second copy) |
| Production-quality Chimera Studio/skin/GooseClippy features | App code/assets only after merged integration/device QA |
| Exact-source capture, license notices, dependency inventory, clean rebuild check | Arcana's **release** pipeline, not runtime-only scripts |
| Signed AAB build controls (without secrets) | Arcana's isolated Play release workflow |
| Forge GPLv3 license plus all license/copyright notices | Arcana distribution package and an in-app Licenses/About entry |
| Source package delivery instructions and build-specific checksums | Durable Arcana release page/archive |

## Keep only in Chimera

- Headless Forge research, one/four-seat experiments, investigative APKs, REA
  evidence tools, comparative Argentum-vs-Forge test harnesses.
- Staging experiments and temporary migration scripts that are not needed to
  compile/install/modify the released application.
- Standalone FrankenArcana proofs, discarded UIs, old encrypted handoff trials,
  and the experimental repository's historical logs.
- Do **not** move any original upload keystore, passwords, Play signing
  material, CI tokens or private configuration into **either** Git repo.

Some Chimera build scripts and patch logic *will* be Corresponding Source for
the last Chimera-built binary; retain those as part of its exact source
package. Once the same changes are applied as first-class Arcana source,
remove redundant staging from production **only after** a clean source-to-AAB
reconstruction shows the scripts are no longer required.

## Safe procedure / dependencies

1. Freeze a tested Chimera integration snapshot **including Claude B PR #16,
   GooseClippy draft #24, and their currently approved related assets**, at
   explicitly reviewed commits. Those drafts are open and must not be treated
   as finished or merged.
2. Prove multi-seat Commander gameplay and Studio UX against Forge on a real
   Android device; run regression tests for lifecycle, background process,
   card imports, network rooms, license menu and the existing Play package.
3. Owner explicitly approves the creation of one **non-main**, non-deploying
   `AARMOU/arcana_table` integration branch and the scope of the copy.
   No write to Arcana occurs from this document alone.
4. Make **one atomic reviewed cutover**: real Arcana UI + Forge host + assets
   + tests + AAB release controls + notices; do not transfer old experimental
   tooling or duplicate production workflows.
5. Produce signed build and same-build corresponding source in the Arcana
   pipeline, preserving Forge and all other license notices, tool versions,
   dependency source inventories, installation information evaluation,
   versioned source URL and build hashes. Audit clean rebuild.
6. Verify upload certificate against Play Console, verify release package
   and version code, and do on-device Android/Play test track QA.
7. Owner approves the final merge and actual Play upload/rollout separately.
   Keep rollback to the prior known-good Arcana release.

## Licensing does not depend on repo location

Forge's GPLv3 obligations arise when covered compiled software is conveyed,
not when a Git repository is renamed. A public repository containing only
patches and upstream links is not automatically the Complete Corresponding
Source for the final Android AAB. Recipients need access to the source for
**their exact build**, including changes and controls needed to rebuild.

Relevant references:
- [GNU GPLv3, sections 1, 4–6](https://www.gnu.org/licenses/gpl-3.0.html)
- [FSF FAQ: source matching binaries](https://www.gnu.org/licenses/gpl-faq.html.en)
- [Play API target requirements](https://support.google.com/googleplay/android-developer/answer/11926878)
- [Existing GPL release blockers](GPLV3_RELEASE_READINESS.md)
- [Existing Play release plan](PLAY_STORE_RELEASE.md)

## No-go conditions

Do not move production code or distribute a Forge AAB when any of these is
true: Claude's branch is still changing without reconciliation; source package
is incomplete or includes secrets; dependency licensing unresolved; no
accessible notices and version-matched source; Play upload certificate
unverified; any unapproved Arcana merge/Cloudflare deploy; or unresolved
on-device acceptance failures.
