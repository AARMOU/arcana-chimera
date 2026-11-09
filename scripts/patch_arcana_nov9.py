#!/usr/bin/env python3
"""Build-time patches for the owner's Nov 9 list (run after patch_arcana_for_forge.py; never committed to arcana_table).

1  Game log: medium detail in the list, tap an entry for Forge's full detail.
2  Studio option: hand drawer buttons in a side column (wide screens).
3  One compact strip at the top of the drawer (grab bar, status, close, menu).
4  The drawer's status line, close button and menu are editable in Studio hand designs.
5  Open Fold: no view swap when the active player is already on screen.
6  The table background is shared by portrait and landscape.
7  Stack -> Commander button (glows when castable) and a commander window with Cast.
8-12  Fan and Catalogue hand arrangements, Pill and Round button looks, built-in backgrounds, ten presets.

Each replacement is anchored and must match exactly once, so a changed Arcana source fails the build loudly.
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
            sys.exit(f"patch_arcana_nov9: {rel}: expected 1 match, found {n}: {old[:90]!r}")
        text = text.replace(old, new)
    path.write_text(text)
    print(f"patched {rel} ({len(pairs)} edits)")


# ------------------------------------------------------------------ 1. game log: tap an entry for full detail
patch("ArgentumBackend.kt", [
    ("data class ArgentumLogLine(val description: String, val type: String, val isYours: Boolean?, val groupingKey: String? = null) {",
     "data class ArgentumLogLine(val description: String, val type: String, val isYours: Boolean?, val groupingKey: String? = null,\n"
     "    /** Chimera: Forge's full-detail lines behind this entry (shown when the entry is tapped). */\n"
     "    val details: List<String> = emptyList()) {"),
    ('private val DELIVERY_FIELDS = setOf("eventId", "eventIndex", "sequence", "timestamp", "timestampMs")',
     'private val DELIVERY_FIELDS = setOf("eventId", "eventIndex", "sequence", "timestamp", "timestampMs", "details")'),
    ("                ArgentumLogLine(text, type, yours, semanticEventKey(e))",
     "                ArgentumLogLine(text, type, yours, semanticEventKey(e),\n"
     "                    e.optJSONArray(\"details\")?.let { d -> (0 until d.length()).map { d.optString(it) }.filter { it.isNotBlank() } }.orEmpty())"),
])
patch("TableOverlaysB.kt", [
    ("        groupingKeys = if (argentumEnabled && argentumSnapshot != null) argentumSnapshot!!.fullLog.map { it.groupingKey } else null,\n",
     "        groupingKeys = if (argentumEnabled && argentumSnapshot != null) argentumSnapshot!!.fullLog.map { it.groupingKey } else null,\n"
     "        details = if (argentumEnabled && argentumSnapshot != null) argentumSnapshot!!.fullLog.map { it.details } else null,\n"),
])
patch("TableDialogs.kt", [
    ("onDismiss: () -> Unit, groupingKeys: List<String?>? = null) {\n    val rows = remember(entries, ownership, turnMarkers, groupingKeys) {",
     "onDismiss: () -> Unit, groupingKeys: List<String?>? = null, details: List<List<String>>? = null) {\n"
     "    // Chimera (owner, Nov 9): medium detail in the list; tap an entry for everything Forge logged behind it.\n"
     "    var openDetails by remember { mutableStateOf<Set<Int>>(emptySet()) }\n"
     "    val rows = remember(entries, ownership, turnMarkers, groupingKeys) {"),
    ("""                                    .studioClickable("TableDialogs.clickable.2", "Dialogs and overlays", enabled = row.count > 1) {
                                        expandedCounts = if (row.firstIndex in expandedCounts) expandedCounts - row.firstIndex
                                            else expandedCounts + (row.firstIndex to minOf(25, row.count))
                                    }""",
     """                                    .studioClickable("TableDialogs.clickable.2", "Dialogs and overlays", enabled = row.count > 1 || details?.getOrNull(row.firstIndex).orEmpty().isNotEmpty()) {
                                        if (row.count > 1) expandedCounts = if (row.firstIndex in expandedCounts) expandedCounts - row.firstIndex
                                            else expandedCounts + (row.firstIndex to minOf(25, row.count))
                                        else openDetails = if (row.firstIndex in openDetails) openDetails - row.firstIndex else openDetails + row.firstIndex
                                    }"""),
    ("""                                if (row.count > 1) StudioText("TableDialogs.Text.321", "Dialogs and overlays", true, "×${row.count} ${if (row.firstIndex in expandedCounts) "▲" else "▼"}",
                                    color = CounterGold, fontWeight = FontWeight.Black, fontSize = 11.sp, modifier = Modifier.padding(horizontal = 4.dp))""",
     """                                if (row.count > 1) StudioText("TableDialogs.Text.321", "Dialogs and overlays", true, "×${row.count} ${if (row.firstIndex in expandedCounts) "▲" else "▼"}",
                                    color = CounterGold, fontWeight = FontWeight.Black, fontSize = 11.sp, modifier = Modifier.padding(horizontal = 4.dp))
                                else if (details?.getOrNull(row.firstIndex).orEmpty().isNotEmpty()) Text(if (row.firstIndex in openDetails) "▲" else "▼",
                                    color = Muted, fontSize = 10.sp, modifier = Modifier.padding(horizontal = 4.dp))"""),
    ("""                            val shown = expandedCounts[row.firstIndex] ?: 0
                            if (row.count > 1 && shown > 0) {""",
     """                            if (row.count == 1 && row.firstIndex in openDetails) details?.getOrNull(row.firstIndex)?.forEach { line ->
                                ManaSymbolText(line, color = Muted, fontSize = 11.sp, modifier = Modifier.fillMaxWidth().padding(start = 14.dp, top = 1.dp, bottom = 1.dp))
                            }
                            val shown = expandedCounts[row.firstIndex] ?: 0
                            if (row.count > 1 && shown > 0) {"""),
    # Grouped rows: each original entry in the expanded list opens its own detail.
    ("""                                    StudioText("TableDialogs.Text.322", "Dialogs and overlays", true, "#${original + 1} · ${entries[original]}", color = Muted, fontSize = 11.sp,
                                        modifier = Modifier.fillMaxWidth().padding(start = 14.dp, top = 2.dp, bottom = 2.dp))""",
     """                                    val more = details?.getOrNull(original).orEmpty()
                                    StudioText("TableDialogs.Text.322", "Dialogs and overlays", true, "#${original + 1} · ${entries[original]}${if (more.isEmpty()) "" else if (original in openDetails) "  ▲" else "  ▼"}", color = Muted, fontSize = 11.sp,
                                        modifier = Modifier.fillMaxWidth().clickable(enabled = more.isNotEmpty()) { openDetails = if (original in openDetails) openDetails - original else openDetails + original }
                                            .padding(start = 14.dp, top = 2.dp, bottom = 2.dp))
                                    if (original in openDetails) more.forEach { line ->
                                        ManaSymbolText(line, color = Muted.copy(alpha = 0.8f), fontSize = 10.sp, modifier = Modifier.fillMaxWidth().padding(start = 26.dp, top = 1.dp, bottom = 1.dp))
                                    }"""),
])

# ------------------------------------------------------------------ 5. open Fold: no swap when the active player is on screen
patch("TableMainLayout.kt", [
    ("        val sideBySideBoards = compactArgentumTable && !isLandscape && screenWidthDp >= 600 && configuration.screenHeightDp >= 600 && players.size >= 2 && localSeat in players.indices\n",
     "        val sideBySideBoards = compactArgentumTable && !isLandscape && screenWidthDp >= 600 && configuration.screenHeightDp >= 600 && players.size >= 2 && localSeat in players.indices\n"
     "        ChimeraView.sideBySide = sideBySideBoards\n"),
])
patch("TableEffects.kt", [
    ("""        if (!shouldFollowActivePlayerToBoard(followActivePlayer, showOverview, active, players.size)) return@LaunchedEffect
        selectedPlayer = active""",
     """        if (!shouldFollowActivePlayerToBoard(followActivePlayer, showOverview, active, players.size)) return@LaunchedEffect
        // Chimera (owner, Nov 9): with your board and an opponent's side by side (open Fold), a turn passing between
        // the two boards on screen moves nothing; only a player who isn't showing brings their board in.
        if (ChimeraView.sideBySide && localSeat in players.indices) {
            val left = if (selectedPlayer != localSeat) selectedPlayer
                else lastOpponentViewed.takeIf { it in players.indices && it != localSeat } ?: players.indices.firstOrNull { it != localSeat }
            if (active == localSeat || active == left) return@LaunchedEffect
        }
        selectedPlayer = active"""),
])

# ------------------------------------------------------------------ 6. one table background for portrait and landscape
patch("StudioControls.kt", [
    ("""    fun draft()=prefs.getString("${key}_draft",null)?.let{raw->runCatching{StudioControlProject.parse(raw)}.getOrNull()?.let{keepOriginal("${key}_draft",raw,it)}}""",
     """    fun draft()=prefs.getString("${key}_draft",null)?.let{raw->runCatching{StudioControlProject.parse(raw)}.getOrNull()?.let{keepOriginal("${key}_draft",raw,it)}}
        // Chimera: an untouched draft follows the shared background; one you're changing keeps your change.
        ?.let{d->val own=loadOwn(); if(d.backdrop==own.backdrop && d.backdropFit==own.backdropFit && d.backdropDim==own.backdropDim) ChimeraBackdropShare.apply(prefs,d) else d}"""),
    ("""    fun load():StudioControlProject=listOf(key,"${key}_backup")""",
     """    // Chimera (owner, Nov 9: "I have to change it twice"): the table background is shared by portrait and landscape.
    fun load():StudioControlProject=ChimeraBackdropShare.apply(prefs,loadOwn())
    private fun loadOwn():StudioControlProject=listOf(key,"${key}_backup")"""),
    ("""        StudioControlProject.parse(project.json())
        return edit.putString("${key}_backup",load().json())""",
     """        StudioControlProject.parse(project.json())
        ChimeraBackdropShare.stage(edit,project)
        return edit.putString("${key}_backup",load().json())"""),
    ("""    fun reset(){prefs.edit().remove(key).remove("${key}_backup").remove("${key}_draft").commit()}""",
     """    fun reset(){prefs.edit().remove(key).remove("${key}_backup").remove("${key}_draft").remove(ChimeraBackdropShare.KEY).commit()}"""),
])

# ------------------------------------------------------------------ hand designs: new pieces, card styles and shapes
patch("StudioLayout.kt", [
    ("""    /** Owner, Nov 7: opacity, 0–1. Pass never draws below 0.5. */
    val alpha: Float = 1f,
)""",
     """    /** Owner, Nov 7: opacity, 0–1. Pass never draws below 0.5. */
    val alpha: Float = 1f,
    /** Chimera (owner, Nov 9): "" rounded by [r], "square", "pill" or "round". */
    val shape: String = "",
)"""),
    ("""    val canvasWidth: Float = 412f, val canvasHeight: Float = 928f, val originY: Float = 344f) {""",
     """    val canvasWidth: Float = 412f, val canvasHeight: Float = 928f, val originY: Float = 344f,
    /** Chimera: design upgrades applied ([ChimeraHandDesign.upgrade]) and how the cards sit (grid, row, fan, catalogue). */
    val chimera: Int = 0, val cards: String = "grid") {"""),
    ("""                    if (e.alpha < 1f) put("alpha", e.alpha.toDouble())
                }) }
        })).toString()""",
     """                    if (e.alpha < 1f) put("alpha", e.alpha.toDouble())
                    if (e.shape.isNotEmpty()) put("shape", e.shape)
                }) }
        })).put("chimera", chimera).put("cards", cards).toString()"""),
    ("""            if (e.kind !in setOf("button", "metric", "card")) add("Unsupported element kind: ${e.kind}")""",
     """            if (e.kind !in setOf("button", "metric", "card") + ChimeraHandDesign.KINDS) add("Unsupported element kind: ${e.kind}")"""),
    ("""            } else if (e.binding !in bindings) add("${e.id}: unknown action binding.")""",
     """            } else if (e.binding !in bindings && e.binding !in ChimeraHandDesign.EXTRA_BINDINGS) add("${e.id}: unknown action binding.")"""),
    ("""            if (e.kind != "card" && (e.w < 44 || e.h < 44)) put(e.id, "Hard to tap: smaller than 44.")""",
     """            if (e.kind != "card" && e.kind != "status" && (e.w < 44 || e.h < 44)) put(e.id, "Hard to tap: smaller than 44.")"""),
    ("""                    e.optDouble("alpha",1.0).toFloat())
            }, obj.optInt("snap",8),""",
     """                    e.optDouble("alpha",1.0).toFloat(), e.optString("shape").takeIf { it in setOf("square","pill","round") }.orEmpty())
            }, obj.optInt("snap",8),"""),
    ("""                canvas?.getDouble("height")?.toFloat() ?: 928f,canvas?.getDouble("originY")?.toFloat() ?: 344f
            ).also { require(it.problems().isEmpty()) { it.problems().joinToString("\\n") } }""",
     """                canvas?.getDouble("height")?.toFloat() ?: 928f,canvas?.getDouble("originY")?.toFloat() ?: 344f,
                obj.optInt("chimera",0), obj.optString("cards","grid").takeIf { c -> ChimeraHandDesign.CARD_STYLES.any { it.first == c } } ?: "grid"
            ).let(ChimeraHandDesign::upgrade).also { require(it.problems().isEmpty()) { it.problems().joinToString("\\n") } }"""),
    ("""        fun responsive(width: Float,height: Float,density: String="Balanced"): StudioLayout {""",
     """        fun responsive(width: Float,height: Float,density: String="Balanced"): StudioLayout =
            ChimeraHandDesign.upgrade(responsiveBase(width,height,density))
        private fun responsiveBase(width: Float,height: Float,density: String="Balanced"): StudioLayout {"""),
    ("""        fun reference() = StudioLayout(listOf(""",
     """        fun reference() = ChimeraHandDesign.upgrade(referenceBase())
        private fun referenceBase() = StudioLayout(listOf("""),
])

patch("StudioHandCanvas.kt", [
    ("""    val cards: List<GameCard>, val playable: Set<Int> = emptySet(), val selected: Int? = null,
)""",
     """    val cards: List<GameCard>, val playable: Set<Int> = emptySet(), val selected: Int? = null,
    /** Chimera: bindings drawn with a pulsing edge (Commander when castable), and the newly drawn card. */
    val glow: Set<String> = emptySet(), val highlighted: Int? = null,
)"""),
    ("""        "metric0","metric1","metric2","metric3" -> element.label""",
     """        "status" -> live
        "metric0","metric1","metric2","metric3","commander" -> element.label"""),
    ("""        val fitted = if (!editing && fitHeight != androidx.compose.ui.unit.Dp.Unspecified && fitHeight.value > 0f)
            ChimeraHandFit.fit(renderElements, state.cards.size, fitHeight.value / scale, origin) else renderElements""",
     """        val fitted = (if (!editing && fitHeight != androidx.compose.ui.unit.Dp.Unspecified && fitHeight.value > 0f)
            ChimeraHandFit.fit(renderElements, state.cards.size, fitHeight.value / scale, origin, layout.cards) else renderElements)
            .map { if (it.shape == "round") { val (rw, rh) = ChimeraHandDesign.roundSize(it, if (editing) it.label else studioLiveLabel(it, state.labels))
                it.copy(x = it.x + (it.w - rw) / 2f, y = it.y + (it.h - rh) / 2f, w = rw, h = rh) } else it }"""),
    ("""                val shape = RoundedCornerShape((e.r*scale).dp)""",
     """                val shape = ChimeraHandDesign.shape(e, scale)"""),
    ("""                val grow = !editing && e.kind != "card\"""",
     """                val grow = !editing && e.kind != "card" && e.kind != "status" && e.kind != ChimeraHandFit.REGION"""),
    ("""                    if (e.kind=="card" && card!=null) CardFace(card,Modifier.fillMaxSize()""",
     """                    if (e.kind == ChimeraHandFit.REGION) ChimeraHandCards(layout.cards, state.cards, state.playable, state.selected, state.highlighted,
                        onCard, onPreview, Modifier.fillMaxSize())
                    else if (e.kind == "status") Box(Modifier.fillMaxSize().testTag("studio-element-${e.id}")
                        .then(if (editing) Modifier.clickable { onSelectElement(e.id) } else ChimeraDrawerTop.drag)
                        .then(e.color?.let(::parseTableColorHex)?.let { Modifier.background(it, shape) } ?: Modifier)
                        .padding(horizontal = 2.dp), contentAlignment = Alignment.CenterStart) {
                        Text(if (editing) e.label else studioLiveLabel(e, state.labels), fontSize = (e.fs*scale).sp, maxLines = 1,
                            overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis, fontWeight = androidx.compose.ui.text.font.FontWeight.Bold,
                            color = e.textColor?.let(::parseTableColorHex) ?: SoftWhite)
                    }
                    else if (e.kind=="card" && card!=null) CardFace(card,Modifier.fillMaxSize()"""),
    ("""                        val color = e.color?.let(::parseTableColorHex) ?: if (e.strong) Mint else PanelLight
                        Box(Modifier.fillMaxSize().testTag("studio-element-${e.id}").clip(shape).background(color,shape)""",
     """                        val color = e.color?.let(::parseTableColorHex) ?: if (e.kind == "icon") androidx.compose.ui.graphics.Color.Transparent else if (e.strong) Mint else PanelLight
                        Box(Modifier.fillMaxSize().chimeraGlow(!editing && e.binding in state.glow, shape).testTag("studio-element-${e.id}").clip(shape).background(color,shape)"""),
])

# ------------------------------------------------------------------ the hand drawer (items 3, 4, 7, 9, 10)
_tbv = (app / "TableBoardViews.kt").read_text()
_a = _tbv.index("    if (studioLayout != null && argentumMode && studioGeometryProblems.isEmpty()) {")
_b = _tbv.index("        return\n    }\n", _a) + len("        return\n    }\n")
_old_design = _tbv[_a:_b]
_canvas = _old_design[_old_design.index("            StudioHandCanvas(studioLayout, StudioHandState("):_old_design.index("        }\n        return\n    }\n")]
_canvas = (_canvas
    .replace("StudioHandCanvas(studioLayout, StudioHandState(", "StudioHandCanvas(ChimeraHandDesign.upgrade(studioLayout), StudioHandState(")
    .replace('"metric2" to "GRAVEYARD\\n$graveyardCount", "metric3" to "EXILE\\n$exileCount"),',
             '"metric2" to "GRAVEYARD\\n$graveyardCount", "metric3" to "EXILE\\n$exileCount",\n'
             '                    "commander" to chimeraCommanderLabel(chimeraCommanders, chimeraCanCast), "status" to chimeraStatus, "close" to "✕", "menu" to "⋮"),')
    .replace('addAll(listOf("metric0","metric1","metric2","metric3"))', 'addAll(listOf("metric0","metric1","metric2","metric3","commander","close","menu"))')
    .replace("}, cards = cards, playable = playableIds, selected = selectedId),",
             "}, cards = cards, playable = playableIds, selected = selectedId,\n"
             "                glow = if (chimeraCanCast) setOf(\"commander\") else emptySet(), highlighted = highlightedCardId),")
    .replace('                    "metric0" -> onStack()\n',
             '                    "metric0" -> onStack()\n'
             '                    "commander" -> chimeraCommanderOpen = true\n'
             '                    "close" -> onClose()\n'
             '                    "menu" -> moreOpen = true\n')
    .replace("fitHeight = availableHeight - 84.dp)", "fitHeight = availableHeight - chimeraNavInset - 8.dp)"))
for must in ["chimeraCommanderLabel", "chimeraCanCast) setOf", '"commander" -> chimeraCommanderOpen', "chimeraNavInset - 8.dp", '"commander","close","menu"', "ChimeraHandDesign.upgrade(studioLayout)"]:
    if must not in _canvas:
        sys.exit("patch_arcana_nov9: hand design block: missing " + must)
_new_design = (
    "    if (studioLayout != null && argentumMode && studioGeometryProblems.isEmpty()) {\n"
    "        // Chimera (owner, Nov 9): the status line, close and menu are pieces of the design (Studio can move, restyle or\n"
    "        // hide them); the grab bar sits over the top middle, and the design starts at the drawer's top edge.\n"
    "        val chimeraStatus = chimeraStatusLine(selectedCard, compactHandLine(cards.size, turnContext, cards.count { it.id in playableIds }, stackCount))\n"
    "        val chimeraNavInset = with(LocalDensity.current) { androidx.compose.foundation.layout.WindowInsets.navigationBars.getBottom(this).toDp() }\n"
    "        Box(Modifier.fillMaxWidth()) {\n"
    + _canvas +
    "            ChimeraGripBar(Modifier.align(Alignment.TopCenter).padding(top = 6.dp))\n"
    "            Box(Modifier.align(Alignment.TopCenter).size(width = 96.dp, height = 22.dp).then(ChimeraDrawerTop.drag))\n"
    "            Box(Modifier.align(Alignment.TopEnd)) {\n"
    "                DropdownMenu(expanded = moreOpen, onDismissRequest = { moreOpen = false }) {\n"
    "                    ChimeraHandMenu(stackCount, onStack, commanderCanCast, onCastCommander, { onMoreAction(\"LOG\") }, autoPassing,\n"
    "                        autoPassAvailable && !actionsBusy, onAutoPass) { moreOpen = false }\n"
    "                }\n"
    "            }\n"
    "        }\n"
    "        return\n"
    "    }\n")

patch("TableBoardViews.kt", [
    (_old_design, _new_design),
    ("    onPreview: (GameCard) -> Unit = onSelect, studioLayout: StudioLayout? = null) {",
     "    onPreview: (GameCard) -> Unit = onSelect, studioLayout: StudioLayout? = null,\n"
     "    chimeraCommanders: List<ChimeraCommanders.Entry> = emptyList(), chimeraCastCommander: (GameCard) -> Unit = { onCastCommander() }) {"),
    ("    var moreOpen by remember { mutableStateOf(false) }\n    val density = LocalDensity.current\n    if (libraryInfo)",
     "    var moreOpen by remember { mutableStateOf(false) }\n"
     "    // Chimera (owner, Nov 9): the Commander button (was Stack) glows when a commander can be cast and opens this window.\n"
     "    var chimeraCommanderOpen by remember { mutableStateOf(false) }\n"
     "    val chimeraCastable: (GameCard) -> Boolean = { c -> argentumMode && !actionsBusy && c.id in playableIds }\n"
     "    val chimeraCanCast = chimeraCommanders.any { chimeraCastable(it.card) }\n"
     "    if (chimeraCommanderOpen) ChimeraCommanderWindow(chimeraCommanders, chimeraCastable,\n"
     "        onCast = { c -> chimeraCommanderOpen = false; chimeraCastCommander(c) }, onPreview = onPreview, onDismiss = { chimeraCommanderOpen = false })\n"
     "    val density = LocalDensity.current\n    if (libraryInfo)"),
    # Native drawer: one compact top strip with the menu; Stack moves into the menu, Commander takes its button.
    ("        if (BuildConfig.STUDIO_ENABLED) Row(Modifier.fillMaxWidth().heightIn(min = 44.dp), verticalAlignment = Alignment.CenterVertically) {\n            val line = compactHandLine",
     "        if (BuildConfig.STUDIO_ENABLED && argentumMode) ChimeraNativeTop(chimeraStatusLine(selectedCard,\n"
     "            compactHandLine(cards.size, turnContext, cards.count { it.id in playableIds }, stackCount)), onClose) { close ->\n"
     "            ChimeraHandMenu(stackCount, onStack, commanderCanCast, onCastCommander, { onMoreAction(\"LOG\") }, autoPassing, autoPassAvailable && !actionsBusy, onAutoPass, close)\n"
     "        }\n"
     "        else if (BuildConfig.STUDIO_ENABLED) Row(Modifier.fillMaxWidth().heightIn(min = 44.dp), verticalAlignment = Alignment.CenterVertically) {\n            val line = compactHandLine"),
    ("                stackCount = stackCount, libraryCount = libraryCount, graveyardCount = graveyardCount, exileCount = exileCount,\n",
     "                stackCount = stackCount, libraryCount = libraryCount, graveyardCount = graveyardCount, exileCount = exileCount,\n"
     "                commanderLabel = chimeraCommanderLabel(chimeraCommanders, chimeraCanCast), commanderGlow = chimeraCanCast,\n"
     "                onCommander = { chimeraCommanderOpen = true },\n"),
    ("                onPassHold = if (autoPassing || autoPassAvailable && !actionsBusy) onAutoPass else null)\n            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween",
     "                onPassHold = if (autoPassing || autoPassAvailable && !actionsBusy) onAutoPass else null)\n            if (!BuildConfig.STUDIO_ENABLED) Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween"),
    ("            val areaHeight = (availableHeight - (if (argentumMode) 252.dp else 172.dp)).coerceAtLeast(if (isLandscape) 96.dp else 120.dp)\n",
     "            // Chimera: the top strip is 36dp (was a 28dp grip and a 44dp status row) and the selection row is gone.\n"
     "            val areaHeight = (availableHeight - (if (argentumMode) (if (BuildConfig.STUDIO_ENABLED) 184.dp else 252.dp) else 172.dp)).coerceAtLeast(if (isLandscape) 96.dp else 120.dp)\n"
     "            if (arrangement == HandArrangement.FAN || arrangement == HandArrangement.CATALOGUE) {\n"
     "                ChimeraHandCards(if (arrangement == HandArrangement.FAN) \"fan\" else \"catalogue\", cards, playableIds, selectedId, highlightedCardId,\n"
     "                    { selectedId = it.id; onSelect(it) }, { selectedId = it.id; onPreview(it) }, Modifier.fillMaxWidth().height(areaHeight).testTag(\"hand-grid\"))\n"
     "                return@BoxWithConstraints\n"
     "            }\n"),
])
patch("HandLayout.kt", [
    ('''    SINGLE_ROW("Single row", "One horizontally scrolling row; cards grow with the tray height.");''',
     '''    SINGLE_ROW("Single row", "One horizontally scrolling row; cards grow with the tray height."),
    // Chimera (owner, Nov 9)
    FAN("Fan", "Cards along a gentle arc, overlapping more as the hand grows; the selected card lifts out."),
    CATALOGUE("Catalogue", "One card large in the middle, its neighbours peeking in at the sides; swipe to move through the hand.");'''),
])
patch("HandDrawerControls.kt", [
    ("""    onPassHold: (() -> Unit)? = null,
) {""",
     """    onPassHold: (() -> Unit)? = null,
    // Chimera (owner, Nov 9): the Commander button replaces Stack (Stack moved into the drawer's menu).
    commanderLabel: String? = null, commanderGlow: Boolean = false, onCommander: () -> Unit = {},
) {"""),
    ("""                DrawerControl("STACK\\n$stackCount", "hand-stack", true, onStack),""",
     """                if (commanderLabel != null) DrawerControl(commanderLabel, "hand-commander", true, onCommander, glow = commanderGlow)
                else DrawerControl("STACK\\n$stackCount", "hand-stack", true, onStack),"""),
    ("""                    group.forEach { c -> HandDrawerButton(c.label, c.tag, true, c.click, Modifier.weight(1f)) }""",
     """                    group.forEach { c -> HandDrawerButton(c.label, c.tag, true, c.click, Modifier.weight(1f).chimeraGlow(c.glow, RoundedCornerShape(10.dp))) }"""),
    ("""                                 val weight: Float = 1f, val strong: Boolean = false)""",
     """                                 val weight: Float = 1f, val strong: Boolean = false, val glow: Boolean = false)"""),
])
patch("TableOverlays.kt", [
    ("""                    Box(
                        Modifier.fillMaxWidth().height(if (BuildConfig.STUDIO_ENABLED) 28.dp else 36.dp).tutorialTarget("hand-drawer-grip")
                            .pointerInput(configuration.screenHeightDp) {""",
     """                    // Chimera (owner, Nov 9): engine games draw one compact top strip in the hand itself, which carries this drag.
                    val chimeraGrip = Modifier.pointerInput(configuration.screenHeightDp) {"""),
    ("""                            },
                        contentAlignment = Alignment.Center
                    ) {
                        // Owner, Nov 7: the grab handle must show.""",
     """                            }
                    ChimeraDrawerTop.drag = chimeraGrip
                    if (!ChimeraDrawerTop.ownsTop(argentumEnabled)) Box(
                        Modifier.fillMaxWidth().height(if (BuildConfig.STUDIO_ENABLED) 28.dp else 36.dp).tutorialTarget("hand-drawer-grip").then(chimeraGrip),
                        contentAlignment = Alignment.Center
                    ) {
                        // Owner, Nov 7: the grab handle must show."""),
    ("""                    StudioScreen("Hand") { HandSheet(
                        studioLayout = studioLayout,""",
     """                    StudioScreen("Hand") { HandSheet(
                        studioLayout = studioLayout,
                        chimeraCommanders = if (argentumEnabled) ChimeraCommanders.forSeat(players.getOrNull(handSeat.coerceIn(players.indices))?.id) else emptyList(),
                        chimeraCastCommander = { c -> if (argentumEnabled) openArgentumActions(c) },"""),
])
patch("ArgentumBackend.kt", [
    ("""                val commander = command.firstOrNull() ?: (battlefield + hand + graveyard + exile).firstOrNull { it.isCommander }""",
     """                val commander = command.firstOrNull() ?: (battlefield + hand + graveyard + exile).firstOrNull { it.isCommander }
                // Chimera (owner, Nov 9): every commander, where it is and its tax, for the Commander button and window.
                val chimeraTax = source.optJSONObject("commanderTax")
                ChimeraCommanders.record(serverId, listOf("Command zone" to command, "Battlefield" to battlefield, "Hand" to handVisible,
                    "Graveyard" to graveyard, "Exile" to exile)) { c -> chimeraTax?.optInt(displayIds.entries.firstOrNull { it.value == c.id }?.key.orEmpty(), 0) ?: 0 }"""),
    ("""                    commanderName = commander?.name, commanderCard = commander,""",
     """                    commanderName = commander?.name, commanderCard = commander,
                    commanderCastCount = (ChimeraCommanders.forSeat(serverId).firstOrNull { it.card.id == commander?.id }?.tax ?: 0) / 2,"""),
])

# ------------------------------------------------------------------ 11. built-in backgrounds for the table
patch("StudioTablePanel.kt", [
    ("""                if(project.backdrop!=null)StudioChip("Remove",!busy,"studio-backdrop-remove"){runtime.change(runtime.draft.copy(backdrop=null))}
            }
""",
     """                if(project.backdrop!=null)StudioChip("Remove",!busy,"studio-backdrop-remove"){runtime.change(runtime.draft.copy(backdrop=null))}
            }
            // Chimera (owner, Nov 9): built-in backgrounds, drawn on the phone the first time one is picked.
            Row(Modifier.hintedHorizontalScroll(rememberScrollState()),verticalAlignment=Alignment.CenterVertically) {
                Text("Built-in",color=darkTableColors.muted,fontSize=12.sp,modifier=Modifier.padding(end=6.dp))
                ChimeraBackdrops.ALL.forEach{(id,label)->StudioChip(label,!busy,"chimera-backdrop-$id",selected=project.backdrop=="bg-chimera-$id.jpg"){
                    busy=true
                    scope.launch {
                        runCatching{withContext(Dispatchers.IO){ChimeraBackdrops.ensureTable(context,id)}}
                            .onSuccess{name->runtime.change(runtime.draft.copy(backdrop=name))}
                            .onFailure{onMessage("Couldn't draw that background.")}
                        busy=false
                    }
                }}
            }
"""),
])

# ------------------------------------------------------------------ 12. Pill and Round button looks (main screen buttons)
patch("StudioButtonTypes.kt", [
    ("""        "Tray bar" to Look(RoundedCornerShape(topStart=10.dp,topEnd=10.dp,bottomStart=2.dp,bottomEnd=2.dp),{it.panel},{it.counter},{BorderStroke(1.dp,it.counter.copy(alpha=0.3f))}), // like HAND 7
    )""",
     """        "Tray bar" to Look(RoundedCornerShape(topStart=10.dp,topEnd=10.dp,bottomStart=2.dp,bottomEnd=2.dp),{it.panel},{it.counter},{BorderStroke(1.dp,it.counter.copy(alpha=0.3f))}), // like HAND 7
        // Chimera (owner, Nov 9): fully rounded ends; and a circle, which widens into a pill when its text needs the room.
        "Pill" to Look(RoundedCornerShape(50),{it.accent.copy(alpha=0.16f)},{it.text},{BorderStroke(1.dp,it.accent.copy(alpha=0.7f))}),
        "Round" to Look(RoundedCornerShape(50),{it.raised},{it.text},{BorderStroke(1.5.dp,it.accent)}),
    )"""),
])
# Hand designs: binding names, card styles, side column, shapes, and the status line's explanation.
patch("StudioHandEditor.kt", [
    ('"metric0" to "Stack",', '"commander" to "Commander window (glows when castable)","close" to "Close the hand","menu" to "Hand menu (⋮)","metric0" to "Stack",'),
    ("""        StudioSlide(e.r,0f..64f,runtime){v->change{it.copy(r=v)}})
""",
     """        StudioSlide(e.r,0f..64f,runtime){v->change{it.copy(r=v)}})
    ChimeraShapeChips(e,change)
"""),
    ("""    if(e.kind=="card") {
        Text("Shows card""",
     """    if(e.kind=="status") {
        Text("Shows the live hand status (cards, phase, playable, stack). Dragging it resizes the drawer.",color=darkTableColors.muted,fontSize=12.sp)
        return
    }
    if(e.kind=="card") {
        Text("Shows card"""),
    ("""        listOf("Minimal","Balanced","Detailed").forEach {d->StudioChip(d,true,"studio-hand-$d"){runtime.changeHand(StudioLayout.responsive(widthDp,heightDp,d))}}
    }
""",
     """        listOf("Minimal","Balanced","Detailed").forEach {d->StudioChip(d,true,"studio-hand-$d"){runtime.changeHand(StudioLayout.responsive(widthDp,heightDp,d))}}
        // Chimera (owner, Nov 9): an option, not the default — buttons in a column beside the cards, for wide screens.
        StudioChip("Buttons in a side column",true,"chimera-hand-side-column"){runtime.changeHand(ChimeraHandLayouts.sideColumn(layout))}
    }
    ChimeraHandStyleChips(layout){runtime.changeHand(it)}
"""),
])

# ------------------------------------------------------------------ 8. ten presets, each its own layout; button text always kept
_sp = (app / "StudioPresets.kt").read_text()
_pa = _sp.index("    val all=listOf(\n")
_pb = _sp.index("    )\n", _pa) + len("    )\n")
patch("StudioPresets.kt", [
    (_sp[_pa:_pb],
     '''    // Chimera (owner, Nov 9): each preset also brings its own hand design (ChimeraHandLayouts), colours and background.
    // None uses "Minimal" (it hides live text, including some button text) and none hides the main button row.
    val all=listOf(
        StudioPreset("Classic","Arcana as it ships",CLASSIC_LOOK,"Balanced",emptySet(),"both","Table default"),
        StudioPreset("Duelist's Gauntlet","One hand on the folded phone: Pass under your thumb, cards fanned above",StudioLook(0.95f,0.85f,1.1f,1.1f),"Balanced",
            setOf("fullTableLabel"),"turn",null),
        StudioPreset("War Table","For the open Fold: a console of buttons beside big cards, brass on green felt",StudioLook(1.1f,1.1f,1f,1.15f),"Balanced",
            setOf("fullTableLabel"),"both",null),
        StudioPreset("Grimoire","Your hand as a spellbook with bookmark tabs, parchment and oxblood on dark wood",StudioLook(1f,1.2f,1f,1.05f),"Balanced",
            setOf("fullTableLabel","timer"),"round",null),
        StudioPreset("Arcade Cabinet","Chunky cabinet controls, scoreboard counters, neon on black",StudioLook(1.05f,0.9f,1.2f,1.15f),"Balanced",
            setOf("fullTableLabel"),"turn",null),
        StudioPreset("Glass Cockpit","A see-through drawer so the battlefield always shows; cards in a carousel",StudioLook(1f,1f,0.95f,1f),"Balanced",
            setOf("fullTableLabel","engineThinking"),"turn",null),
        StudioPreset("Command Center","Every number and piece of information, dense and small",StudioLook(0.85f,0.8f,0.9f,0.85f),"Detailed",
            emptySet(),"both","Slate"),
        StudioPreset("Big & Bold","Oversized buttons and cards, maximum contrast",StudioLook(1.2f,1.1f,1.3f,1.3f),"Balanced",
            setOf("fullTableLabel","timer"),"turn","Monochrome"),
        StudioPreset("Zen Garden","Calm: one row of cards, soft pills, room to breathe",StudioLook(1.05f,1.9f,1.05f,1.05f),"Balanced",
            setOf("fullTableLabel","engineThinking","timer"),"round",null),
        StudioPreset("Battlefield First","The table takes over: big battlefield cards, every hand button on one compact strip",StudioLook(1.3f,1.15f,0.9f,0.9f),"Balanced",
            setOf("fullTableLabel","engineThinking","timer"),"round",null),
    )
'''),
])
patch("StudioEditorPanels.kt", [
    ("""    var keepColours by remember{mutableStateOf(false)}
""",
     """    var keepColours by remember{mutableStateOf(false)}
    var chimeraBefore by remember{mutableStateOf<ChimeraPresets.Before?>(null)}
    val chimeraContext=androidx.compose.ui.platform.LocalContext.current
    val chimeraScreen=androidx.compose.ui.platform.LocalConfiguration.current
"""),
    ("""                        if(trying==null)trying=runtime.draft to look.read()
                        tryingPreset=preset.name
                        look.write(preset.look)
                        runtime.change(StudioPresets.project(trying!!.first,preset,keepColours))""",
     """                        if(trying==null)trying=runtime.draft to look.read()
                        if(chimeraBefore==null)chimeraBefore=ChimeraPresets.Before(if(runtime.hasPendingHand())runtime.handDraft() else null,ChimeraDrawer.opacity)
                        tryingPreset=preset.name
                        look.write(preset.look)
                        // Chimera: the preset's hand design, colours, background and drawer opacity come with it (Back undoes all).
                        runtime.change(ChimeraPresets.project(chimeraContext,StudioPresets.project(trying!!.first,preset,keepColours),preset.name,keepColours),
                            ChimeraHandLayouts.forPreset(preset.name,chimeraScreen.screenWidthDp.toFloat(),chimeraScreen.screenHeightDp.toFloat(),keepColours) ?: chimeraBefore?.hand)
                        ChimeraDrawer.set(ChimeraPresets.opacity(preset.name))"""),
    ("""                    Button(onClick={trying=null;StudioLog.add("Preset kept: $tryingPreset");tryingPreset=null},""",
     """                    Button(onClick={trying=null;chimeraBefore=null;StudioLog.add("Preset kept: $tryingPreset");tryingPreset=null},"""),
    ("""                    OutlinedButton(onClick={look.write(before);runtime.change(project);trying=null;""",
     """                    OutlinedButton(onClick={look.write(before);runtime.change(project,chimeraBefore?.hand);chimeraBefore?.let{ChimeraDrawer.set(it.opacity)};chimeraBefore=null;trying=null;"""),
])
