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

# ================================================================== speed (owner, Nov 9 22:00: "quick and easy one-offs")
# Pass to my turn on your own turn: Forge offers only "declare attackers" there, so auto-pass declares no attack
# (or only the creatures that must attack) instead of stopping.
patch("ArgentumPassing.kt", [
    ('''        /** Stop and hand control back: something needs the player. */
        INTERRUPTED,''',
     '''        /** Stop and hand control back: something needs the player. */
        INTERRUPTED,
        /** Your own declare-attackers step: declare no attack (Forge offers no plain pass there). */
        NO_ATTACK,'''),
    ('''        "DeclareAttackers" in legalActionTypes && "PassPriority" !in legalActionTypes -> Outcome(Step.INTERRUPTED, "You can declare attackers.")''',
     '''        "DeclareAttackers" in legalActionTypes && "PassPriority" !in legalActionTypes ->
            if (activePlayer == localSeat) Outcome(Step.NO_ATTACK) else Outcome(Step.INTERRUPTED, "You can declare attackers.")'''),
])
patch("TableEffects.kt", [
    ('''        delay(300) // let a burst of updates settle before answering
        if (argentumSnapshot !== snapshot || argentumAwaitingUpdate) return@LaunchedEffect // decide from the newest state only''',
     '''        delay(150) // let a burst of updates settle before answering (was 300; owner, Nov 9: speed)
        if (argentumSnapshot !== snapshot || argentumAwaitingUpdate) return@LaunchedEffect // decide from the newest state only'''),
    ('''            ArgentumPassing.Step.PASS -> sendArgentumPriority()''',
     '''            ArgentumPassing.Step.PASS -> sendArgentumPriority()
            ArgentumPassing.Step.NO_ATTACK -> {
                val plan = snapshot.legalActions.firstNotNullOfOrNull { ArgentumAttackPlan.from(it) }
                val epoch = plan?.let { ArgentumActions.epochFor(it.legalAction, snapshot.interactionEpoch) }
                val must = plan?.options.orEmpty().filter { it.mandatory }.associate { it.attackerId to it.targets.first() }
                // Once per turn: if Forge rejects it (a creature must attack), stop and let you declare (owner, Nov 9 23:22:
                // it used to try again forever).
                val again = ChimeraPlayState.autoAttackTurn == snapshot.turnNumber
                ChimeraPlayState.autoAttackTurn = snapshot.turnNumber
                if (!again && plan != null && epoch != null && submitArgentumAction(plan.buildAction(must), epoch)) {
                    recordDiagnostic("Pass to my turn: declared ${if (must.isEmpty()) "no attack" else "${must.size} required attacker(s)"}", DiagnosticCategory.ACTION)
                } else {
                    passToMyTurnFrom = null
                    recordDiagnostic("Pass to my turn: stopped (You can declare attackers.)", DiagnosticCategory.ACTION)
                    android.widget.Toast.makeText(context, "You can declare attackers.", android.widget.Toast.LENGTH_LONG).show()
                }
            }'''),
])

# A non-debug build (still signed with the same test key): Android runs a debuggable app's code with fewer
# optimisations, and Forge's AI is the heaviest code in the app. Arcana's preview-only features followed the debug
# flag; they now follow PREVIEW, which this build keeps on.
gradle = Path(sys.argv[1]) / "build.gradle.kts"
g = gradle.read_text()
for old, new in [
    ('''        buildConfigField("boolean", "STUDIO_ENABLED", "false")''',
     '''        buildConfigField("boolean", "STUDIO_ENABLED", "false")
        buildConfigField("boolean", "PREVIEW", "false")'''),
    ('''            buildConfigField("boolean", "STUDIO_ENABLED", "true")''',
     '''            buildConfigField("boolean", "STUDIO_ENABLED", "true")
            buildConfigField("boolean", "PREVIEW", "true")
            isDebuggable = false // Chimera (owner, Nov 9): the AI runs with full optimisation'''),
]:
    if g.count(old) != 1:
        sys.exit(f"patch_arcana_nov9c: build.gradle.kts: expected 1 match, found {g.count(old)}: {old[:80]!r}")
    g = g.replace(old, new)
