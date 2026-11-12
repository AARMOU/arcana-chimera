# Chimera: WotC Commander preconstructed deck library

## Owner requirement (November 8, 2026)
- **Chimera only.** Do not edit `AARMOU/arcana_table`.
- In **Change Deck**, list **only official WotC Commander preconstructed decks** (not unrelated Forge, EDHREC, or Arcana premades).
- Present an expandable tree: **product/set → deck name**, with the deck's **color identity mana pips**.
- Child labels are the official precon titles only; no commander subtitle, `WIP` marker, or product prefix.
- Include the original six as their original published WotC decklists: Forces of the Imperium, Necron Dynasties, The Ruinous Powers, Tyranid Swarm, Eldrazi Unbound, Eldrazi Incursion.
- Hide old precon menu entries without deleting their original files. Owner-created/imported personal decks remain accessible.
- **Forge handles card rules and AI.** Do not reintroduce per-card Argentum certification or require each deck to run simulated games.

## Data source and build
- Script: `scripts/build_wotc_commander_precons.py`.
- Public upstream: [MTGJSON](https://mtgjson.com/downloads/) `DeckList.json` plus `SetList.json`, with published SHA-256 checksums. The compiler then downloads only the selected individual Commander deck files, not the enormous all-decks archive.
- Source licensing: [MTGJSON MIT](https://mtgjson.com/license/). The generated app asset includes source and digest metadata.
- Select Commander Deck products (and full 100-card Commander Secret Lair products when cataloged as Secret Lair Drop), use the published commander and mainboard quantities, and exclude unrelated/non-100-card products.
- Compile at **APK build time**, never fetch a decklist from the phone when selected.
- Asset path in staged app: `src/main/assets/precons/wotc-commander-precons.json`.
- No artwork, card scripts, or Oracle text are included in this new asset; it contains only original deck titles, set identity, mana-color letters, and the decklist.

## UI and integration
- Loader: `arcana-overlay/.../ChimeraPrecons.kt`; converts the asset into Arcana's existing `AppPreconDeck` model.
- Tree: `arcana-overlay/.../ChimeraPreconTree.kt`, group headers ordered newest-first; children alphabetically. Color pips use the commander's color identity (W/U/B/R/G), C for colorless.
- Patch: `scripts/patch_commander_precons.py`; strictly anchored to the staged Arcana snapshot and fails on incompatible source changes.
- `TableController.preconDecks` receives the official catalog instead of the old hard-coded six/WIP list.
- `TableOverlays` offers official precon choices and personal saved decks in Change Deck, but **not** Arcana/Argentum built-in premades. The latter remain unchanged in the AI opponent menu and are not deleted.
- Existing deck selection and Forge message contract remain intact.

## Testing boundary
- `python3 -m unittest discover -s tests -p 'test_commander_precons.py'`: synthetic archive and offline indexed-download fixtures, Commander-only filter, original six inclusion and filename stability.
- A real CI build is still required to prove MTGJSON downloads and Compose compilation; a phone run is required to confirm the set tree and color pips on the Fold.
- The compiler's 100-card check is **source-data integrity only**, not Forge game/rules verification.
- If MTGJSON is unavailable at build time, the build fails; it must not fall back to the old/non-WotC lists.
- Never merge or release without owner approval; keep Claude B's `claude-b/forge-brain-transplant-20261107` branch untouched.

## Operational notes
- The automatic source refresh happens when a future APK is built, not continuously on the device. The index/download approach makes bounded requests for Commander products only.
- To make distribution reproducible, pin the MTGJSON archive digests for a final release candidate.
- New Commander precons that MTGJSON has not published yet cannot be fabricated.
- Collector/alternate editions may appear as separately named official product variants if the source provides a different deck file.
