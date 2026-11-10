package com.arcanatable

import com.arcanatable.engine.LocalSeat
import com.arcanatable.engine.OfflineCardLibrary
import com.wingedsheep.engine.registry.CardRegistry
import org.json.JSONObject

/**
 * The tutorial's practice boards for Forge (owner, Nov 10: "update the tutorial to work with the current version
 * as-is"). Arcana's lessons used to build their boards in Arcana's old engine and resume them; with Forge each board
 * is a Forge game-state script that Forge applies before the first turn (the same mechanism Forge's puzzles use).
 * The opponent's deck is only Islands, so it never responds in a lesson.
 */
internal object ChimeraTutorialBoards {
    private fun many(name: String, n: Int) = List(n) { name }.joinToString(";")
    private val YOUR_LIBRARY = many("Forest", 30)
    private val AI_LIBRARY = many("Island", 30)
    private val AI_HAND = many("Island", 6)

    private fun script(vararg lines: String) = (listOf(
        "activeplayer=human", "activephase=MAIN1",
        "humanlibrary=$YOUR_LIBRARY", "ailibrary=$AI_LIBRARY", "aihand=$AI_HAND",
    ) + lines).joinToString("\n")

    /** The Forge board for a lesson, or null to let Arcana's own code handle it. */
    fun build(id: String, library: OfflineCardLibrary): TestScenarioGame? {
        val commander = id == TutorialPracticeScenarios.TOUR
        val you = LocalSeat("You", listOf("Grizzly Bears", "Elvish Mystic", "Giant Growth", "Llanowar Elves") + List(36) { "Forest" },
            if (commander) TutorialPracticeScenarios.YOUR_COMMANDER else null)
        val ai = LocalSeat("Training Opponent", List(40) { "Island" }, if (commander) TutorialPracticeScenarios.OPPONENT_COMMANDER else null)
        val board = when (id) {
            // Your first turn: Forest and Elvish Mystic in hand, nothing on the battlefield yet.
            TutorialPracticeScenarios.FIRST_TURN -> script("turn=1", "humanlife=20", "ailife=20",
                "humanhand=Forest;Elvish Mystic;Grizzly Bears;Forest;Forest;Forest;Forest", "humanbattlefield=", "aibattlefield=")
            // Grizzly Bears ready to attack; the opponent has no creatures.
            TutorialPracticeScenarios.COMBAT -> script("turn=3", "humanlife=20", "ailife=20", "removesummoningsickness=true",
                "humanbattlefield=Grizzly Bears;Forest;Forest", "humanhand=Elvish Mystic;Forest;Forest;Forest;Forest;Forest",
                "aibattlefield=Island")
            // Grizzly Bears, two untapped Forests and Giant Growth in hand.
            TutorialPracticeScenarios.STACK -> script("turn=3", "humanlife=20", "ailife=20", "removesummoningsickness=true",
                "humanbattlefield=Grizzly Bears;Forest;Forest", "humanhand=Giant Growth;Elvish Mystic;Forest;Forest;Forest;Forest",
                "aibattlefield=Island")
            // A Commander table with something in every zone, for the lessons that explain the screen.
            TutorialPracticeScenarios.TOUR -> script("turn=3", "humanlife=40", "ailife=40", "removesummoningsickness=true",
                "humanbattlefield=Grizzly Bears|Counters:P1P1=1;Forest;Forest", "humanhand=Forest;Elvish Mystic;Giant Growth;Forest;Forest",
                "humangraveyard=Llanowar Elves", "humanexile=Forest",
                "humancommand=${TutorialPracticeScenarios.YOUR_COMMANDER}|IsCommander",
                "aicommand=${TutorialPracticeScenarios.OPPONENT_COMMANDER}|IsCommander", "aibattlefield=Island")
            else -> return null
        }
        val names = (you.deck + ai.deck + listOfNotNull(you.commander, ai.commander)).toSet()
        val registry = CardRegistry().apply { register(library.load(names)) }
        return TestScenarioGame(listOf(you, ai), registry, JSONObject().put("forgeBoard", board))
    }
}