gradle.write_text(g)
print("patched build.gradle.kts (2 edits)")
for rel in ["TableController.kt", "ReleaseFeatures.kt"]:
    path = app / rel
    t = path.read_text()
    if "BuildConfig.DEBUG" not in t:
        sys.exit(f"patch_arcana_nov9c: {rel}: BuildConfig.DEBUG not found")
    path.write_text(t.replace("BuildConfig.DEBUG", "BuildConfig.PREVIEW"))
    print(f"patched {rel} (debug flag -> PREVIEW)")
left = [f.name for f in app.rglob("*.kt") if "BuildConfig.DEBUG" in f.read_text() and f.name != "DeckBuilderScreen.kt"]
if left:
    sys.exit("patch_arcana_nov9c: BuildConfig.DEBUG still used in " + ", ".join(left))

# ================================================================== owner, Nov 9 21:21 list (screenshots and the 21:44 game log)
# Player chips and the commander button: Forge's internal "Commander Effect" was taken for the commander once the real
# one left the command zone (the bridge no longer sends it; the commander is now found by its commander flag first).
patch("ArgentumBackend.kt", [
    ('''                val commander = command.firstOrNull() ?: (battlefield + hand + graveyard + exile).firstOrNull { it.isCommander }''',
     '''                val commander = command.firstOrNull { it.isCommander } ?: (battlefield + hand + graveyard + exile).firstOrNull { it.isCommander }
                    ?: command.firstOrNull { !it.type.contains("Emblem", ignoreCase = true) }'''),
])
# Token pictures come from a Scryfall search (a list): take the first result's picture.
patch("CardImages.kt", [
    ('''    internal fun imageFromJson(json: JSONObject, version: String): String? {
        fun from(uris: JSONObject?) = uris?.optString(version)?.takeIf { it.isNotBlank() }''',
     '''    internal fun imageFromJson(list: JSONObject, version: String): String? {
        val json = list.optJSONArray("data")?.optJSONObject(0) ?: list // a search (tokens): its first result
        fun from(uris: JSONObject?) = uris?.optString(version)?.takeIf { it.isNotBlank() }'''),
])
# The bottom bar (Pass · Hand · Table) hides while the hand drawer is open: it showed through a see-through drawer,
# giving two Pass buttons and the card caption over it. Its space stays, so the board doesn't jump.
patch("TableMainLayout.kt", [
    ('''            Row(Modifier.fillMaxWidth().padding(top = 5.dp).testTag("bottom-bar").tutorialTarget("bottom-bar"), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.Bottom) {''',
     '''            Row(Modifier.fillMaxWidth().padding(top = 5.dp).graphicsLayer { alpha = if (showHand) 0f else 1f }.testTag("bottom-bar").tutorialTarget("bottom-bar"), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.Bottom) {'''),
])

