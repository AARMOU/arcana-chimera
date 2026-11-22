#!/usr/bin/env python3
"""Build-time: owner, Nov 19 — Studio usability audit fixes, the Options audit, the tutorial's Stack chapter, and the
home commander picture.

Each edit names text that must be present exactly once, so a changed Arcana file fails the build loudly instead of
being half-edited. Never committed to arcana_table.

  Studio
   1  panels stay on screen (ChimeraStudio.kt: clamp, double-tap reset, reset each session)
   2  the Table panel is capped and scrolls like the other panels
   3  collapsing applies to the panel you collapsed, not every panel after it
   4/11 one save rule: looks, typeface, home picture and drawer settings are previewed and kept only on APPLY
   5  Classic panel and owner hand designs behind Options › Troubleshooting › Developer tools
   6  Home deck picture, typeface and Terminal move out of the Table panel to Studio options › Looks
   7  instructions that were wrong (Classic panel text; tutorial step about Studio in Settings)
   8  Looks: presets and screen layouts as a gallery, the first page of Studio options
   9  colours: the hand drawer's colours join the Palette page
  10  Adjust UI (card sizes, quick actions, notices) becomes Studio options › Game; its separate window is gone
  Table panel: tapping the Table tab again opens it
  Options: regrouped and reworded; Arcana Studio entry; developer tools switch; "Respond to my spells" removed
  Tutorial: the Stack chapter no longer needs the STACK button or a detected priority pass; stuck steps can be skipped
  Home: art only (a chosen commander art was shown as the whole card); size button; Deck stats/History only with a deck
"""
import json
import re
import sys
from pathlib import Path

APP = Path(sys.argv[1])
SRC = APP / "src/main/java/com/arcanatable"
count = 0


def edit(name, old, new, n=1):
    global count
    p = SRC / name
    s = p.read_text(encoding="utf-8")
    found = s.count(old)
    if found != n:
        sys.exit(f"patch_studio_usability: {name}: expected {n} of {old[:90]!r}, found {found}")
    p.write_text(s.replace(old, new), encoding="utf-8")
    count += 1


def cut(name, start, end, new, keep_end=True):
    """Replace from start (inclusive) to end (inclusive unless keep_end) with new."""
    global count
    p = SRC / name
    s = p.read_text(encoding="utf-8")
    i = s.find(start)
    if i < 0 or s.count(start) != 1:
        sys.exit(f"patch_studio_usability: {name}: start not unique: {start[:90]!r}")
    j = s.find(end, i)
    if j < 0:
        sys.exit(f"patch_studio_usability: {name}: no end {end[:90]!r}")
    stop = j if keep_end else j + len(end)
    p.write_text(s[:i] + new + s[stop:], encoding="utf-8")
    count += 1
    return s[i:stop]


def between(name, start, end):
    s = (SRC / name).read_text(encoding="utf-8")
    i = s.index(start)
    return s[i:s.index(end, i)]


# ------------------------------------------------------------------ home picture
# The commander art picker saves a whole-card picture; the home screen showed it as is (card text and borders).
edit("TableOverlays.kt",
     "val art = commanderName?.let { name -> ChimeraArt.get(name) ?: homeArtUrl(tableArt, bundledArt, name) }",
     "val art = commanderName?.let { name -> commanderArtOnly(ChimeraArt.get(name)) ?: homeArtUrl(tableArt, bundledArt, name) } // owner, Nov 19: art only")
# Owner, Nov 21: no size button on the home picture; the crop is chosen in Studio only
# (⋮ › Studio options › Looks › Home deck picture crop). Tapping the picture still shows the whole card large.
# Deck stats and History only when there is a deck to show them for.
edit("HomeScreen.kt", "ChimeraHomeButtons(enabled = true)", "if (deck != null) ChimeraHomeButtons(enabled = true) // owner, Nov 19: only with a deck")

# ------------------------------------------------------------------ Table panel (2, 6)
cut("StudioTablePanel.kt",
    "            // Chimera (owner, Nov 9): the commander picture on the home screen.\n",
    "                ChimeraTerminalStudioChip()\n            }\n",
    "            Text(\"Home deck picture, typeface and the Terminal layout: ⋮ › Studio options › Looks.\",color=darkTableColors.muted,fontSize=11.sp)\n",
    keep_end=False)
