package com.zeroconvert.ime

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class FloatingKeyboardPlacementTest {
    @Test fun remembersRelativePositionWhenViewportChanges() {
        val placement = FloatingKeyboardPlacement().placedAt(
            left = 60, top = 450, minX = 10, maxX = 110, minY = 50, maxY = 550
        )

        assertEquals(120, placement.left(min = 20, max = 220))
        assertEquals(340, placement.top(min = 20, max = 420))
    }

    @Test fun clampsAtEdgesAndKeepsTheSavedPositionWhenThereIsNoRoomToMove() {
        val placement = FloatingKeyboardPlacement().placedAt(
            left = -40, top = 900, minX = 8, maxX = 90, minY = 26, maxY = 700
        )
        assertEquals(8, placement.left(min = 8, max = 120))
        assertEquals(800, placement.top(min = 28, max = 800))

        val pinned = placement.placedAt(
            left = 8, top = 28, minX = 8, maxX = 8, minY = 28, maxY = 28
        )
        assertEquals(placement, pinned)
    }

    @Test fun keepsWidthWithinBothTheResizeLimitsAndTheViewport() {
        assertEquals(640, FloatingKeyboardPlacement().widthPx(density = 2f, available = 1000))
        assertEquals(380, FloatingKeyboardPlacement().widthPx(density = 2f, available = 380))
        assertEquals(520, FloatingKeyboardPlacement(widthDp = 200f).widthPx(density = 2f, available = 1000))
        assertEquals(1000, FloatingKeyboardPlacement(widthDp = 900f).widthPx(density = 2f, available = 1000))
    }

    @Test fun oldPlacementsUseTheDefaultHeightAndNewPlacementsRestoreBothDimensions() {
        val old = FloatingKeyboardPlacement(widthDp = 300f)
        assertEquals(620, old.heightPx(density = 2f, available = 900, defaultHeightDp = 310f))
        val resized = old.copy(heightDp = 400f).placedAt(20, 20, 0, 100, 0, 100)
        assertEquals(600, resized.widthPx(density = 2f, available = 900))
        assertEquals(800, resized.heightPx(density = 2f, available = 900, defaultHeightDp = 310f))
        assertEquals(550, resized.heightPx(density = 2f, available = 550, defaultHeightDp = 310f))
    }

    @Test fun everyCornerKeepsItsOppositeCornerAnchored() {
        val expected = mapOf(
            FloatingKeyboardCorner.TOP_LEFT to (60..400 to 90..370),
            FloatingKeyboardCorner.TOP_RIGHT to (100..360 to 90..370),
            FloatingKeyboardCorner.BOTTOM_LEFT to (60..400 to 120..340),
            FloatingKeyboardCorner.BOTTOM_RIGHT to (100..360 to 120..340)
        )
        for ((corner, ranges) in expected) {
            assertEquals(ranges.first, resizeFloatingKeyboardAxis(100..400, 10..600, -40, 200, corner.left))
            assertEquals(ranges.second, resizeFloatingKeyboardAxis(120..370, 10..700, -30, 180, corner.top))
        }
    }

    @Test fun horizontalAndVerticalDragsChangeOnlyTheRequestedAxis() {
        val x = 100..400
        val y = 120..370
        assertEquals(100..480, resizeFloatingKeyboardAxis(x, 10..600, 80, 200, movesStart = false))
        assertEquals(y, resizeFloatingKeyboardAxis(y, 10..700, 0, 180, movesStart = false))
        assertEquals(x, resizeFloatingKeyboardAxis(x, 10..600, 0, 200, movesStart = true))
        assertEquals(70..370, resizeFloatingKeyboardAxis(y, 10..700, -50, 180, movesStart = true))
    }

    @Test fun sizeClampsToTheMinimumAndToEachViewportEdge() {
        assertEquals(200..400, resizeFloatingKeyboardAxis(100..400, 10..600, 300, 200, movesStart = true))
        assertEquals(100..300, resizeFloatingKeyboardAxis(100..400, 10..600, -300, 200, movesStart = false))
        assertEquals(10..400, resizeFloatingKeyboardAxis(100..400, 10..600, -300, 200, movesStart = true))
        assertEquals(100..600, resizeFloatingKeyboardAxis(100..400, 10..600, 400, 200, movesStart = false))
        assertEquals(10..190, resizeFloatingKeyboardAxis(10..190, 10..190, -100, 236, movesStart = false))
    }

    @Test fun docksOnlyWhenDraggedDownNearTheBottom() {
        assertFalse(shouldDockFloatingKeyboard(60f, 700f, viewportBottom = 800, snapDistance = 20))
        assertFalse(shouldDockFloatingKeyboard(0f, 800f, viewportBottom = 800, snapDistance = 20))
        assertFalse(shouldDockFloatingKeyboard(-10f, 789f, viewportBottom = 800, snapDistance = 20))
        assertTrue(shouldDockFloatingKeyboard(60f, 780f, viewportBottom = 800, snapDistance = 20))
    }
}
