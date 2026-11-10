#!/usr/bin/env python3
"""Build-time patches for the owner's third Nov 9 list (run after patch_arcana_nov9.py, before patch_arcana_wording.py).

 4  Concede is written to the diagnostics.
 5  "Pass to my turn" doesn't stop at your own declare-attackers step (it stops only when passing isn't possible).
 8  Opening hand: an even, centred grid; every card shows without scrolling where they fit.
 9  Combat: cards centred; tapped / summoning-sick / otherwise unavailable creatures shown dimmed with the reason.
10  Tap a card or ability name in a choice to see the card.
11  Pictures: a fallback chain (chosen link, full card picture, by-name lookup, then name and text); failures logged.
 3  Home screen commander picture: Studio option Cropped / Full card / Full art, and tap to view it full screen.
14  Terminal preset settings in Studio (the layouts themselves are in ChimeraTerminal.kt).

Each replacement is anchored and must match exactly once, so a changed Arcana source fails the build loudly.
Never committed to arcana_table.
"""
import sys
from pathlib import Path

app = Path(sys.argv[1]) / "src/main/java/com/arcanatable"


def patch(rel, pairs):
    path = app / rel
    text = path.read_text()
    for old, new in pairs:
        n = text.count(old)
        if n != 1:
            sys.exit(f"patch_arcana_nov9c: {rel}: expected 1 match, found {n}: {old[:90]!r}")
        text = text.replace(old, new)
    path.write_text(text)
    print(f"patched {rel} ({len(pairs)} edits)")


# ------------------------------------------------------------------ 4. Concede in the diagnostics
patch("TableOverlays.kt", [
    ("""    ChimeraConcedeDialog { if (argentumEnabled) argentumSocket.concede() else concede(localSeat) }""",
     """    ChimeraConcedeDialog { recordDiagnostic("Player conceded", DiagnosticCategory.ACTION); if (argentumEnabled) argentumSocket.concede() else concede(localSeat) }"""),
])

# ------------------------------------------------------------------ 5. pass-to-my-turn skips your own attack step
patch("ArgentumPassing.kt", [
    ('''        "DeclareAttackers" in legalActionTypes -> Outcome(Step.INTERRUPTED, "You can declare attackers.")''',
     '''        "DeclareAttackers" in legalActionTypes && "PassPriority" !in legalActionTypes -> Outcome(Step.INTERRUPTED, "You can declare attackers.")'''),
])

# ------------------------------------------------------------------ 8. opening hand grid
patch("ArgentumScreens.kt", [
    ('''StudioText("ArgentumScreens.Text.18", "Stack and actions", false, "ARGENTUM", color = Mint''',
     '''StudioText("ArgentumScreens.Text.18", "Stack and actions", false, "OPENING HAND", color = Mint'''),
    ('''                    val grid = HandLayout.compute(cards.size, maxWidth.value, maxHeight.value, HandArrangement.AUTO_FIT, 1.25f)
                    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(HandLayout.GAP.dp)) {
                        cards.chunked(grid.columns.coerceAtLeast(1)).forEach { row ->''',
     '''                    // Owner, Nov 9: an even, centred grid; the largest cards that let the whole hand show at once.
                    val grid = HandLayout.compute(cards.size, maxWidth.value, maxHeight.value - 4f, HandArrangement.AUTO_FIT, 1.6f)
                    val perRow = if (grid.rows > 0) (cards.size + grid.rows - 1) / grid.rows else cards.size
                    Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).heightIn(min = maxHeight),
                        verticalArrangement = Arrangement.spacedBy(HandLayout.GAP.dp, Alignment.CenterVertically), horizontalAlignment = Alignment.CenterHorizontally) {
                        cards.chunked(perRow.coerceAtLeast(1)).forEach { row ->'''),
])