edit("StudioTablePanel.kt",
     "            if(collapsed)return@Column\n",
     "            if(collapsed)return@Column\n"
     "            // Owner, Nov 19: capped and scrolling like every other Studio panel, so its top row never leaves the screen.\n"
     "            Column(Modifier.heightIn(max=ChimeraStudioUi.bodyMax()).testTag(\"studio-table-body\").verticalScroll(rememberScrollState()).padding(vertical=4.dp)) {\n")
p = SRC / "StudioTablePanel.kt"
s = p.read_text(encoding="utf-8")
if not s.endswith("\n        }\n    }\n}\n"):
    sys.exit("patch_studio_usability: StudioTablePanel.kt: unexpected ending")
s = s[: -len("\n        }\n    }\n}\n")] + "\n            }\n        }\n    }\n}\n"
s = s.replace("import androidx.compose.foundation.rememberScrollState\n",
              "import androidx.compose.foundation.rememberScrollState\nimport androidx.compose.foundation.verticalScroll\n", 1)
p.write_text(s, encoding="utf-8")

# ------------------------------------------------------------------ editor shell (1, 3, 4/11, Table tab)
edit("StudioEditorShell.kt",
     "    var confirmClose by remember{mutableStateOf(false)}\n",
     "    var confirmClose by remember{mutableStateOf(false)}\n"
     "    // Owner, Nov 19: instant settings are remembered as they were, so leaving without APPLY puts them back.\n"
     "    val chimeraSessionContext=androidx.compose.ui.platform.LocalContext.current\n"
     "    remember(runtime){ChimeraStudioSession.begin(chimeraSessionContext,look);true}\n")
edit("StudioEditorShell.kt",
     "    val popups=StudioEditorRequests.popups\n",
     "    // Owner, Nov 19: collapsing is for the panel you collapsed; the next item's panel opens whole.\n"
     "    LaunchedEffect(runtime.selected,runtime.tablePicked,handSelected){collapsed=false}\n"
     "    val popups=StudioEditorRequests.popups\n")
edit("StudioEditorShell.kt",
     "runCatching{runtime.apply()}.onSuccess{onApplied()}",
     "runCatching{runtime.apply()}.onSuccess{ChimeraStudioSession.commit();onApplied()}")
edit("StudioEditorShell.kt",
     "val close:()->Unit={if(runtime.canUndo || runtime.hasDraft())confirmClose=true else runtime.cancel()}",
     "val close:()->Unit={if(runtime.canUndo || runtime.hasDraft() || ChimeraStudioSession.dirty())confirmClose=true else {ChimeraStudioSession.revert();runtime.cancel()}}")
edit("StudioEditorShell.kt",
     "TextButton(onClick={confirmClose=false;runtime.discard()}",
     "TextButton(onClick={confirmClose=false;ChimeraStudioSession.revert();runtime.discard()}")
edit("StudioEditorShell.kt",
     "confirmButton={TextButton(onClick={confirmClose=false;runtime.cancel()}",
     "confirmButton={TextButton(onClick={confirmClose=false;ChimeraStudioSession.revert();runtime.cancel()}")
edit("StudioEditorShell.kt",
     'Text("Your draft is kept and reopens next time. Nothing changes in the game until you Apply.")',
     'Text("Your layout draft is kept and reopens next time. Looks, typeface and the home picture go back to how they were. Nothing changes until you Apply.")')
edit("StudioEditorShell.kt",
     "    val pick:(String)->Unit={name->\n        if(name==\"Hand\")",
     "    val pick:(String)->Unit={name->\n"
     "        // Owner, Nov 19: tapping the Table tab again opens the Table panel (background and colours), however busy the board is.\n"
     "        if(name==\"Table\" && tab==\"Table\" && !noGame){runtime.selected=null;runtime.tablePicked=true}\n"
     "        else if(name==\"Hand\")")
edit("StudioEditorShell.kt",
     'Text("Studio changes the table, player, stack, zones and log screens. Start an offline practice game against the AI to see and edit them. Your layout then applies to every game.",',
     'Text("Studio changes the table, player, stack, zones and log screens. Start an offline practice game against the AI to see and edit them. Your layout then applies to every game. Looks and the whole-app settings are under ⋮ › Studio options.",')

