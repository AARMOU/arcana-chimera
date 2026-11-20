package com.arcanatable

import kotlin.random.Random
import java.util.ArrayDeque

/**
 * GooseClippy's offline, event-grounded personality. A quip is chosen only from the event observed,
 * never by guessing what the player cast or what Forge decided.
 * Advice is intentionally unhelpfully helpful; no actual rules decisions are made here.
 */
internal enum class GooseMoment {
    WELCOME, PRIORITY, YOUR_TURN, OPPONENT_TURN, UPKEEP, DRAW, FIRST_MAIN, COMBAT,
    SECOND_MAIN, END_STEP, STACK, LIFE_LOSS, LIFE_GAIN, LOW_LIFE, BIG_HAND,
    EMPTY_HAND, HAND_CHANGE, GAME_OVER, GENERAL
}

internal data class GooseObservation(
    val turn:Int,
    val activeSeat:Int,
    val localSeat:Int,
    val prioritySeat:Int,
    val stepKey:String,
    val stackSize:Int,
    val handSize:Int?,
    val life:Int?,
    val gameOver:Boolean
)

/** Prioritizes genuinely observable game events. No observation = no line change. */
internal object GooseMomentDetector {
    fun change(before:GooseObservation?, after:GooseObservation):GooseMoment? {
        if(before==null)return GooseMoment.WELCOME
        if(before==after)return null
        if(!before.gameOver && after.gameOver)return GooseMoment.GAME_OVER
        if(before.life!=null && after.life!=null) {
            if(after.life<before.life && after.life<=10 && before.life>10)return GooseMoment.LOW_LIFE
            if(after.life<before.life)return GooseMoment.LIFE_LOSS
            if(after.life>before.life)return GooseMoment.LIFE_GAIN
        }
        if(after.stackSize>before.stackSize)return GooseMoment.STACK
        if(before.prioritySeat!=after.prioritySeat && after.prioritySeat==after.localSeat)
            return GooseMoment.PRIORITY
        if(before.turn!=after.turn || before.activeSeat!=after.activeSeat)
            return if(after.activeSeat==after.localSeat)GooseMoment.YOUR_TURN else GooseMoment.OPPONENT_TURN
        if(before.stepKey!=after.stepKey && after.activeSeat==after.localSeat) {
            val phase=after.stepKey.uppercase()
            return when {
                "UPKEEP" in phase -> GooseMoment.UPKEEP
                "DRAW" in phase -> GooseMoment.DRAW
                "POSTCOMBAT_MAIN" in phase || "MAIN2" in phase -> GooseMoment.SECOND_MAIN
                "MAIN" in phase -> GooseMoment.FIRST_MAIN
                "COMBAT" in phase || "ATTACK" in phase || "BLOCK" in phase -> GooseMoment.COMBAT
                "END" in phase || "CLEANUP" in phase -> GooseMoment.END_STEP
                else -> null
            }
        }
        if(before.handSize!=null && after.handSize!=null) {
            if(after.handSize>=8 && before.handSize<8)return GooseMoment.BIG_HAND
            if(after.handSize==0 && before.handSize>0)return GooseMoment.EMPTY_HAND
            if(after.handSize!=before.handSize)return GooseMoment.HAND_CHANGE
        }
        return null
    }
}