# ================================================================== presets (owner, Nov 9 21:25: the three that showcase the
# editor are Classic, Glass Cockpit and Command Center; keep all ten; the seven whole-screen layouts get their own section;
# more presets made only from editor settings)
patch("StudioPresets.kt", [
    ('''        StudioPreset("Duel Arena",''',
     '''        StudioPreset("Grimoire","A spellbook: serif type on parchment, bookmark tabs down the right edge, wine and gold",StudioLook(1.05f,1.1f,0.95f,1.05f),"Balanced",
            emptySet(),"round",null),
        StudioPreset("Arcade Cabinet","A neon cabinet: mono type on a neon grid, chunky square controls, a scoreboard, one row of cards",StudioLook(0.95f,0.9f,1.15f,1f),"Balanced",
            emptySet(),"turn",null),
        StudioPreset("War Table","An officer's console on felt: condensed type, every button in a column, a ticker of counts",StudioLook(0.9f,0.85f,0.95f,0.95f),"Detailed",
            emptySet(),"both",null),
        StudioPreset("Duelist's Gauntlet","One-handed on the folded phone: a fan of cards over dark wood, Pass under your thumb",StudioLook(1f,1f,1.05f,1.1f),"Balanced",
            emptySet(),"turn",null),
        StudioPreset("Zen Garden","Calm: serif type at dusk, soft pills, one row of cards, room to breathe, fewer labels",StudioLook(1f,1.25f,0.95f,1f),"Balanced",
            setOf("fullTableLabel","engineThinking","timer"),"hidden",null),
        StudioPreset("Big & Bold","Easy to see: black and yellow, oversized buttons and text, big cards",StudioLook(1.2f,1.1f,1.25f,1.2f),"Balanced",
            emptySet(),"both",null),
        StudioPreset("Battlefield First","The board gets the room: a short drawer of slim square strips, small cards in one row",StudioLook(0.85f,0.9f,0.85f,0.8f),"Detailed",
            setOf("fullTableLabel"),"round",null),
        StudioPreset("Duel Arena",'''),
])
patch("StudioEditorPanels.kt", [
    ('''            StudioSection("Presets")
            Row(Modifier.hintedHorizontalScroll()) {
                StudioPresets.all.forEach {preset->
                    StudioChip(preset.name,true,"studio-preset-${preset.name}",selected=tryingPreset==preset.name){
                        if(trying==null)trying=runtime.draft to look.read()''',
     '''            StudioSection("Presets")
            Text("Made only with Studio's own settings: everything in them can be changed here.",color=darkTableColors.muted,fontSize=11.sp)
            val chimeraTry:(StudioPreset)->Unit={preset->
                        if(trying==null)trying=runtime.draft to look.read()'''),
    ('''                        StudioLog.add("Preset tried: ${preset.name}${if(keepColours)" (kept my colours)" else ""}")
                    }
                }
            }''',
     '''                        StudioLog.add("Preset tried: ${preset.name}${if(keepColours)" (kept my colours)" else ""}")
            }
            Row(Modifier.hintedHorizontalScroll()) {
                StudioPresets.all.filter{!ChimeraPresets.isLayout(it.name)}.forEach{preset->
                    StudioChip(preset.name,true,"studio-preset-${preset.name}",selected=tryingPreset==preset.name){chimeraTry(preset)}
                }
            }
            StudioSection("Screen layouts")
            Text("Whole screens built into Chimera. Studio edits the classic screen; each layout's menu has \\"Classic screen\\".",color=darkTableColors.muted,fontSize=11.sp)
            Row(Modifier.hintedHorizontalScroll()) {
                StudioPresets.all.filter{ChimeraPresets.isLayout(it.name)}.forEach{preset->
                    StudioChip(preset.name,true,"studio-preset-${preset.name}",selected=tryingPreset==preset.name){chimeraTry(preset)}
                }
            }'''),
])
# The typeface the presets use, as a Studio setting of its own (so presets are entirely editor settings).
patch("StudioTablePanel.kt", [
    ('''            // Chimera (owner, Nov 9): the Terminal preset's layouts, colours, text size, scanlines and glow.''',
     '''            // Chimera (owner, Nov 9): the typeface of Arcana's screen.
            Row(Modifier.hintedHorizontalScroll(rememberScrollState()),verticalAlignment=Alignment.CenterVertically) {
                Text("Typeface",color=darkTableColors.muted,fontSize=12.sp,modifier=Modifier.padding(end=6.dp))
                listOf("" to "Arcana","serif" to "Serif","mono" to "Mono","condensed" to "Condensed").forEach{(id,label)->
                    StudioChip(label,true,"chimera-font-${id.ifEmpty{"arcana"}}",selected=ChimeraSkins.classicFontName==id){ChimeraSkins.setClassicFont(id)}}
            }
            // Chimera (owner, Nov 9): the Terminal preset's layouts, colours, text size, scanlines and glow.'''),
])