# ------------------------------------------------------------------ Studio options (5, 6, 8, 9, 10)
P = "StudioEditorPanels.kt"
edit(P, 'var page by remember{mutableStateOf(if(hand!=null)"Hand" else "Layout")}',
     'var page by remember{mutableStateOf(if(hand!=null)"Hand" else "Looks")}')
edit(P, 'val pages=listOfNotNull("Hand".takeIf{hand!=null},"Layout","Palette","Project")',
     'val pages=listOfNotNull("Looks","Hand".takeIf{hand!=null},"Layout","Palette","Game".takeIf{ChimeraGameOptions.content!=null},"Project")')
edit(P, 'Text(p,color=if(p==page)StudioEditorRequests.chromeGold else darkTableColors.muted,fontSize=13.sp)',
     'Text(p,color=if(p==page)StudioEditorRequests.chromeGold else darkTableColors.muted,fontSize=12.sp,maxLines=1)')

presets_start = '        if(page=="Layout") {\n        // Owner, Nov 6 (batch 4): presets that make really noticeable changes, with Keep / Back.\n'
presets_end = '        StudioSection("Layout profile")\n'
old_presets = between(P, presets_start, presets_end)
try_start = old_presets.index("            val chimeraTry:(StudioPreset)->Unit={preset->")
try_end = old_presets.index("            Row(Modifier.hintedHorizontalScroll()) {", try_start)
chimera_try = old_presets[try_start:try_end]
keep_start = old_presets.index("            trying?.let{(project,before)->")
keep_end = old_presets.index('                Text("Card and button sizes change right away; Back puts them and the layout back."', keep_start)
keep_back = old_presets[keep_start:keep_end] + (
    '                Text("Previewed now. Keep holds it in your draft; APPLY saves it. Back puts everything back.",color=darkTableColors.muted,fontSize=11.sp)\n'
    "            }\n")
looks = (
    '        // Owner, Nov 19: Looks first. Presets and screen layouts as a gallery, then the settings that change the whole app.\n'
    '        if(page=="Looks") {\n'
    "        if(look!=null) {\n"
    + chimera_try
    + keep_back
    + '            StudioSection("Presets")\n'
    + '            Text("Restyle the whole game in one tap. Everything in a preset can be changed afterwards.",color=darkTableColors.muted,fontSize=11.sp)\n'
    + "            ChimeraLookCards(StudioPresets.all.filter{!ChimeraPresets.isLayout(it.name)},tryingPreset){chimeraTry(it)}\n"
    + '            StudioSection("Screen layouts")\n'
    + '            Text("Whole screens built into Chimera, each with its own menu. Studio edits the classic screen; each layout\'s menu has \\"Classic screen\\".",color=darkTableColors.muted,fontSize=11.sp)\n'
    + "            ChimeraLookCards(StudioPresets.all.filter{ChimeraPresets.isLayout(it.name)},tryingPreset){chimeraTry(it)}\n"
    + '            StudioToggleRow("Keep my colours",keepColours,"studio-preset-keep-colours"){keepColours=it}\n'
    + "        }\n"
    + "        ChimeraAppLookSection()\n"
    + '        StudioSection("Table")\n'
    + '        Row(Modifier.hintedHorizontalScroll()){StudioChip("Table background and colours…",true,"studio-looks-table"){runtime.selected=null;runtime.tablePicked=true;onDismiss()}}\n'
    + "        }\n"
    + '        if(page=="Layout") {\n'
)
cut(P, presets_start, presets_end, looks)
# Adjust UI's options are now the Game page.
cut(P, "        // Owner, Nov 6: Adjust UI's options live in Studio now (its old entry points open Studio in these builds).\n",
    '        StudioSection("Turn label")\n', "")
edit(P, '        if(page=="Palette") {\n',
     '        if(page=="Game") {\n'
     "        // Owner, Nov 19: Adjust UI retired; card sizes, quick actions and game notices are a page here.\n"
     "        ChimeraGameOptions.content?.invoke()\n"
     "        }\n"
     '        if(page=="Palette") {\n')
