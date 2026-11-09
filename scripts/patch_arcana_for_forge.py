#!/usr/bin/env python3
"""Build-time patch of the staged Arcana checkout (never committed, never pushed to arcana_table).

Owner, 2026-11-08: "I want the argentum card check gone." Forge is the engine, so Arcana no longer drops
cards Argentum lacks. Every card goes to Forge; Forge reports the ones it doesn't have (ArcanaForgeHost.deck).
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
            sys.exit(f"patch_arcana_for_forge: {rel}: expected 1 match, found {n}: {old[:90]!r}")
        text = text.replace(old, new)
    path.write_text(text)
    print(f"patched {rel} ({len(pairs)} edits)")


KEEP = "{ library.canonical(it) ?: it }"  # Argentum's spelling when it knows the card, else the deck's own name

patch("engine/OfflineGame.kt", [
    ("val yourCommander = commander?.let(library::canonical)",
     "val yourCommander = commander?.let " + KEEP),
    (".filter { deck -> library.canonical(deck.commander) != null }",
     ""),
    ("val supported = yourList.mapNotNull(library::canonical)\n        val missing = yourList.filter { library.canonical(it) == null }.distinct()",
     "val supported = yourList.map " + KEEP + "\n        val missing = emptyList<String>() // Forge decides; it reports cards it lacks"),
    ("val aiList = argentumLibraryList(premade.cards, premade.commander).mapNotNull(library::canonical)",
     "val aiList = argentumLibraryList(premade.cards, premade.commander).map " + KEEP),
    ("val otherLists = others.map { d -> d to argentumLibraryList(d.cards, d.commander).mapNotNull(library::canonical) }",
     "val otherLists = others.map { d -> d to argentumLibraryList(d.cards, d.commander).map " + KEEP + " }"),
    # Arcana's card registry still loads what Argentum knows (card display); the rest is Forge-only.
    ("val cards = library.load((supported + aiList + yourCommander + premade.commander + otherLists.flatMap { (d, l) -> l + d.commander }).toSet())",
     "val cards = library.load((supported + aiList + yourCommander + premade.commander + otherLists.flatMap { (d, l) -> l + d.commander }).filter { library.canonical(it) != null }.toSet())"),
    # Forge plays its own version of every card, so Arcana's "using our own versions" note doesn't apply.
    ("if (custom.isNotEmpty()) notes += ",
     "if (false && custom.isNotEmpty()) notes += "),
    ("val ai = LocalSeat(aiName(premade.commander), filled(aiList, library.canonical(premade.commander)!!), library.canonical(premade.commander))",
     "val ai = LocalSeat(aiName(premade.commander), filled(aiList, library.canonical(premade.commander) ?: premade.commander), library.canonical(premade.commander) ?: premade.commander)"),
    ("val extras = otherLists.mapNotNull { (d, list) -> library.canonical(d.commander)?.let { cmd -> LocalSeat(aiName(cmd), filled(list, cmd), cmd) } }",
     "val extras = otherLists.map { (d, list) -> (library.canonical(d.commander) ?: d.commander).let { cmd -> LocalSeat(aiName(cmd), filled(list, cmd), cmd) } }"),
])

# Wi-Fi/internet guests (Nov 8, multiplayer): the host checked each guest's deck against Argentum, refusing a seat
# whose commander Argentum lacks and dropping its other cards. Forge plays the guest's whole deck, like the host's.
patch("engine/OfflineGame.kt", [
    ("val cmd = commander?.let(library::canonical) ?: return null to",
     "val cmd = commander?.let " + KEEP + " ?: return null to"),
    ("val supported = list.mapNotNull(library::canonical)\n        val missing = list.filter { library.canonical(it) == null }.distinct()",
     "val supported = list.map " + KEEP + "\n        val missing = emptyList<String>() // Forge decides; it reports cards it lacks"),
    ("val toLoad = (supported + cmd).toSet().filter { registry.getCard(it) == null }.toSet()",
     "val toLoad = (supported + cmd).toSet().filter { registry.getCard(it) == null && library.canonical(it) != null }.toSet()"),
])

# Game-loading screen (owner, Nov 9): "Your game is loading, please wait" with a progress bar from the bridge's real
# start-up steps (ChimeraLoading), instead of Argentum's connecting/room wording and the "offline" room code.
patch("TableOverlays.kt", [
    ('StudioText("TableOverlays.Text.6", "Dialogs and overlays", false, "ARGENTUM GAME",',
     'StudioText("TableOverlays.Text.6", "Dialogs and overlays", false, "LOADING",'),
    ('argentumStatus == ArgentumConnectionStatus.CONNECTING -> "Connecting to Argentum…"',
     'argentumStatus == ArgentumConnectionStatus.CONNECTING -> "Your game is loading, please wait"'),
    ('argentumInbound?.optString("type") == "quickGameLobbyState" -> if (argentumVsAiLobby) "Your practice table is ready when Argentum finishes preparing it." else "Your room is open. Share its code so other players can join."',
     'argentumInbound?.optString("type") == "quickGameLobbyState" -> "Your game is loading, please wait"'),
    ('else -> if (argentumVsAiLobby) "Connected. Creating your practice table…" else "Connected. Creating your Argentum room…"',
     'else -> "Your game is loading, please wait"'),
    ('if (!lobbyId.isNullOrBlank()) {',
     'if (!lobbyId.isNullOrBlank() && lobbyId != "offline") {'),
    ('"Your Arcana table and card presentation stay here. Argentum validates the deck and controls every game action.",\n                    color = Muted, fontSize = 14.sp, modifier = Modifier.padding(top = 14.dp)\n                )',
     '"",\n                    color = Muted, fontSize = 1.sp, modifier = Modifier\n                )\n                com.arcanatable.engine.ChimeraLoadingBar(barColor = Mint, trackColor = Panel, textColor = Muted)'),
])

# Deck builder (owner, Nov 9: "a lot of cards aren't showing up"): the library list stopped at 400 matches, fine for
# Argentum's pool, but with Forge's ~33k cards most of the alphabet was out of reach without a search. The list is
# lazy (draws only what's on screen), so it shows every match; names are made unique for its keys.
patch("DeckBuilderScreen.kt", [
    ('result = withContext(Dispatchers.Default) { lib.search(baseQuery, filters, limit = 400) }',
     'result = withContext(Dispatchers.Default) { lib.search(baseQuery, filters, limit = Int.MAX_VALUE).let { r -> r.copy(cards = r.cards.distinctBy { it.name }) } }'),
])

# Hand drawer with a Studio hand design (owner, Nov 9: "used to auto resize the cards when dragging the hand drawer").
# Arcana's Studio canvas scaled the design to the screen width only, so dragging the drawer changed nothing inside it,
# and the dragged height wasn't kept. In play, the design now scales to fit the drawer (width and height, proportions
# kept, centred; buttons still grow to 44dp), and the drawer remembers its height per screen size.
patch("StudioHandCanvas.kt", [
    ("onResize: (String,Float,Float) -> Unit = { _,_,_ -> }) {",
     "onResize: (String,Float,Float) -> Unit = { _,_,_ -> }, fitHeight: androidx.compose.ui.unit.Dp = androidx.compose.ui.unit.Dp.Unspecified) {"),
    ("val scale = maxWidth.value / layout.canvasWidth.takeIf {it.isFinite()}?.coerceIn(240f,2000f).let {it ?: 412f}",
     "val canvasW = layout.canvasWidth.takeIf {it.isFinite()}?.coerceIn(240f,2000f) ?: 412f\n        val widthScale = maxWidth.value / canvasW"),
    ("val extent = ((renderElements.maxOfOrNull { it.y+it.h } ?: origin)-origin).coerceIn(44f,4000f)",
     "val extent = ((renderElements.maxOfOrNull { it.y+it.h } ?: origin)-origin).coerceIn(44f,4000f)\n"
     "        val scale = if (!editing && fitHeight != androidx.compose.ui.unit.Dp.Unspecified && fitHeight.value > 0f)\n"
     "            minOf(widthScale, fitHeight.value / extent).coerceAtLeast(widthScale * 0.3f) else widthScale"),
    ('Box(Modifier.fillMaxWidth().height((extent*scale).dp).testTag("studio-hand-canvas")) {',
     'Box(Modifier.fillMaxWidth().height((extent*scale).dp).padding(start = ((maxWidth.value - canvasW*scale)/2f).coerceAtLeast(0f).dp).testTag("studio-hand-canvas")) {'),
])
patch("TableBoardViews.kt", [
    ("onPreview = { selectedId = it.id; onPreview(it) })\n        }\n        return",
     "onPreview = { selectedId = it.id; onPreview(it) }, fitHeight = availableHeight - 84.dp)\n        }\n        return"),
])
patch("TableOverlays.kt", [
    ("mutableFloatStateOf(((configuration.screenHeightDp - (studioLayout?.originY ?: 344f)",
     'mutableFloatStateOf(optionPrefs.getFloat("chimera_studio_hand_${configuration.screenWidthDp}x${configuration.screenHeightDp}", ((configuration.screenHeightDp - (studioLayout?.originY ?: 344f)'),
    ("configuration.screenHeightDp.coerceAtLeast(1)).coerceIn(.38f,.95f))\n    }",
     "configuration.screenHeightDp.coerceAtLeast(1)).coerceIn(.38f,.95f)))\n    }"),
    ('if (!studioFrame) optionPrefs.edit().putFloat("hand_sheet_height_fraction"',
     'if (studioFrame) optionPrefs.edit().putFloat("chimera_studio_hand_${configuration.screenWidthDp}x${configuration.screenHeightDp}", studioHandFraction).apply()\n'
     '                                        if (!studioFrame) optionPrefs.edit().putFloat("hand_sheet_height_fraction"'),
])

# Change art (owner, Nov 9): a card's menu in the deck builder opens every printing's artwork (ChimeraArt); your
# choice shows on your cards on the table, in hand and on the mulligan screen (the host also sends it to everyone).
patch("DeckBuilderScreen.kt", [
    ("onReplace: () -> Unit, onPreview: () -> Unit) {\n    var menu by remember { mutableStateOf(false) }",
     "onReplace: () -> Unit, onPreview: () -> Unit) {\n    var menu by remember { mutableStateOf(false) }\n"
     "    var chimeraArt by remember { mutableStateOf(false) }\n"
     "    if (chimeraArt) ChimeraArtPicker(line.name) { chimeraArt = false }"),
    ('"Remove all copies") }, onClick = { menu = false; onCount(0) })',
     '"Remove all copies") }, onClick = { menu = false; onCount(0) })\n'
     '                androidx.compose.material3.DropdownMenuItem(text = { androidx.compose.material3.Text("Change art") }, onClick = { menu = false; chimeraArt = true })'),
])
patch("ArgentumBackend.kt", [
    ('imageUrl = card.optString("imageUri").takeUnless { it.isBlank() || it == "null" } ?: artByName(card.optString("name"), card.optBoolean("isToken")),',
     'imageUrl = (if (card.optString("ownerId") == state.optString("viewingPlayerId") && !card.optBoolean("isToken")) ChimeraArt.get(card.optString("name")) else null)\n'
     '                            ?: card.optString("imageUri").takeUnless { it.isBlank() || it == "null" } ?: artByName(card.optString("name"), card.optBoolean("isToken")),'),
])
patch("TableArgentum.kt", [
    ('imageUrl = detail.optString("imageUri").takeUnless { it.isBlank() || it == "null" },\n                    manaCost = detail.optString("manaCost")',
     'imageUrl = ChimeraArt.get(detail.optString("name")) ?: detail.optString("imageUri").takeUnless { it.isBlank() || it == "null" },\n                    manaCost = detail.optString("manaCost")'),
])

# "Pass to my turn" (owner, Nov 9 12:10: "sometimes it passes through my first main straight to combat"): the loop
# waited 300 ms and then decided from the state it had before waiting; when your turn arrived during the wait it
# passed anyway, from your new main phase. After the wait it now steps aside if a newer state has arrived (the next
# round decides from that one).
patch("TableEffects.kt", [
    ("delay(300) // let a burst of updates settle before answering\n",
     "delay(300) // let a burst of updates settle before answering\n"
     "        if (argentumSnapshot !== snapshot || argentumAwaitingUpdate) return@LaunchedEffect // decide from the newest state only\n"),
])

# Hand drawer card size (owner, Nov 9: "the hand drawer still doesn't resize the cards"; chose "auto-resize, never
# smaller than now"). Arcana's Nov 6 drawer kept every card at 92 x 132 (x card size) whatever the drawer's height.
# Now the hand is fitted to the drawer (cards grow as you drag it up, up to Arcana's maximum card size) and never
# shrinks below that 92 x 132 size: below it, rows scroll as before. Single-row keeps Arcana's own layout.
patch("HandLayout.kt", [
    ("        val width = areaWidth.coerceAtLeast(1f)\n        val target = 92f * sizeScale.coerceIn(.6f, 1.6f)\n",
     "        val width = areaWidth.coerceAtLeast(1f)\n        val target = 92f * sizeScale.coerceIn(.6f, 1.6f)\n"
     "        if (cardCount > 0) { // the largest cards that show the whole hand in the drawer\n"
     "            var fitWidth = 0f; var fitColumns = 1\n"
     "            for (r in 1..cardCount) {\n"
     "                val c = ceil(cardCount / r.toFloat()).toInt()\n"
     "                val w = min((width - (c - 1) * 4f) / c, ((areaHeight - (r - 1) * 4f) / r) * 92f / 132f)\n"
     "                if (w > fitWidth) { fitWidth = w; fitColumns = c }\n"
     "            }\n"
     "            if (fitWidth >= target) {\n"
     "                val w = min(fitWidth, BASE_MAX_CARD_WIDTH * sizeScale.coerceIn(.6f, 1.6f))\n"
     "                val r = ceil(cardCount / fitColumns.toFloat()).toInt()\n"
     "                return HandGrid(fitColumns, r, w, w * 132f / 92f, false)\n"
     "            }\n"
     "        }\n"),
])

# Arcana Studio selection (owner, Nov 9: "tap and hold ... automatically selects multiple buttons. I just want to tap
# and hold on a button to enter a selection mode"). Holding an item now selects just it and turns on selection mode;
# taps then add or remove items; holding the selected item again (or closing the selection) ends it. Selecting
# everything like an item stays available as a "Select all N like it" button in the side panel. Same in the hand editor.
patch("StudioControls.kt", [
    ("    var group by mutableStateOf<Set<String>>(emptySet())\n",
     "    var group by mutableStateOf<Set<String>>(emptySet())\n"
     "    /** Chimera (owner, Nov 9): selection mode, entered by holding one item; taps then add or remove items. */\n"
     "    var picking by mutableStateOf(false)\n"),
    ("var selected:String? get()=lead;set(v){lead=v;group=emptySet();if(v!=null)tablePicked=false}",
     "var selected:String? get()=lead;set(v){lead=v;group=emptySet();if(v!=null)tablePicked=false else picking=false}"),
    ("    fun holdSelect(id:String) {\n",
     "    fun holdSelect(id:String) {\n"
     "        if(controls[id]==null)return\n"
     "        if(picking && lead==id){picking=false;group=emptySet();StudioLog.add(\"Selection mode off\");return}\n"
     "        lead=id;group=emptySet();tablePicked=false;picking=true;panelOpen=true\n"
     "        StudioLog.add(\"Selection mode: ${title(id)}; tap items to add them\")\n"
     "    }\n"
     "    /** Arcana's Nov 7 hold: select the item and everything like it (now the side panel's \"Select all like it\"). */\n"
     "    fun selectAllLike(id:String) {\n        picking=true\n"),
])
patch("StudioEditorShell.kt", [
    ("hit!=null && runtime.group.isNotEmpty()->runtime.toggleMember(hit)",
     "hit!=null && (runtime.group.isNotEmpty() || runtime.picking)->runtime.toggleMember(hit)"),
    ("    var handGroup by remember{mutableStateOf(emptySet<String>())}\n    val pickHand:(String?)->Unit={handSelected=it;handGroup=emptySet()}",
     "    var handGroup by remember{mutableStateOf(emptySet<String>())}\n    var handPicking by remember{mutableStateOf(false)}\n"
     "    val pickHand:(String?)->Unit={handSelected=it;handGroup=emptySet();if(it==null)handPicking=false}"),
    ("        if(handSelected==id && handGroup.isNotEmpty())handGroup=emptySet()\n"
     "        else {handSelected=id;handGroup=l.elements.filter{it.id!=id && it.kind==kind && it.visible}.map{it.id}.toSet()}\n"
     "        StudioLog.add(\"Hand: selected ${handGroup.size+1} like $id\")",
     "        if(kind!=null && handPicking && handSelected==id){handPicking=false;handGroup=emptySet()}\n"
     "        else if(kind!=null){handSelected=id;handGroup=emptySet();handPicking=true}\n"
     "        StudioLog.add(if(handPicking)\"Hand: selection mode, $id\" else \"Hand: selection mode off\")"),
    ("group=handGroup,onHold=holdHand,onToggle=toggleHand,",
     "group=handGroup,onHold=holdHand,onToggle=toggleHand,picking=handPicking,"),
])
patch("StudioHandEditor.kt", [
    ("intercept:Boolean,snap:Boolean,outlines:Boolean,group:Set<String> = emptySet(),onHold:(String)->Unit={},onToggle:(String)->Unit={},",
     "intercept:Boolean,snap:Boolean,outlines:Boolean,group:Set<String> = emptySet(),onHold:(String)->Unit={},onToggle:(String)->Unit={},picking:Boolean=false,"),
    ("    val currentGroup by rememberUpdatedState(group)\n",
     "    val currentGroup by rememberUpdatedState(group)\n    val currentPicking by rememberUpdatedState(picking)\n"),
    ("hit!=null && currentGroup.isNotEmpty()->toggle(hit)",
     "hit!=null && (currentGroup.isNotEmpty() || currentPicking)->toggle(hit)"),
])
patch("StudioInspector.kt", [
    ('                    if(runtime.group.isEmpty() && like>0)Text("Hold it to pick all ${like+1} like it",color=darkTableColors.muted,fontSize=10.sp,maxLines=1)\n'
     '                    else if(runtime.group.isNotEmpty())Text("Tap items to add or remove · hold to go back to one",color=darkTableColors.muted,fontSize=10.sp,maxLines=1)\n',
     '                    if(runtime.picking || runtime.group.isNotEmpty())Text("Selection mode: tap items to add or remove · hold to finish",color=darkTableColors.muted,fontSize=10.sp,maxLines=1)\n'
     '                    else Text("Hold it to select several",color=darkTableColors.muted,fontSize=10.sp,maxLines=1)\n'
     '                    if(like>0 && runtime.group.size<like)StudioChip("Select all ${like+1} like it",true,"studio-select-like"){runtime.selectAllLike(control.id)}\n'),
])

# Import-time "not in Argentum" messages: Argentum no longer runs the game, so don't report against it.
patch("DeckSupport.kt", [
    ('''        append("Argentum check: $argentumCopies in Argentum")
        if (customCopies > 0) append(" · $customCopies Arcana custom")
        append(" · $unsupportedCopies not in Argentum")
        append(" (indexed, not gameplay-verified)")''',
     '''        append("Forge engine: all $totalCopies card(s) go to Forge, which reports any it doesn't have when the game starts")'''),
])
# Deck import and editor: Arcana's catalog also reads forge-cards.* (Forge's cards Arcana's library lacks; written at
# build time by ForgeCatalogExport), so search, import, deck checks and card details cover every Forge card.
patch("DeckSupport.kt", [
    ('for ((base, custom) in listOf("all-cards" to false, "custom-cards" to true)) {',
     'for ((base, custom) in listOf("all-cards" to false, "custom-cards" to true, "forge-cards" to false)) {'),
    ('ARGENTUM -> "Argentum library · not verified"', 'ARGENTUM -> "Forge"'),
    ('UNSUPPORTED -> "Not in Argentum"', 'UNSUPPORTED -> "Not in Forge"'),
])
patch("CardImages.kt", [
    ('for (file in listOf("argentum/custom-cards.jsonl", "argentum/all-cards.jsonl")) {',
     'for (file in listOf("argentum/custom-cards.jsonl", "argentum/all-cards.jsonl", "argentum/forge-cards.jsonl")) {'),
])
patch("DeckBuilderScreen.kt", [
    ('if (card.custom) "Arcana custom card" else "Argentum library"', 'if (card.custom) "Arcana custom card" else "Forge library"'),
])
# Deck import (owner, Nov 8): colons in card names ("Ultimate Magic: Meteor"), CSV from any site, looser text,
# and deck links (Archidekt, Moxfield, ManaBox) through ChimeraDeckImport (Chimera overlay).
patch("DeckImportParser.kt", [
    ("""        if (name.contains(':') && !name.contains(" // ")) {""",
     """        if (false && name.contains(':')) { // Forge build: real card names have colons (Ultimate Magic: Meteor)"""),
    ("""    val rows = mutableListOf<ImportRow>()
    val errors = mutableListOf<DeckImportDiagnostic>()""",
     """    @Suppress("NAME_SHADOWING") val raw = ChimeraDeckImport.normalize(raw) // CSV and looser text (Chimera)
    val rows = mutableListOf<ImportRow>()
    val errors = mutableListOf<DeckImportDiagnostic>()"""),
])
patch("TableController.kt", [
    ("""        val initial = parseDeckListDetailed(text)
        if (initial.gameBlockers.isNotEmpty()) {""",
     """        if (ChimeraDeckImport.isUrl(text)) { // a deck link: fetch it, show the list, then import it
            importing = true; importError = null
            ChimeraDeckImport.fetchInto(text, { list -> importing = false; importText = list; importDeck(list, saveAsMine) },
                { e -> importing = false; importError = e })
            return
        }
        val initial = parseDeckListDetailed(text)
        if (initial.gameBlockers.isNotEmpty()) {"""),
])
patch("DeckBuilderScreen.kt", [
    ("""onClick = { pendingImport = importText; importing = false; editing = "" }""",
     """onClick = { ChimeraDeckImport.fetchInto(importText, { list -> pendingImport = list; importing = false; editing = "" }, { e -> importText = importText + System.lineSeparator() + "// " + e }) }"""),
])
patch("TableController.kt", [
    ("if (supportReport != null && supportReport.unsupportedCopies > 0) {\n                    val names = supportReport.unsupportedNames",
     "if (supportReport != null && supportReport.unsupportedCopies > 0 && System.getProperty(\"arcana.argentumCardCheck\") != null) { // Forge build: no Argentum card check\n                    val names = supportReport.unsupportedNames"),
])
