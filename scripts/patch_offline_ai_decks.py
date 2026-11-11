#!/usr/bin/env python3
"""Chimera-only: per-AI offline deck selection, applied to staged Arcana source."""
from pathlib import Path
import sys

def swap(path, old, new):
    data = path.read_text(encoding="utf-8")
    if data.count(old) != 1 or new in data:
        raise RuntimeError("Unsupported staged donor or duplicate AI deck patch: " + path.name)
    path.write_text(data.replace(old, new), encoding="utf-8")

def patch(app):
    root = Path(app) / "src/main/java/com/arcanatable"
    controller = root / "TableController.kt"
    overlay = root / "TableOverlays.kt"
    home = root / "HomeScreen.kt"
    swap(controller,
         '    var offlineAiDeckId by run { mutableStateOf<String?>(null) }',
         '''    var offlineAiDeckId by run { mutableStateOf<String?>(null) }
    var chimeraAiDeckIds by run { mutableStateOf(List(3) { seat -> optionPrefs.getString("chimera_ai_deck_$seat", null) }) }
    fun setChimeraAiDeck(seat: Int, id: String?) {
        if (seat !in 0..2) return
        chimeraAiDeckIds = chimeraAiDeckIds.toMutableList().also { it[seat] = id }
        optionPrefs.edit().apply {
            if (id == null) remove("chimera_ai_deck_$seat") else putString("chimera_ai_deck_$seat", id)
        }.apply()
    }
    fun chimeraAiSeat(seat: Int, id: String): com.arcanatable.engine.LocalSeat {
        val pair = when {
            id.startsWith("precon:") -> preconDecks.firstOrNull { it.id == id.removePrefix("precon:") }?.let { it.decklist to it.commander }
            id.startsWith("saved:") -> savedDecks.firstOrNull { it.name == id.removePrefix("saved:") }?.let { it.text to it.commander }
            else -> null
        } ?: error("AI \${seat + 1}: selected deck no longer exists.")
        val parsed = parseDeckListDetailed(pair.first)
        if (parsed.gameBlockers.isNotEmpty()) error("AI \${seat + 1}: \${parsed.gameBlockers.joinToString(" ")}")
        val commander = pair.second?.takeIf(String::isNotBlank)
            ?: parsed.playableEntries.firstOrNull { it.commander }?.name
            ?: error("AI \${seat + 1}: commander missing.")
        val cards = parsed.playableEntries.flatMap { entry -> List(entry.count) { entry.name } }.toMutableList()
        val commanderIndex = cards.indexOfFirst { it.equals(commander, true) }
        if (commanderIndex >= 0) cards.removeAt(commanderIndex)
        if (cards.size != 99) error("AI \${seat + 1}: expected 99 main-deck cards; found \${cards.size}.")
        return com.arcanatable.engine.LocalSeat("AI \${seat + 1}", cards.toList(), commander)
    }''')
    swap(overlay,
        '                aiDecks = premadeDecks.map { DeckChoice(it.id, it.name, it.commander, builtIn = true) },',
        '''                aiDecks = preconDecks.map { DeckChoice("precon:\${it.id}", it.name, it.commander, builtIn = true) } +
                    savedDecks.map { DeckChoice("saved:\${it.name}", it.name, it.commander, builtIn = false) },''')
    swap(overlay,
        '                onAiDeck = { offlineAiDeckId = it; TutorialActions.tap("setup-ai-deck") },',
        '''                onAiDeck = { setChimeraAiDeck(0, it); TutorialActions.tap("setup-ai-deck") },
                aiDeckSelections = chimeraAiDeckIds,
                onAiDeckForSeat = { seat, deck -> setChimeraAiDeck(seat, deck) },''')
    swap(home,
        '    onAiDeck: (String?) -> Unit,\n    onOffline: () -> Unit,',
        '''    onAiDeck: (String?) -> Unit,
    aiDeckSelections: List<String?> = List(3) { null },
    onAiDeckForSeat: (Int, String?) -> Unit = { _, _ -> },
    onOffline: () -> Unit,''')
    start = '                StudioText("HomeScreen.Text.27", "Home and setup", true, if (aiCount > 1) "FIRST AI\'S DECK (the others are random)"'
    raw = home.read_text(encoding="utf-8")
    begin = raw.find(start)
    end = raw.find('                StudioText("HomeScreen.Text.28"', begin)
    if begin < 0 or end < begin or raw.count(start) != 1:
        raise RuntimeError("PlaySheet source drift")
    replacement = '''                for (aiSeat in 0 until aiCount.coerceIn(1, 3)) {
                    val selected = aiDeckSelections.getOrNull(aiSeat)
                    StudioText("HomeScreen.Text.27", "Home and setup", true, "AI \${aiSeat + 1} DECK",
                        color = Muted, fontSize = 12.sp, fontWeight = FontWeight.Bold,
                        letterSpacing = 1.4.sp, modifier = Modifier.tutorialTarget("setup-ai-deck"))
                    val choices = if (showAllDecks) aiDecks else aiDecks.filter { !it.builtIn || it.id == selected }.take(12)
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        DeckChip("Random", selected == null) { onAiDeckForSeat(aiSeat, null) }
                        choices.forEach { d -> DeckChip(d.name, selected == d.id) { onAiDeckForSeat(aiSeat, d.id) } }
                        if (!showAllDecks && choices.size < aiDecks.size) DeckChip("More decks ▾", false) { showAllDecks = true }
                    }
                }
'''
    home.write_text(raw[:begin] + replacement + raw[end:], encoding="utf-8")
    swap(controller,
        '''                val setup = preparedSetup.copy(
                    ai = preparedSetup.ai.copy(difficulty = levels[0]),
                    extraAis = preparedSetup.extraAis.mapIndexed { i, seat -> seat.copy(difficulty = levels.getOrElse(i + 1) { levels[0] }) },
                )''',
        '''                val defaultAis = listOf(preparedSetup.ai) + preparedSetup.extraAis
                val chosen = (0 until offlineAiCount.coerceIn(1, 3)).map { index ->
                    val id = chimeraAiDeckIds.getOrNull(index)
                    val source = if (id == null) defaultAis.getOrElse(index) { error("AI deck missing") }
                                 else chimeraAiSeat(index, id)
                    source.copy(difficulty = levels.getOrElse(index) { levels[0] })
                }
                val setup = preparedSetup.copy(ai = chosen.first(), extraAis = chosen.drop(1))''')
    print("Per-seat Chimera offline AI deck selection staged")

if __name__ == "__main__":
    if len(sys.argv) != 2: raise SystemExit("usage: patch_offline_ai_decks.py STAGED_APP")
    patch(sys.argv[1])
