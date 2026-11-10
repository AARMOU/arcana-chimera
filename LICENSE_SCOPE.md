# Chimera software licensing and third-party boundaries

The owner selected **GNU General Public License version 3.0** for the
Forge-powered Chimera integration (see `docs/DECISIONS.md`). The full
unaltered text is in [LICENSE](LICENSE).

For independently authored Chimera adapter, overlay, glue and build-tool
source that the contributors have the right to license, the intended project
license is **GPL-3.0-only**. This declaration does not purport to relicense
another copyright holder's code, images, text, card data, trademarks or
other licensed materials.

- **Forge:** GNU GPLv3, copyrighted by its respective contributors. Preserve
  upstream notices. The pinned upstream license copy is retained separately
  in `third_party_licenses/Forge-GPL-3.0.txt`. Exact source must be
  distributed for the exact build as GPL requires.
- **Arcana donor:** Read-only source is staged from
  `AARMOU/arcana_table` at build time. Preserve its original component
  notices and any licenses for transitive dependencies. Do not assume a
  repo-level GPL statement replaces those existing notices.
- **Third-party Java/Android libraries, card assets and card data:** License
  inventory and redistribution conditions remain to be independently audited
  for each final AAB, including items fetched/generated during the build.
- **Google Play:** Signing and accepting a bundle does not establish GPLv3
  compliance. See `docs/GPLV3_RELEASE_READINESS.md` and
  `docs/PLAY_STORE_RELEASE.md` for the source, notice, distribution and
  sign-off gates.

This repository-level license statement is **not** a release approval.
No Forge-powered Play closed-test or production rollout should occur until
the actual binary and corresponding-source audit is complete.
