# Chimera decisions and guardrails

## Owner override — November 3, 2026 (Mountain time)

The owner expressly authorized making this research-only repository **public** to attempt one **free** GitHub Actions Forge Commander game. This supersedes only the November 2 prohibition on publication and GitHub Actions in private repositories. Never start a paid runner. Use only standard `ubuntu-24.04` after the repository is public, with manual `workflow_dispatch`, no scheduling or automatic retries, and no publicly uploaded compiled engine or assets. Visibility must be changed by the owner in GitHub Settings; the integration cannot alter visibility.

The other boundaries remain in force: do not touch production Arcana, build APKs, deploy, distribute protected artwork or databases, spend AI/API credits, or merge unrelated work. Public visibility exposes repository history and old Actions logs.

## Original November 2 direction (superseded only as noted above)

**Owner direction: November 2, 2026 (Mountain time).** Explore Forge in a dedicated private repository named `AARMOU/arcana-chimera`.

**Authorized:** Setup and documentation for an independent, read-only-first research experiment; preparation of local build probes and a test matrix.

**Not authorized:** Touch production Arcana; change the engine in B142; start paid workers; spend AI/API credits; create app releases; deploy services; make the repo public; or auto-merge anything.

**No cross-project control:** Chimera is not part of the Arcana production dispatch pipeline. The existing repair drill and current-orders board in Arcana remain authoritative for Arcana work.

**Next decision:** Whether to run manual Forge compilation and gameplay tests, then (only if they succeed) develop a throwaway one-turn Android proof of concept.

**Current result:** No Forge builds or gameplay tests executed by this repository's setup. All acceptance-matrix entries begin as `not_run`.
