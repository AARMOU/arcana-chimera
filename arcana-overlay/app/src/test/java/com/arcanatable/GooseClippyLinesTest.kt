package com.arcanatable

import org.junit.Assert.*
import org.junit.Test
import kotlin.random.Random

/** Regression gates for the owner's 100+ context-specific, nonrepeating, barely-helpful quips. */
class GooseClippyLinesTest {
    @Test fun libraryHasManyUniqueLinesAndEveryMomentIsCovered() {
        assertEquals(GooseMoment.values().toSet(),GooseClippyLines.lines.keys)
        assertTrue("At least 100 quips",GooseClippyLines.totalLines>=100)
        val all=GooseClippyLines.lines.values.flatten()
        assertEquals("Quips must not be duplicates",all.size,all.toSet().size)
        GooseMoment.values().forEach {moment->
            assertTrue("Category $moment needs enough choices to avoid a short loop",
                GooseClippyLines.lines.getValue(moment).size>=10)
        }
    }

    @Test fun fullShuffleBagExhaustsAllOptionsBeforeRepeating() {
        val chooser=GooseQuipDeck(Random(445))
        GooseMoment.values().forEach{moment->
            val count=GooseClippyLines.lines.getValue(moment).size
            val cycle1=List(count){chooser.next(moment)}
            val cycle2=List(count){chooser.next(moment)}
            assertEquals("No repeats during first cycle of $moment",count,cycle1.toSet().size)
            assertEquals("No repeats during second cycle of $moment",count,cycle2.toSet().size)
            assertEquals("No recent replay across the five-line cycle boundary of $moment",10,
                (cycle1.takeLast(5)+cycle2.take(5)).toSet().size)
        }
    }

    @Test fun repeatedPriorityCannotStickOnThreeLines() {
        val chooser=GooseQuipDeck(Random(17))
        val first12=List(12){chooser.next(GooseMoment.PRIORITY)}
        assertEquals(12,first12.toSet().size)
        val next12=List(12){chooser.next(GooseMoment.PRIORITY)}
        assertEquals(12,next12.toSet().size)
        assertNotEquals(first12.last(),next12.first())
    }

    @Test fun noticesMustMatchObservedRealStateChanges() {
        val base=GooseObservation(8,1,0,1,"UPKEEP",0,5,27,false)
        assertEquals(GooseMoment.WELCOME,GooseMomentDetector.change(null,base))
        assertNull(GooseMomentDetector.change(base,base))
        assertEquals(GooseMoment.PRIORITY,GooseMomentDetector.change(base,base.copy(prioritySeat=0)))
        assertEquals(GooseMoment.YOUR_TURN,GooseMomentDetector.change(base,base.copy(turn=9,activeSeat=0)))
        assertEquals(GooseMoment.OPPONENT_TURN,GooseMomentDetector.change(base,base.copy(turn=9,activeSeat=2)))
        assertEquals(GooseMoment.STACK,GooseMomentDetector.change(base,base.copy(stackSize=1)))
        assertEquals(GooseMoment.LIFE_LOSS,GooseMomentDetector.change(base,base.copy(life=20)))
        assertEquals(GooseMoment.LOW_LIFE,GooseMomentDetector.change(base,base.copy(life=10)))
        assertEquals(GooseMoment.LIFE_GAIN,GooseMomentDetector.change(base,base.copy(life=29)))
        assertEquals(GooseMoment.BIG_HAND,GooseMomentDetector.change(base,base.copy(handSize=8)))
        assertEquals(GooseMoment.EMPTY_HAND,GooseMomentDetector.change(base,base.copy(handSize=0)))
        assertEquals(GooseMoment.GAME_OVER,GooseMomentDetector.change(base,base.copy(gameOver=true)))
    }

    @Test fun phaseQuipsOnlyPretendTheLocalPlayerIsActiveWhenTrue() {
        val before=GooseObservation(7,0,0,0,"UPKEEP",0,4,40,false)
        assertEquals(GooseMoment.DRAW,GooseMomentDetector.change(before,before.copy(stepKey="DRAW")))
        assertEquals(GooseMoment.COMBAT,GooseMomentDetector.change(before,before.copy(stepKey="DECLARE_ATTACKERS")))
        assertEquals(GooseMoment.SECOND_MAIN,GooseMomentDetector.change(before,before.copy(stepKey="POSTCOMBAT_MAIN")))
        assertEquals(GooseMoment.END_STEP,GooseMomentDetector.change(before,before.copy(stepKey="END")))
        val opponent=before.copy(activeSeat=1)
        assertNull(GooseMomentDetector.change(opponent,opponent.copy(stepKey="DRAW")))
    }
}