# ------------------------------------------------------------------ 9. combat boards
patch("CombatStackBoard.kt", [
    ("""import androidx.compose.foundation.layout.Box
""", """import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.wrapContentWidth
"""),
    ("""private fun CenteredGrid(tag: String, content: @Composable () -> Unit) {
    FlowRow(
        Modifier.fillMaxWidth().tutorialTarget(tag),
        horizontalArrangement = Arrangement.spacedBy(12.dp, Alignment.CenterHorizontally),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) { content() }
}""",
     """private fun CenteredGrid(tag: String, content: @Composable () -> Unit) {
    // Owner, Nov 9: the cards sit in the middle; the grid is as wide as its widest row and centred in the screen.
    Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.TopCenter) {
        FlowRow(
            Modifier.wrapContentWidth().tutorialTarget(tag),
            horizontalArrangement = Arrangement.spacedBy(12.dp, Alignment.CenterHorizontally),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) { content() }
    }
}

/** Your creatures that can't take part right now, dimmed, with why (owner, Nov 9). Not selectable. */
@Composable
private fun UnavailableCreatures(cards: List<GameCard>, sizing: CombatSizing, reason: (GameCard) -> String) {
    if (cards.isEmpty()) return
    SectionLabel("NOT AVAILABLE", Muted)
    CenteredGrid("combat-unavailable") {
        cards.forEach { card ->
            val id = "unavailable-${card.id}"
            StackTile(CombatStack(id, listOf(id)), CombatPiece(id, card.name, card), sizing, chosen = 0, selected = false, enabled = false,
                note = reason(card), noteColor = Muted, onTap = {}, stepper = null)
        }
    }
}"""),
    ("""    players: List<TablePlayer> = emptyList(), viewerId: String? = null,
) {
    val optionFor = plan.options.associateBy { it.attackerId }""",
     """    players: List<TablePlayer> = emptyList(), viewerId: String? = null,
    unavailable: List<GameCard> = emptyList(),
) {
    val optionFor = plan.options.associateBy { it.attackerId }"""),
    ("""                    }) else null,
                )
            }
        }
    } }

    picking?.let { stack ->""",
     """                    }) else null,
                )
            }
        }
        UnavailableCreatures(unavailable, combatSizing(zoom.floatValue, small = true)) { c ->
            when { c.tapped -> "TAPPED"; c.summoningSick -> "SUMMONING SICK"; else -> "CAN'T ATTACK" } }
    } }

    picking?.let { stack ->"""),
    ("""    defender: TablePlayer? = null,
) {
    val required = plan.mandatory.filterKeys { it in plan.blockers }""",
     """    defender: TablePlayer? = null,
    unavailable: List<GameCard> = emptyList(),
) {
    val required = plan.mandatory.filterKeys { it in plan.blockers }"""),
    ("""        if (focusStack != null && blockersOf(focusStack.ids).isEmpty() && againstMe.isNotEmpty())""",
     """        UnavailableCreatures(unavailable, small) { c -> if (c.tapped) "TAPPED" else "CAN'T BLOCK" }
        if (focusStack != null && blockersOf(focusStack.ids).isEmpty() && againstMe.isNotEmpty())"""),
])
patch("TableOverlays.kt", [
    ("""            viewerId = argentumSnapshot?.let { snap -> snap.players.getOrNull(snap.viewingPlayer)?.id },
            onConfirm = { action ->""",
     """            viewerId = argentumSnapshot?.let { snap -> snap.players.getOrNull(snap.viewingPlayer)?.id },
            unavailable = argentumSnapshot?.let { snap -> val can = plan.options.map { it.attackerId }.toSet()
                snap.players.getOrNull(snap.viewingPlayer)?.battlefield.orEmpty().filter { it.type.contains("Creature", ignoreCase = true) && snap.serverCardIds[it.id] !in can } }.orEmpty(),
            onConfirm = { action ->"""),
    ("""            defender = argentumSnapshot?.let { snap -> snap.players.getOrNull(snap.viewingPlayer) },
            onConfirm = { action ->""",
     """            defender = argentumSnapshot?.let { snap -> snap.players.getOrNull(snap.viewingPlayer) },
            unavailable = argentumSnapshot?.let { snap -> val can = plan.blockers.toSet()
                snap.players.getOrNull(snap.viewingPlayer)?.battlefield.orEmpty().filter { it.type.contains("Creature", ignoreCase = true) && snap.serverCardIds[it.id] !in can } }.orEmpty(),
            onConfirm = { action ->"""),
])