edit(P, 'StudioToggleRow("Snap to 4dp grid",snap,"studio-snap",onSnap)', 'StudioToggleRow("Snap to grid",snap,"studio-snap",onSnap)')
edit(P, 'onClassic?.let{StudioChip("Classic panel",true,"studio-classic",onClick=it)}',
     'if(ChimeraDev.on)onClassic?.let{StudioChip("Classic panel",true,"studio-classic",onClick=it)} // owner, Nov 19: developer tools only')
# Colours in one place: the hand drawer's colours join the Palette page.
edit(P, '        }\n        if(page=="Project") {\n',
     '        StudioSection("Hand drawer colours")\n'
     '        Text("The drawer uses the colours above unless you give it its own. An item\'s Style tab, and Match picture in the Table panel, also set colours.",color=darkTableColors.muted,fontSize=11.sp)\n'
     "        val chimeraHand=StudioHandEditing.layout(runtime,chimeraScreen.screenWidthDp.toFloat(),chimeraScreen.screenHeightDp.toFloat())\n"
     "        Row(Modifier.hintedHorizontalScroll()) {\n"
     '            StudioChip("Same as above",chimeraHand.theme.isNotEmpty(),"studio-hand-palette-inherit",selected=chimeraHand.theme.isEmpty()){runtime.changeHand(chimeraHand.copy(theme=emptyMap()))}\n'
     "            tablePalettePresets.forEach {preset->StudioChip(preset.name,true,\"studio-hand-palette-${preset.name}\"){\n"
     "                runtime.changeHand(chimeraHand.copy(theme=StudioLayout.themeRoles.associateWith{role->tableColorHex(preset.colors.colorRole(if(role==\"gold\")\"counter\" else role))}))\n"
     "            }}\n"
     "        }\n"
     "        val handBase=chimeraHand.colors(darkTableColors)\n"
     "        val handSwatches=listOf(\"Surface\" to handBase.panel,\"Raised\" to handBase.raised,\"Accent\" to handBase.accent,\"Text\" to handBase.text,\"Gold\" to handBase.counter)\n"
     "        StudioLayout.themeRoles.forEach {role->\n"
     "            StudioColorField(\"Drawer \"+role,chimeraHand.theme[role],handSwatches,{v->\n"
     "                runtime.changeHand(chimeraHand.copy(theme=if(v==null)chimeraHand.theme-role else chimeraHand.theme+(role to v)))},\"studio-hand-theme-$role\")\n"
     "        }\n"
     "        }\n"
     '        if(page=="Project") {\n')

H = "StudioHandEditor.kt"
cut(H, '    Text("Hand palette (saved with the hand)"', "    ChimeraDrawerControls()\n",
    '    Text("Drawer colours are on the Palette page.",color=darkTableColors.muted,fontSize=11.sp,modifier=Modifier.padding(top=10.dp))\n')
edit(H, '        StudioChip("Owner design",true,"studio-hand-owner") {',
     '        if(ChimeraDev.on)StudioChip("Owner design",true,"studio-hand-owner") { // owner, Nov 19: developer tools only')
edit(H, '        StudioChip("Reference",true,"studio-hand-reference"){runtime.changeHand(StudioLayout.reference())}',
     '        if(ChimeraDev.on)StudioChip("Reference",true,"studio-hand-reference"){runtime.changeHand(StudioLayout.reference())}')
edit(H, 'Switch(ChimeraAdapt.sideColumn,{ChimeraAdapt.changeSideColumn(it)},modifier=Modifier.testTag("chimera-side-column"))',
     'Switch(ChimeraAdapt.sideColumn,{ChimeraAdapt.changeSideColumn(it)},modifier=Modifier.testTag("chimera-side-column")) // kept on APPLY (owner, Nov 19)')
edit(H, 'Text("Takes effect immediately; off shows the native hand drawer.",color=darkTableColors.muted,fontSize=11.sp)',
     'Text("Saves right away. Off shows the standard hand drawer.",color=darkTableColors.muted,fontSize=11.sp)')