# ================================================================== the hand editor shows the design at its in-play size
# (owner, Nov 9 21:17: "The hand drawer drastically changes size when going into the studio"). The editor used to zoom
# the design to the full width (twice its size on the open Fold); it now uses the game's size and spread, and edits are
# converted back to design units. The side column the game uses on short, wide screens is a Studio switch.
patch("StudioHandCanvas.kt", [
    ('''        val scale = if (editing) chimeraRaw else ChimeraAdapt.scale(chimeraRaw)
        val chimeraSx = chimeraRaw / scale''',
     '''        val scale = ChimeraAdapt.scale(chimeraRaw) // owner, Nov 9 21:17: the editor shows the design at its in-play size
        val chimeraSx = if (editing) 1f else chimeraRaw / scale'''),
    ('''            .let { if (fitHeight != androidx.compose.ui.unit.Dp.Unspecified && fitHeight.value > 0f)
                ChimeraAdapt.columnize(''',
     '''            .let { if (ChimeraAdapt.sideColumn && fitHeight != androidx.compose.ui.unit.Dp.Unspecified && fitHeight.value > 0f)
                ChimeraAdapt.columnize('''),
])
patch("StudioHandEditor.kt", [
    ('''                val unit=constraints.maxWidth/layout.canvasWidth.coerceIn(240f,2000f)''',
     '''                val chimeraRaw=maxWidth.value/layout.canvasWidth.coerceIn(240f,2000f)
                val unit=constraints.maxWidth/layout.canvasWidth.coerceIn(240f,2000f)*(ChimeraAdapt.scale(chimeraRaw)/chimeraRaw) // matches the canvas (in-play size)'''),
    ('''        Switch(chimeraSeparate,{v->runtime.prefs.edit().putBoolean(ChimeraOrientation.SEPARATE,v).apply();chimeraSeparate=v;onApplied()},
            modifier=Modifier.testTag("chimera-separate-landscape"))
    }
''',
     '''        Switch(chimeraSeparate,{v->runtime.prefs.edit().putBoolean(ChimeraOrientation.SEPARATE,v).apply();chimeraSeparate=v;onApplied()},
            modifier=Modifier.testTag("chimera-separate-landscape"))
    }
    Row(Modifier.fillMaxWidth().heightIn(min=48.dp),verticalAlignment=Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text("Buttons beside the cards on wide screens",color=darkTableColors.text)
            Text("In play on a short, wide drawer (open Fold, landscape) the button rows move into a column so the cards keep their height. The editor shows the design as you laid it out.",
                color=darkTableColors.muted,fontSize=11.sp)
        }
        Switch(ChimeraAdapt.sideColumn,{ChimeraAdapt.changeSideColumn(it)},modifier=Modifier.testTag("chimera-side-column"))
    }
'''),
])

# Players in a choice are not cards: no picture lookup by their name (owner, Nov 9 22:35 log: "You", "AI · Animar").
patch("ArgentumCardPick.kt", [
    ('''    val image = imageUrl ?: str("imageUri") ?: artByName(shownName, isToken)''',
     '''    val image = imageUrl ?: str("imageUri") ?: if (isPlayer) null else artByName(shownName, isToken)'''),
])