# ------------------------------------------------------------------ 10. tap a card or ability name in a choice
patch("TableOverlays.kt", [
    ("""                Column(Modifier.heightIn(max = 460.dp).verticalScroll(rememberScrollState())) {
                    when (kind) {""",
     """                Column(Modifier.heightIn(max = 460.dp).verticalScroll(rememberScrollState())) {
                    ChimeraChoiceCards(remember(decisionId) { ChimeraChoice.entries(decision, { argentumPickCard(it) },
                        argentumSnapshot?.players.orEmpty().flatMap { it.battlefield + it.graveyard + it.exile + it.hand + it.revealed }) })
                    when (kind) {"""),
    ("""                            nameOf = { id -> ArgentumDecisions.cardName(decision, id) { argentumSnapshot?.targets?.get(it)?.name } },
                            firstLabel = decision.optString("firstLabel").takeIf { it.isNotBlank() && it != "null" },
                            lastLabel = decision.optString("lastLabel").takeIf { it.isNotBlank() && it != "null" },
                            onChange = { argentumOrderSelection = it }
                        )""",
     """                            nameOf = { id -> ChimeraChoice.orderLabel(decision, id, ArgentumDecisions.cardName(decision, id) { argentumSnapshot?.targets?.get(it)?.name }) },
                            firstLabel = decision.optString("firstLabel").takeIf { it.isNotBlank() && it != "null" },
                            lastLabel = decision.optString("lastLabel").takeIf { it.isNotBlank() && it != "null" },
                            onChange = { argentumOrderSelection = it },
                            onName = { id -> ChimeraChoice.open = ChimeraChoice.forId(decision, id) { argentumPickCard(it) } }
                        )"""),
])
patch("ArgentumUi.kt", [
    ("""fun ArgentumOrderEditor(items: List<String>, nameOf: (String) -> String, firstLabel: String?, lastLabel: String?, onChange: (List<String>) -> Unit) {""",
     """fun ArgentumOrderEditor(items: List<String>, nameOf: (String) -> String, firstLabel: String?, lastLabel: String?, onChange: (List<String>) -> Unit, onName: ((String) -> Unit)? = null) {"""),
    ("""                StudioText("ArgentumUi.Text.55", "Table and player", true, nameOf(id), color = SoftWhite, fontSize = 12.sp, modifier = Modifier.weight(1f))""",
     """                StudioText("ArgentumUi.Text.55", "Table and player", true, nameOf(id), color = if (onName != null) Mint else SoftWhite, fontSize = 12.sp,
                    modifier = Modifier.weight(1f).then(if (onName != null) Modifier.clickable { onName(id) }.padding(vertical = 6.dp) else Modifier))"""),
])

# ------------------------------------------------------------------ 11. pictures: fallback chain, failures in the report
patch("TableBoardViews.kt", [
    ("""    LaunchedEffect(url, fallbackName) {
        if (bitmap == null) bitmap = loadCardArtWithRetry(url) { retryContext -> CardImages.loadForCardArt(url, fallbackName, retryContext) }
    }""",
     """    LaunchedEffect(url, fallbackName) {
        if (bitmap == null) {
            // Owner, Nov 9: the chosen link, then the full card picture, then a lookup by name; the name and text stay if all fail.
            val name = (fallbackName ?: label)?.takeIf { it.isNotBlank() && it != "Hidden card" }
            val chain = listOfNotNull(url, CardImages.atSize(url, "normal").replace("version=art_crop", "version=normal").takeIf { it != url },
                name?.let { CardImages.nameLookupFor(it) + "&format=image&version=normal" }).distinct()
            for (link in chain) {
                val got = loadCardArtWithRetry(link) { retryContext -> CardImages.loadForCardArt(link, name, retryContext) }
                if (got != null) { if (link != url) CardBitmapCache.put(url, got); bitmap = got; ChimeraPictureLog.loaded(name); break }
            }
            if (bitmap == null) ChimeraPictureLog.failed(name, chain)
        }
    }"""),
])
patch("TableDiagnostics.kt", [
    ("""    appendLine("Connection: ${if (argentumEnabled) argentumStatus.name.lowercase() else onlineStatus}")""",
     """    appendLine("Connection: ${if (argentumEnabled) argentumStatus.name.lowercase() else onlineStatus}")
    ChimeraPictureLog.lines().forEach { appendLine(it) }"""),
])