# 7: the Classic panel's instructions said "hold to move"; the editor drags to move and holds to select several.
edit("StudioControlsPage.kt",
     "Tap a control to edit; hold to move, or hold its lower-right corner to resize.",
     "Tap a control to edit it; drag it to move, or drag its lower-right corner to resize. Hold to select several.")

# ------------------------------------------------------------------ Adjust UI becomes Studio's Game page (10)
A = "AdjustUiPage.kt"
edit(A, "    inStudio: Boolean = false,\n) {", "    inStudio: Boolean = false,\n    /** Owner, Nov 19: drawn inside Studio options (its Game page) instead of as its own window. */\n    embedded: Boolean = false,\n) {")
body_start = "                Column(Modifier.weight(1f).fillMaxWidth().padding(top = 7.dp).verticalScroll(rememberScrollState())) {\n                    TablePalettePicker()\n"
body_end = '                    AdjustSlider("Combat card size", combatSize, MIN_COMBAT_ZOOM..MAX_COMBAT_ZOOM, { "${(it * 100).toInt()}%" }, onCombatSize)\n'
old = between(A, body_start, body_end)
inner = old[len(body_start):] + body_end
cut(A, body_start, body_end,
    "                Column(Modifier.weight(1f).fillMaxWidth().padding(top = 7.dp).verticalScroll(rememberScrollState())) {\n"
    "                    TablePalettePicker()\n                    chimeraBody()\n", keep_end=False)
edit(A, "    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {",
     "    val chimeraBody: @Composable () -> Unit = {\n" + inner + "    }\n"
     "    if (embedded) {\n"
     "        Column(Modifier.fillMaxWidth()) {\n"
     '            Text("Game options save as you change them.", color = Muted, fontSize = 11.sp)\n'
     "            chimeraBody()\n"
     "            TextButton(onClick = onReset, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) {\n"
     '                Text("RESET GAME OPTIONS", color = Color(0xFFE5B2A7), fontSize = 10.sp, fontWeight = FontWeight.Bold)\n'
     "            }\n"
     "        }\n"
     "        return\n"
     "    }\n"
     "    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {")

# Owner, Nov 19 (vibration off by default): "Buzz on your turn" was never read anywhere; Options › Vibration is the one
# vibration setting, and an online nudge follows it too.
edit(A, '                    AdjustToggle("Buzz on your turn", "Give a short buzz when your turn begins.", turnBuzz, onTurnBuzz)\n', "")
edit("TableWifi.kt", "internal fun TableController.buzz(ms: Long) {\n",
     "internal fun TableController.buzz(ms: Long) {\n    if (ChimeraHaptics.level == \"off\") return // owner, Nov 19: Options › Vibration covers every buzz\n")

# The Adjust UI call in TableOverlaysB: the same arguments now also feed Studio's Game page.
T = "TableOverlaysB.kt"
s = (SRC / T).read_text(encoding="utf-8")
head = "    if (layoutLabOpen) AdjustUiPage(\n"
if s.count(head) != 1:
    sys.exit("patch_studio_usability: TableOverlaysB.kt: AdjustUiPage call not found once")
i = s.index(head)
j = i + len(head)  # just after "AdjustUiPage("
depth, k, in_str = 1, j, False
while depth:
    ch = s[k]
    if in_str:
        if ch == "\\":
            k += 1
        elif ch == '"':
            in_str = False
    elif ch == '"':
        in_str = True
    elif ch in "({[":
        depth += 1
    elif ch in ")}]":
        depth -= 1
    k += 1
args = s[j:k - 1].rstrip()
if args.endswith(","):
    args = args[:-1]
new_call = ("    // Owner, Nov 19: Adjust UI's window is retired; the same options are Studio options › Game.\n"
            "    val chimeraGameOptions: @Composable (Boolean) -> Unit = { chimeraEmbedded -> AdjustUiPage(\n" + args +
            ",\n        embedded = chimeraEmbedded)\n    }\n"
            "    androidx.compose.runtime.SideEffect { ChimeraGameOptions.content = { chimeraGameOptions(true) } }\n"
            "    if (layoutLabOpen) chimeraGameOptions(false)")
s = s[:i] + new_call + s[k:]
(SRC / T).write_text(s, encoding="utf-8")
count += 1