# Swipe down to close, anywhere in the drawer (owner, Nov 9 23:00: "I want the swipe down to close to work more
# consistently"): a downward flick that the drawer's own scrolling doesn't use (it is at its top) closes it.
patch("TableOverlays.kt", [
    ("import androidx.compose.ui.platform.LocalDensity\n", "import androidx.compose.ui.platform.LocalDensity\nimport androidx.compose.ui.input.nestedscroll.nestedScroll\n"),
    ('''                        .fillMaxHeight(effectiveHandFraction)
                        .verticalScroll(rememberScrollState())''',
     '''                        .fillMaxHeight(effectiveHandFraction)
                        .nestedScroll(run {
                            val closeSpeed = with(LocalDensity.current) { 1100.dp.toPx() }
                            remember(closeSpeed) { object : androidx.compose.ui.input.nestedscroll.NestedScrollConnection {
                                override suspend fun onPostFling(consumed: androidx.compose.ui.unit.Velocity, available: androidx.compose.ui.unit.Velocity): androidx.compose.ui.unit.Velocity {
                                    if (available.y > closeSpeed) showHand = false
                                    return androidx.compose.ui.unit.Velocity.Zero
                                }
                            } }
                        })
                        .verticalScroll(rememberScrollState())'''),
])

# Studio's "Start practice game" (owner, Nov 9 23:28: "It opens a game but it's hiding behind the screen"): the game
# started under the open Studio editor, so its opening hand couldn't be answered. Studio now steps aside (your draft is
# kept), the game starts, and Studio reopens once the opening hand is kept.
patch("MainActivity.kt", [
    ('''                val studioPractice:(()->Unit)?=if(gameInProgress) null else ({studioCloseAll();startOfflineGame()})''',
     '''                val studioPractice:(()->Unit)?=if(gameInProgress) null else ({studioCloseAll();ChimeraPlayState.studioAfterPractice=true;studio.cancel();startOfflineGame()})
                LaunchedEffect(ChimeraPlayState.studioAfterPractice,gameInProgress,argentumSnapshot,argentumMulliganPrompt,argentumPendingDecision) {
                    if(ChimeraPlayState.studioAfterPractice && gameInProgress && argentumSnapshot!=null && !argentumMulliganPrompt && argentumPendingDecision==null) {
                        ChimeraPlayState.studioAfterPractice=false
                        studio.begin()
                    }
                }'''),
])

# More memory for the app (owner, Nov 10): memory clean-up took about a third of the AI's thinking time at 512 MB.
manifest = Path(sys.argv[1]) / "src/main/AndroidManifest.xml"
m = manifest.read_text()
old = '''    <application
        android:allowBackup="true"'''
if m.count(old) != 1:
    sys.exit(f"patch_arcana_nov9c: AndroidManifest.xml: expected 1 match, found {m.count(old)}")
manifest.write_text(m.replace(old, '''    <application
        android:largeHeap="true"
        android:allowBackup="true"'''))
print("patched AndroidManifest.xml (large heap)")

# The six debug test scenarios ("TESTS (6)" on the home screen) are removed from the dev app (owner, Nov 10 08:51):
# the debug build uses the release build's empty list, so the button and the "ARCANA QA" title go too.
debug_scenarios = Path(sys.argv[1]) / "src/debug/java/com/arcanatable/TestScenarios.kt"
release_scenarios = Path(sys.argv[1]) / "src/release/java/com/arcanatable/TestScenarios.kt"
if not debug_scenarios.exists() or not release_scenarios.exists():
    sys.exit("patch_arcana_nov9c: TestScenarios.kt not found in the debug or release source set")
debug_scenarios.write_text(release_scenarios.read_text())
print("replaced debug TestScenarios.kt with the release (empty) one")

# ================================================================== tutorial (owner, Nov 10: "update the tutorial to work with
# the current version as-is"): the practice boards come from ChimeraTutorialBoards (Forge game-state scripts).
patch("TutorialPracticeScenarios.kt", [
    ('''    fun build(id: String, library: OfflineCardLibrary): TestScenarioGame = when (id) {''',
     '''    fun build(id: String, library: OfflineCardLibrary): TestScenarioGame = ChimeraTutorialBoards.build(id, library) ?: when (id) {'''),
])