# ------------------------------------------------------------------ 3. home screen commander picture
patch("HomeScreen.kt", [
    ("""import androidx.compose.foundation.layout.Box
""", """import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.aspectRatio
"""),
    ("""                        Box(Modifier.fillMaxWidth().height(if (short) 120.dp else if (wide) 280.dp else 230.dp).background(Color(0xFF22302C))) {
                            if (commanderArt != null) CardArt(commanderArt, Modifier.fillMaxSize(), contentScale = ContentScale.Crop, fallbackName = deck?.commander)
                            Column(Modifier.align(Alignment.BottomStart).fillMaxWidth().background(Color(0xB3101615)).padding(horizontal = 16.dp, vertical = 10.dp)) {""",
     """                        // Owner, Nov 9: Studio option Cropped / Full card / Full art; tap the picture to see the whole card.
                        val chimeraPrefs = androidx.compose.ui.platform.LocalContext.current.getSharedPreferences("arcana_options", android.content.Context.MODE_PRIVATE)
                        val chimeraMode = if (commanderArt == null) ChimeraHomeArt.CROPPED else ChimeraHomeArt.mode(chimeraPrefs)
                        var chimeraArtOpen by remember { mutableStateOf(false) }
                        if (chimeraArtOpen && commanderArt != null) ChimeraCardPop(deck?.commander.orEmpty(), ChimeraHomeArt.fullCard(commanderArt), null) { chimeraArtOpen = false }
                        if (chimeraMode != ChimeraHomeArt.CROPPED && commanderArt != null) Box(Modifier.fillMaxWidth().background(Color(0xFF22302C))
                            .clickable { chimeraArtOpen = true }.padding(vertical = 10.dp).testTag("home-commander-art"), contentAlignment = Alignment.Center) {
                            if (chimeraMode == ChimeraHomeArt.FULL_CARD) CardArt(ChimeraHomeArt.fullCard(commanderArt),
                                Modifier.height(if (short) 200.dp else if (wide) 440.dp else 340.dp).aspectRatio(0.716f).clip(RoundedCornerShape(12.dp)),
                                contentScale = ContentScale.Fit, fallbackName = deck?.commander)
                            else CardArt(commanderArt, Modifier.padding(horizontal = 10.dp).fillMaxWidth().aspectRatio(1.37f).clip(RoundedCornerShape(10.dp)),
                                contentScale = ContentScale.Fit, fallbackName = deck?.commander)
                        }
                        Box(Modifier.fillMaxWidth().then(if (chimeraMode == ChimeraHomeArt.CROPPED) Modifier.height(if (short) 120.dp else if (wide) 280.dp else 230.dp) else Modifier)
                            .background(Color(0xFF22302C)).then(if (chimeraMode == ChimeraHomeArt.CROPPED && commanderArt != null) Modifier.clickable { chimeraArtOpen = true } else Modifier)) {
                            if (commanderArt != null && chimeraMode == ChimeraHomeArt.CROPPED) CardArt(commanderArt, Modifier.fillMaxSize(), contentScale = ContentScale.Crop, fallbackName = deck?.commander)
                            Column(Modifier.align(Alignment.BottomStart).fillMaxWidth().background(Color(0xB3101615)).padding(horizontal = 16.dp, vertical = 10.dp)) {"""),
])
patch("StudioTablePanel.kt", [
    ("""                        busy=false
                    }
                }}
            }
""",
     """                        busy=false
                    }
                }}
            }
            // Chimera (owner, Nov 9): the commander picture on the home screen.
            Row(Modifier.hintedHorizontalScroll(rememberScrollState()),verticalAlignment=Alignment.CenterVertically) {
                val homePrefs=context.getSharedPreferences("arcana_options",android.content.Context.MODE_PRIVATE)
                Text("Home deck picture",color=darkTableColors.muted,fontSize=12.sp,modifier=Modifier.padding(end=6.dp))
                ChimeraHomeArt.ALL.forEach{(id,label)->StudioChip(label,true,"chimera-home-art-$id",selected=ChimeraHomeArt.mode(homePrefs)==id){ChimeraHomeArt.set(homePrefs,id)}}
            }
            // Chimera (owner, Nov 9): the Terminal preset's layouts, colours, text size, scanlines and glow.
            Row(verticalAlignment=Alignment.CenterVertically) {
                Text("Terminal preset",color=darkTableColors.muted,fontSize=12.sp,modifier=Modifier.padding(end=6.dp))
                ChimeraTerminalStudioChip()
            }
"""),
])