# ------------------------------------------------------------------ Options (owner, Nov 19: audit the options menu)
D = "TableDialogs.kt"
edit(D, 'SettingsToggle("Reduce swipe animation", "Skip the animated jump when switching player tabs.", reducedSwipeMotion, onReducedMotion, Modifier)',
     'SettingsToggle("Reduce swipe animation", "Switch player tabs instantly instead of sliding.", reducedSwipeMotion, onReducedMotion, Modifier)')
edit(D, "if (false && onOpenStudio != null) {", "if (onOpenStudio != null) { // owner, Nov 19: Studio from Options")
edit(D, 'androidx.compose.material3.Text("ARCANA STUDIO  ›", color = Mint, fontWeight = FontWeight.Bold, modifier = Modifier.fillMaxWidth())',
     'androidx.compose.material3.Text("ARCANA STUDIO  ›  looks, layout, colours, game options", color = Mint, fontWeight = FontWeight.Bold, modifier = Modifier.fillMaxWidth())')
# Owner, Nov 19: "Respond to my spells and abilities" is removed from Options, and is off for everyone (a saved "on"
# or one restored from a saved game no longer applies), so it can't stay on with no way to turn it off.
p = SRC / D
s = p.read_text(encoding="utf-8")
m = re.search(r'                SettingsToggle\("Respond to my spells and abilities", [^\n]*\n', s)
if not m or s.count('SettingsToggle("Respond to my spells and abilities"') != 1:
    sys.exit("patch_studio_usability: Respond to my spells toggle not found once")
p.write_text(s[:m.start()] + s[m.end():], encoding="utf-8")
count += 1
edit("TableController.kt", 'var respondToOwnStack by run { mutableStateOf(optionPrefs.getBoolean("respond_to_own_stack", false)) }',
     'var respondToOwnStack by run { mutableStateOf(false) } // owner, Nov 19: setting removed; always off')
edit("TableController.kt", 'if (!tutorialPracticeActive) respondToOwnStack = JSONObject(checkpoint).optBoolean("respondToOwnStack")',
     'if (!tutorialPracticeActive) respondToOwnStack = false // owner, Nov 19: setting removed; always off')
edit(D, 'SettingsToggle("Skip AI actions unless needed", "Offline games: pass over AI spells and abilities unless they affect you. Prompts and blocks still stop the game.",',
     'SettingsToggle("Skip AI spells that don\'t target you", "Offline games: on AI turns, pass automatically unless an AI spell or ability targets you or your cards. Spells that hit everyone, like board wipes, are skipped too. Questions and blocks still stop the game.",')
edit(D, '"Notify you when Forge is waiting for your move."', '"Notify you when a game is waiting for your move."')
# Help first (tutorials, licences), then troubleshooting.
edit(D, '                "HELP & DIAGNOSTICS" -> "setting-help-diagnostics"\n',
     '                "HELP & DIAGNOSTICS", "TROUBLESHOOTING" -> "setting-help-diagnostics"\n')
cut(D, '                LinkRow("Learn & tutorials", "20 chapters and a Magic glossary · works offline", onClick = onTutorials)\n                LinkRow("Licenses & source code"',
    '                if (false) LinkRow("What\'s new"',
    '                SettingsToggle("Developer tools in Studio", "Shows Studio\'s Classic panel and the owner\'s hand designs. For testing.", ChimeraDev.on, { ChimeraDev.change(it) }, Modifier)\n')

edit(D, '                Section("HELP & DIAGNOSTICS")\n',
     '                Section("HELP")\n'
     '                LinkRow("Learn & tutorials", "20 chapters and a Magic glossary · works offline", onClick = onTutorials)\n'
     '                LinkRow("Licenses & source code", "GPLv3, notices, and corresponding source",\n'
     '                    modifier = Modifier.testTag("chimera-license-settings"),\n'
     '                    onClick = { ChimeraLegal.open = true })\n'
     '                Section("TROUBLESHOOTING")\n')
