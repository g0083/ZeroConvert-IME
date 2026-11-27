package com.zeroconvert.ime

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class HorizontalCursorDragTrackerTest {

    private class Recorder {
        val steps = mutableListOf<Int>()
        var dragStarts = 0
        val tracker = HorizontalCursorDragTracker(
            stepPx = 10f,
            startSlopPx = 5f,
            onStep = { steps += it },
            onDragStart = { dragStarts++ }
        )
    }

    @Test
    fun movementInsideSlopIsNotADrag() {
        val r = Recorder()
        r.tracker.begin(100f)
        r.tracker.move(104f)
        r.tracker.move(96f)
        assertFalse(r.tracker.isDragging)
        assertEquals(0, r.dragStarts)
        assertTrue(r.steps.isEmpty())
    }

    @Test
    fun eachStepSizeOfTravelMovesOneCharacterRight() {
        val r = Recorder()
        r.tracker.begin(100f)
        r.tracker.move(106f)
        assertTrue(r.tracker.isDragging)
        assertEquals(1, r.dragStarts)
        assertTrue(r.steps.isEmpty())
        r.tracker.move(110f)
        assertEquals(listOf(1), r.steps)
        r.tracker.move(131f)
        assertEquals(listOf(1, 1, 1), r.steps)
    }

    @Test
    fun largeJumpEmitsEveryCrossedCharacter() {
        val r = Recorder()
        r.tracker.begin(100f)
        r.tracker.move(140f)
        assertEquals(List(4) { 1 }, r.steps)
    }

    @Test
    fun leftDragEmitsLeftSteps() {
        val r = Recorder()
        r.tracker.begin(100f)
        r.tracker.move(70f)
        assertEquals(List(3) { -1 }, r.steps)
    }

    @Test
    fun reversingFingerReturnsCursorToSamePosition() {
        val r = Recorder()
        r.tracker.begin(100f)
        r.tracker.move(130f)
        r.tracker.move(100f)
        assertEquals(listOf(1, 1, 1, -1, -1, -1), r.steps)
    }

    @Test
    fun jitterWithinOneStepEmitsNothingAfterDragStart() {
        val r = Recorder()
        r.tracker.begin(100f)
        r.tracker.move(108f)
        r.tracker.move(103f)
        r.tracker.move(109f)
        assertTrue(r.steps.isEmpty())
        assertEquals(1, r.dragStarts)
    }

    @Test
    fun beginResetsDragState() {
        val r = Recorder()
        r.tracker.begin(100f)
        r.tracker.move(120f)
        r.tracker.begin(200f)
        assertFalse(r.tracker.isDragging)
        r.tracker.move(202f)
        assertFalse(r.tracker.isDragging)
        assertEquals(1, r.dragStarts)
    }
}