internal object GooseClippyLines {
    val lines:Map<GooseMoment,List<String>> = mapOf(
        GooseMoment.WELCOME to listOf(
            "It looks like you're playing Commander. Have you considered reading the cards?",
            "Welcome to Arcana XP. The help menu is available, should the obvious become elusive.",
            "I'm GooseClippy, a paperclip with a beak and very limited confidence in your sequencing.",
            "Your game loaded. Let's make sure the strategy does too.",
            "Four players, one table, and at least one ambitious misunderstanding of the rules.",
            "Helpful tip: the green Start button is not a land drop.",
            "Commander.exe has launched. I recommend clicking the cards you actually intend to play.",
            "I can assist with your turn. I'm told that's been necessary before.",
            "Remember: the objective is generally to win. A surprisingly useful starting point.",
            "Your desktop assistant has arrived. Somebody has to supervise the stack.",
            "If you're unsure what a card does, its text is a popular resource.",
            "The battlefield is ready. Your plan may need another minute."
        ),
        GooseMoment.PRIORITY to listOf(
            "You have priority. Responding now is easier than wishing you had later.",
            "Priority acquired. Check the stack before clicking Pass. Just a thought.",
            "You can act now. The interface has generously highlighted this for you.",
            "A response window is open. I recommend deciding whether you actually need to respond.",
            "Priority.exe is running. Reading the options is considered advanced technology.",
            "You have priority. Perhaps check the mana you left untapped.",
            "This is an opportunity to act, not an obligation to panic-click.",
            "Priority has reached you. The pass button will still be there after you think.",
            "The game is waiting for your decision. That makes two of us.",
            "You may respond. If you're holding interaction, now could be relevant.",
            "The stack might be empty, but the concept of timing still applies.",
            "Before passing priority, ask whether your future self will complain."
        ),
        GooseMoment.YOUR_TURN to listOf(
            "Your turn! Consider a land drop before the grand strategy unfolds.",
            "The active player is you. Might be worth checking your hand first.",
            "Welcome to your turn. A plan is optional but usually recommended.",
            "Turn ownership confirmed. Your opponents seem oddly optimistic.",
            "Your turn started. Perhaps count your mana before announcing greatness.",
            "You're the active player. Remember that casting everything isn't always ideal.",
            "Your turn has loaded. Check whether you can play a land.",
            "It's your turn. There's still time to sequence those spells correctly.",
            "The turn indicator says YOU. Try to make that an advantage.",
            "You have another turn. Excellent chance to learn from the last one.",
            "Your turn is live. GooseClippy recommends thinking before tapping.",
            "A fresh turn! Maybe this time the combat math gets a cameo."
        ),
        GooseMoment.OPPONENT_TURN to listOf(
            "Opponent turn. Keep an eye on the stack; surprises do happen.",
            "Someone else is acting. You can still check what interaction you have.",
            "Your opponent has the turn. A good moment to read their board.",
            "Other-player.exe is running. Consider leaving mana available if you planned to respond.",
            "Sit back and admire their sequencing. You might pick up a trick.",
            "The turn changed hands. You haven't necessarily lost the right to respond.",
            "You're not the active player. Instants remain an exciting discovery.",
            "An opponent's turn: the traditional time to remember your triggers.",
            "The waiting game is running. Watch for anything that targets you.",
            "Their turn is active. Maybe identify the scary permanent before it's lethal.",
            "Someone else has the spotlight. Your removal can still audition.",
            "Opponent turn detected. Pay attention; the board isn't decorative."
        ),
        GooseMoment.UPKEEP to listOf(
            "Upkeep detected. Triggered abilities may want your attention.",
            "Beginning upkeep. It's a fine time to remember what you control.",
            "Upkeep.exe is running. Check the effects that happen every turn.",
            "The upkeep phase is here. Delayed responsibilities have arrived on schedule.",
            "Upkeep: where forgotten triggers become educational experiences.",
            "You might review your upkeep triggers. The game certainly will.",
            "The morning paperwork phase has started. Please read the fine print.",
            "Upkeep is here. You can review active effects before getting ambitious.",
            "Maintenance window open. Your board state deserves a second glance.",
            "A fresh upkeep. Let's see whether anything you played actually matters now.",
            "Your triggers may be due. I suggest noticing them this time.",
            "Upkeep mode activated. Check first, honk second."
        ),
        GooseMoment.DRAW to listOf(
            "Draw step detected. Maybe the next card will clarify your plan.",
            "The draw phase has arrived. Remember that hand size is information.",
            "Draw step. The deck has prepared another opportunity for interpretation.",
            "One more card. Try comparing it with the mana you have.",
            "Your library is offering a surprise. Read it before celebrating.",
            "Card acquisition phase. Consider what you can actually cast.",
            "Draw step loaded. Perhaps don't immediately tap out without looking.",
            "Your hand may have changed. I'd recommend noticing which card.",
            "The draw phase is here. You can plan your main phase now.",
            "Topdeck theater is open. Please avoid calling every card a miracle.",
            "Draw step. New cardboard does not automatically mean new strategy.",
            "A card draw can improve your options. Emphasis on can."
        ),
        GooseMoment.FIRST_MAIN to listOf(
            "Main phase one. Consider your land drop before spending the rest.",
            "First main phase. You might want to plan around combat.",
            "The battlefield awaits. Maybe check the cost of your spells.",
            "Main phase activated. Mana math remains available free of charge.",
            "Precombat decisions. Try asking what you need untapped afterward.",
            "It's main phase one. Not every playable card needs to be played.",
            "The game is accepting sorcery-speed decisions. Timing is useful here.",
            "Main phase loaded. You could review the board before creating more board.",
            "You can build your position now. Preferably in a helpful direction.",
            "The spellcasting office is open. Your lands have limited working hours.",
            "First main phase. Count to the required mana value, then cast.",
            "I see the main phase is active. Perhaps start with your actual objective."
        ),
        GooseMoment.COMBAT to listOf(
            "Combat phase. Counting blockers before attacking is a classic technique.",
            "Warning: creatures may be assigned to attack. Check their power first.",
            "Battle.exe is running. Consider who survives your proposed attacks.",
            "Combat time. The math is usually more reliable than optimism.",
            "The pointy part of the turn has arrived. Review your attackers.",
            "Combat is on the schedule. You might check for summoning sickness.",
            "Attackers and blockers are about to matter. Yes, both.",
            "Combat detected. It's worth noticing who can block.",
            "The battlefield is active. Perhaps don't attack into obvious losses.",
            "If you're unsure whether to swing, counting damage is surprisingly effective.",
            "Combat phase. Your life total and theirs are different numbers for a reason.",
            "The violence tab is open. A little arithmetic might help."
        ),
        GooseMoment.SECOND_MAIN to listOf(
            "Second main phase. You can still play cards you sensibly held back.",
            "Postcombat phase. Maybe make the play that was safer after attacks.",
            "Main phase two. Check what mana remains before clicking anything.",
            "The second main phase has arrived. Review what combat changed.",
            "You survived combat. Now you can decide what to deploy.",
            "Your main phase is back for an encore. Try a coherent finale.",
            "Combat is behind us. The board may look different; take another look.",
            "Second main phase: a good moment to avoid leaving value unused.",
            "The table has entered its after-action review. You're invited.",
            "Postcombat.exe started. You can still develop your board.",
            "Main phase two. Don't forget you may have had a plan.",
            "This is where sequencing after combat can pay off. Fancy that."
        ),
        GooseMoment.END_STEP to listOf(
            "End step. Review any end-of-turn triggers before you disappear.",
            "The end phase is here. You might want to check your hand size.",
            "End step detected. Please review your unfinished business.",
            "The turn is closing. This is a fine time for instant-speed plans.",
            "End-step paperwork is due. Did you leave anything important open?",
            "Closing time. Look for effects that expire this turn.",
            "The last chapter of this turn is loading. Reading it helps.",
            "End step. Everything you meant to do earlier is now negotiating with timing rules.",
            "Shutdown sequence initiated. Your next turn will still remember this one.",
            "The game is tidying up. You should consider doing likewise.",
            "End phase reached. Some triggers may have opinions.",
            "The turn is packing up. Double-check whether you can still respond."
        ),
        GooseMoment.STACK to listOf(
            "The stack has grown. Read the top item before volunteering a response.",
            "Another item on the stack. Remember: last in, first out.",
            "Stack activity detected. You may want to see what resolves first.",
            "The stack is busy. Timing matters more than button enthusiasm.",
            "More stack activity. Perhaps check who or what is being targeted.",
            "The response queue has new business. No need to answer blindly.",
            "Stack item detected. Priority will determine who acts next.",
            "Something is waiting to resolve. You could inspect it first.",
            "The stack has a new entry. This might affect your excellent plan.",
            "Fresh stack activity. Maybe save that answer for the correct spell.",
            "The stack is doing stack things. It helps to read each one.",
            "Resolution pending. Pay attention to the order."
        ),
        GooseMoment.LIFE_LOSS to listOf(
            "Your life total decreased. You may want to keep track of how.",
            "Life lost. The next useful move might involve staying alive.",
            "Your health went down. Not every resource needs to be spent immediately.",
            "A life change was detected. Consider checking the board for repeat damage.",
            "The life counter fell. Defending is one possible response.",
            "Your health bar experienced budget cuts. Maybe reassess your blocks.",
            "Life total reduced. It can help to know what caused it.",
            "Your life points have left the building. Watch the next combat.",
            "Damage and life payments add up. Apparently faster than expected.",
            "The system detected lower life. Defensive planning may be useful.",
            "Life total down. Perhaps this is the moment to read that threatening card.",
            "Life lost. GooseClippy recommends a more sustainable strategy."
        ),
        GooseMoment.LIFE_GAIN to listOf(
            "Life gained! It may be worth checking what triggered that.",
            "Your life went up. Congratulations on understanding at least one interaction.",
            "Health increased. You can afford to think about the next threat.",
            "Life restored. Perhaps try to preserve some of it.",
            "Life gain detected. Good news, even if the game did most of the work.",
            "Your survivability is trending upward. A delightful novelty.",
            "The health bar received an update. Maybe investigate the source.",
            "Life increased! That is generally the preferred direction.",
            "Your life total has improved. Try not to spend it all at once.",
            "The numbers went up. I knew one of your plays would do that eventually.",
            "Life total improved. A chance to plan instead of panic.",
            "A lovely gain. Now let's see if you can make it matter."
        ),
        GooseMoment.LOW_LIFE to listOf(
            "Low life warning. Blocking is still a legal concept.",
            "Your health bar is low. Consider your opponent's next attack.",
            "The goose has detected a concerning shortage of life.",
            "Windows XP recommends reading the battlefield before passing.",
            "Low life. This may be a good time to count incoming damage.",
            "Survival.exe is requesting elevated privileges. Perhaps find interaction.",
            "Your life total needs a plan with fewer optimistic assumptions.",
            "This life total is making the paperclip nervous. Assess the threats.",
            "Emergency advisory: you may need to prioritize staying alive.",
            "The health counter is looking a little too minimalist.",
            "Your life reserves are low. Defensive creatures do have a purpose.",
            "Low life detected. Maybe don't treat the next combat as optional."
        ),
        GooseMoment.BIG_HAND to listOf(
            "That's quite a hand. Consider what you can actually use this turn.",
            "Your hand is crowded. Planning a discard pile already?",
            "Many cards detected. Try prioritizing the useful ones.",
            "You have options. This is often where analysis helps.",
            "Your hand may need a plan, not just more cards.",
            "A sizable hand. Reading each card would be a strong opening.",
            "Your inventory is full of possibilities and probable distractions.",
            "The hand count is high. Are you sure you know the cleanup rule?",
            "That's a lot of cardboard. Mana might become the limiting factor.",
            "Card advantage is nice. Playing them coherently is another skill.",
            "Your hand is well stocked. Consider saving responses for the right time.",
            "Congratulations on collecting cards. The next step is using them."
        ),
        GooseMoment.EMPTY_HAND to listOf(
            "Your hand is empty. Topdeck planning is now the entire strategy.",
            "No cards in hand. You may want to protect what you've already played.",
            "Your hand has reached zero. Future options are feeling streamlined.",
            "Empty hand detected. Maybe don't spend every card immediately next time.",
            "The hand zone is quiet. At least there's less to misread.",
            "No cards available in hand. Your board has become rather important.",
            "Resource warning: the hand inventory is empty.",
            "Your hand is clear. This is one way to simplify decision-making.",
            "The last card has left your hand. I assume that was intentional.",
            "Zero cards in hand. Card draw might be unusually attractive.",
            "No cards in hand. Timing your next draw will be exciting.",
            "Congratulations, you've solved the problem of having too many options."
        ),
        GooseMoment.HAND_CHANGE to listOf(
            "Your hand size changed. Worth checking which options are new.",
            "Hand update detected. You may want to revisit your plan.",
            "Something changed in your hand. Please don't make me name it for you.",
            "Your card inventory has changed. Review before the next click.",
            "Hand count updated. A small adjustment in strategy might help.",
            "You have a different number of cards now. This can matter.",
            "Your hand changed. I recommend noticing that before combat.",
            "Card resources updated. Perhaps check what's still available.",
            "Your hand just changed. Some old assumptions may be obsolete.",
            "Hand status revised. The paperclip suggests another look.",
            "Card count changed. Consider mana requirements before acting.",
            "The available options have shifted. You are permitted to adapt."
        ),
        GooseMoment.GAME_OVER to listOf(
            "Game over. Reviewing the final board is an excellent way to learn.",
            "The match has ended. GooseClippy recommends studying at least one decision.",
            "Commander.exe has exited. I hope you saved the lessons.",
            "Game finished. The help menu remains optimistic about your future.",
            "The final state is in. You could review the turns that mattered.",
            "The game is over. Perhaps next time you'll remember that trigger.",
            "Match complete. Don't worry, even excellent players reread cards.",
            "The scoreboard has spoken. GooseClippy declines to interpret the tone.",
            "The final turn has resolved. Time for a little constructive hindsight.",
            "Game over. Your strategic choices have been archived for educational purposes.",
            "The match ended. Consider keeping notes on what surprised you.",
            "Shutdown complete. The paperclip suggests another game and slightly more planning."
        ),
        GooseMoment.GENERAL to listOf(
            "I can help. Have you checked the text on the card?",
            "It looks like you're thinking. A promising development.",
            "A gentle reminder: untapped mana is often more useful than imaginary mana.",
            "Need a suggestion? Start by identifying the biggest threat.",
            "Try reading the stack from the top. It saves embarrassment.",
            "If something seems illegal, double-check the phase and timing.",
            "A helpful hint: there is more than one player at this table.",
            "I recommend considering your next turn before spending everything.",
            "Friendly tip: your commander is not automatically indestructible.",
            "Have you tried checking whether that creature can attack?",
            "A little combat math could make all this less surprising.",
            "I believe in your potential. The probability model is less certain."
        )
    )
    val totalLines:Int get()=lines.values.sumOf{it.size}
    init {
        require(lines.keys==GooseMoment.values().toSet()){"Missing GooseClippy events"}
        require(totalLines>=100){"GooseClippy needs at least 100 lines"}
        require(lines.values.flatten().distinct().size==totalLines){"GooseClippy lines must be distinct"}
    }
}

/**
 * Each category is dealt as a full shuffled deck before any line in that category repeats.
 * Recently used lines move to the end of the next shuffled deck. A 7-line global memory
 * avoids stale rotation even when events alternate quickly.
 */
internal class GooseQuipDeck(private val random:Random=Random.Default) {
    private val remaining=mutableMapOf<GooseMoment,ArrayDeque<String>>()
    private val recent=ArrayDeque<String>()
    private val historySize=7
    fun next(moment:GooseMoment):String {
        val bucket=remaining.getOrPut(moment){ArrayDeque()}
        if(bucket.isEmpty()) {
            val shuffled=GooseClippyLines.lines.getValue(moment).shuffled(random)
            val fresh=shuffled.filterNot{it in recent}
            val deferred=shuffled.filter{it in recent}
            (fresh+deferred).forEach{bucket.addLast(it)}
        }
        val next=bucket.removeFirst()
        recent.addLast(next)
        if(recent.size>historySize)recent.removeFirst()
        return next
    }
}