# Menu (owner, Nov 10: "Yes, remove them"): Import / Replace deck, Edit imported deck, Add AI opponent, Add token…, Mana
# pool and Search / browse library leave the game menu (decks are chosen and imported on Home; Forge runs the board).
_menu = app / "TableMainLayout.kt"
_lines = _menu.read_text().split("\n")
for n in (19, 20, 22, 23, 24, 25):
    hits = [i for i, l in enumerate(_lines) if f'"TableMainLayout.Text.{n}"' in l]
    if len(hits) != 1 or not _lines[hits[0]].lstrip().startswith("if ("):
        sys.exit(f"patch_arcana_nov9c: TableMainLayout.kt: menu item Text.{n} not found as expected")
    _lines[hits[0]] = _lines[hits[0]].replace("if (", "if (false && ", 1)
_menu.write_text("\n".join(_lines))
print("patched TableMainLayout.kt (6 menu items removed)")

# ================================================================== menus and options audit (owner, Nov 10 09:05: "Remove and merge")
def _hide_lines(rel, markers):
    """Puts `if (false)` in front of the composable on the one line holding each marker (Kotlin: `if (a) if (false) X(...)`)."""
    path = app / rel
    lines = path.read_text().split("\n")
    for marker, call in markers:
        hits = [i for i, l in enumerate(lines) if marker in l]
        if len(hits) != 1 or call not in lines[hits[0]]:
            sys.exit(f"patch_arcana_nov9c: {rel}: {marker!r} not found once with {call!r}")
        lines[hits[0]] = lines[hits[0]].replace(call, "if (false) " + call, 1)
    path.write_text("\n".join(lines))
    print(f"patched {rel} ({len(markers)} items hidden)")

# Game menu: Learn & tutorials and What's new (tutorials stay in Settings, reachable from Home too), Save full offline
# game (Forge games can't be saved yet), and the two greyed-out items that never worked (Start Forge game, Online room).
_hide_lines("TableMainLayout.kt", [
    ('"TableMainLayout.Text.11"', "StudioDropdownMenuItem("),
    ('"TableMainLayout.Text.14"', "StudioDropdownMenuItem("),
    ('"TableMainLayout.Text.32"', "StudioDropdownMenuItem("),
    ('"TableMainLayout.Text.34"', "StudioDropdownMenuItem("),
    ('"TableMainLayout.Text.35"', "StudioDropdownMenuItem("),
])
# The turn label's session/turn timer menu and its hold-to-hide: whether the turn time shows is a Studio piece ("timer").
patch("TableMainLayout.kt", [
    ('''enabled = !onlineMode || localSeat == 0, onClick = { run { timerMenuOpen = true } }, onLongClick = { turnTimerVisible = !turnTimerVisible }''',
     '''enabled = false, onClick = { }, onLongClick = { }'''),
])
# Settings: card size, the Arcana Studio button (Studio is on Home and in the menu), Show turn timer (a Studio piece),
# What's new (Arcana's own release notes), and the engine self-test (a check with Arcana's old Portal set).
_hide_lines("TableDialogs.kt", [
    ('StudioText("TableDialogs.Text.124"', 'StudioText("TableDialogs.Text.124"'),
    ('SettingsToggle("Show turn timer"', 'SettingsToggle("Show turn timer"'),
    ('LinkRow("What\'s new"', 'LinkRow("What\'s new"'),
])
patch("TableDialogs.kt", [
    ('''                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    listOf(0.82f to "SMALL", 1f to "STANDARD", 1.18f to "LARGE").forEach { (scale, label) ->''',
     '''                if (false) Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    listOf(0.82f to "SMALL", 1f to "STANDARD", 1.18f to "LARGE").forEach { (scale, label) ->'''),
    ('''                if (onOpenStudio != null) {''', '''                if (false && onOpenStudio != null) {'''),
    ('''                } else StudioTextButton("TableDialogs.TextButton.27"''', '''                } else if (false) StudioTextButton("TableDialogs.TextButton.27"'''),
    ('''                if (onEngineSelfTest != null) LinkRow(''', '''                if (false && onEngineSelfTest != null) LinkRow('''),
])
# Home: the saved offline game card (Forge games can't be saved yet; "the game may still be running" stays).
patch("HomeScreen.kt", [
    ('''            if (offlineResumeAvailable && !argentumActive) Column(''', '''            if (false && offlineResumeAvailable && !argentumActive) Column('''),
])
patch("TableDialogs.kt", [
    ('''"20 chapters, 160 lessons and a Magic glossary · works offline"''', '''"20 chapters and a Magic glossary · works offline"'''),
])