# ------------------------------------------------------------------ tutorial: a step that can't be done can be skipped
edit("TutorialCoach.kt",
     '                            StudioText("TutorialCoach.Text.12", "Dialogs and overlays", true, step.hint.ifBlank { "Use the highlighted control" },\n'
     '                                color = gold, fontSize = 12.sp, modifier = Modifier.weight(1f))\n',
     '                            StudioText("TutorialCoach.Text.12", "Dialogs and overlays", true, step.hint.ifBlank { "Use the highlighted control" },\n'
     '                                color = gold, fontSize = 12.sp, modifier = Modifier.weight(1f))\n'
     "                            // Owner, Nov 19: a step that waits for the game can't strand the learner. After 20 seconds it can be skipped.\n"
     "                            val chimeraStuck = remember(lesson.id, index) { androidx.compose.runtime.mutableStateOf(false) }\n"
     "                            LaunchedEffect(lesson.id, index) { kotlinx.coroutines.delay(20_000); chimeraStuck.value = true }\n"
     '                            if (chimeraStuck.value) androidx.compose.material3.TextButton(onClick = onNext, modifier = Modifier.height(45.dp).testTag("tutorial-skip-step")) {\n'
     '                                androidx.compose.material3.Text("SKIP STEP", color = gold, fontSize = 12.sp, fontWeight = FontWeight.Bold)\n'
     "                            }\n")

catalog_path = APP / "src/main/assets/tutorials/catalog.json"
catalog = json.loads(catalog_path.read_text(encoding="utf-8"))
chapters = {c["id"]: c for c in catalog["chapters"]}
stack = chapters.get("stack") or sys.exit("patch_studio_usability: no stack chapter")
steps = {s["id"]: s for s in stack["steps"]}
for need in ("open", "last-first", "priority", "opponent-cast", "respond", "inspect-stack", "pass", "close"):
    if need not in steps:
        sys.exit(f"patch_studio_usability: stack chapter lost step {need}")
# Owner, Nov 19: the chapter got stuck. Its first step needed a STACK button that became COMMANDER (Nov 9), and
# "Pass to resolve" waited for a detected priority pass with nothing to fall back on. Now the stack opens by itself
# once your spell is on it, the pass step waits for Giant Growth to resolve (which the lesson can always see),
# and the last steps never need a panel that may already be closed.
stack["steps"] = [
    steps["opponent-cast"],
    steps["respond"],
    dict(steps["inspect-stack"], title="Your spell is on the stack",
         body="Giant Growth waits here until it resolves. The newest spell or ability resolves first, after every player passes: "
              "if someone cast another instant now, it would resolve before Giant Growth."),
    dict(steps["priority"], body="Whoever has priority may act. You still have it, so you could add another spell. "
                                 "Arcana only offers actions the game says are legal right now."),
    dict(steps["pass"], advance={"kind": "event", "event": "SpellResolved"}),
    {"id": "close", "screen": "board", "advance": {"kind": "next"}, "title": "Resolved",
     "body": "Giant Growth resolved and the stack is empty. Your Bears are 5/5 until end of turn.", "hint": "Next"},
    {"id": "where", "screen": "board", "advance": {"kind": "next"}, "title": "Find the stack any time",
     "body": "Open the stack from STACK in your table's shortcuts, or from ⋮ at the top of your hand drawer.", "hint": "Next"},
]
for s in stack["steps"]:
    s.pop("target", None) if s["id"] in ("close", "where") else None
# Settings chapter: where Studio and its looks are now (owner, Nov 19).
settings = {s["id"]: s for s in chapters["settings"]["steps"]}
if "Arcana Studio, on Home and in the ⋮ menu" not in settings["layout"]["body"] or "Layout › Presets" not in settings["reorder"]["body"]:
    sys.exit("patch_studio_usability: settings chapter text changed")
settings["layout"].update(hint="Next", body="Arcana Studio, on Home, in the ⋮ menu and here in Options, edits the game's screens: move, resize and "
                          "restyle buttons, the hand drawer, card size, colours, backgrounds and type.")
settings["reorder"]["body"] = ("In Studio, ⋮ › Studio options opens on Looks: presets restyle the whole game in one tap, and every preset "
                               "can then be changed piece by piece. Screen layouts are whole-screen designs, each with its own menu.")
catalog_path.write_text(json.dumps(catalog, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
count += 1

print(f"patch_studio_usability: {count} edits")