# ================================================================== vibration (owner, Nov 10: "it doesn't seem like anything does")
patch("TableEffects.kt", [
    ('''        if (isYou && buzzOnTurn && appInForeground) haptics.performHapticFeedback(androidx.compose.ui.hapticfeedback.HapticFeedbackType.LongPress)''',
     '''        if (isYou) ChimeraHaptics.turnStart() // the phone's vibrator, not the touch haptic the phone can silence'''),
])
patch("TableOverlays.kt", [
    ('''    ChimeraConcedeDialog { recordDiagnostic("Player conceded", DiagnosticCategory.ACTION); if (argentumEnabled) argentumSocket.concede() else concede(localSeat) }''',
     '''    ChimeraConcedeDialog { recordDiagnostic("Player conceded", DiagnosticCategory.ACTION); if (argentumEnabled) argentumSocket.concede() else concede(localSeat) }
    ChimeraHapticWatch(argentumPendingDecision?.optString("decisionId")?.ifBlank { null } ?: argentumPendingDecision?.toString()?.take(200),
        argentumBlockPlan != null, argentumMulliganPrompt, passToMyTurnFrom != null, cardZoom.card != null,
        argentumSnapshot?.let { it.activePlayer == localSeat } ?: true)'''),
])
patch("TableDialogs.kt", [
    ('''                SettingsToggle("Skip AI actions unless needed", "Offline games: pass over AI spells and abilities unless they affect you. Prompts and blocks still stop the game.", quietOpponentTurns, onQuietOpponentTurns, Modifier)''',
     '''                SettingsToggle("Skip AI actions unless needed", "Offline games: pass over AI spells and abilities unless they affect you. Prompts and blocks still stop the game.", quietOpponentTurns, onQuietOpponentTurns, Modifier)
                ChimeraVibrationSetting()'''),
])

# The commander chip in the zone strip (owner, Nov 10: "covered by its power/toughness"): the picture alone, without the
# battlefield badges (power/toughness, NEW) that covered it at this size.
patch("TableBoardViews.kt", [
    ('''                            CardFace(commander, Modifier.width(29.dp), onClick = { if (castable) onCastCommander() else onPublicPreview(commander) }, onLongClick = { onPublicPreview(commander) }, showCounters = false)''',
     '''                            CardArt(commanderArtOnly(commander.imageUrl) ?: commander.imageUrl.orEmpty(), Modifier.width(34.dp).height(40.dp).clip(RoundedCornerShape(topStart = 9.dp, bottomStart = 9.dp)),
                                contentScale = ContentScale.Crop, fallbackName = commander.name)'''),
])

# "YES · ALWAYS THIS TURN" only for real yes/no questions, not two-way choices like Keep on top / Graveyard (owner, Nov 10).
_hide_lines("TableOverlays.kt", [('StudioTextButton("TableOverlays.TextButton.14"', 'StudioTextButton("TableOverlays.TextButton.14"')])
patch("TableOverlays.kt", [
    ('''if (false) StudioTextButton("TableOverlays.TextButton.14"''',
     '''if (decision.decisionDisplayText("yesText", "Yes").equals("Yes", ignoreCase = true)) StudioTextButton("TableOverlays.TextButton.14"'''),
])
