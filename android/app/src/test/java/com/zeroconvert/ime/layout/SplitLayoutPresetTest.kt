package com.zeroconvert.ime.layout

import com.zeroconvert.ime.QwertySplitLayout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SplitLayoutPresetTest {

    @Test
    fun qwertyPresetsHaveValidWeightsAndSplitCuts() {
        for (profile in KeyboardLayoutProfile.entries) {
            for (isJapanese in listOf(true, false)) {
                val spec = QwertyLayoutPresets.specFor(profile, isJapanese)
                assertEquals(10, spec.numberRowNormal.size)
                assertEquals(10, spec.numberRowShift.size)
                assertEquals(10, spec.row1.size)
                assertTrue(spec.row2.size in 9..10)
                assertEquals(spec.row2.size == 9, spec.row2Staggered)
                assertEquals(7, spec.row3.size)

                // Verify split cut indices match QwertySplitLayout.cutAfter
                val grid = spec.toKeyboardGridSpec(showNumberRow = false)
                assertEquals(4, grid.rows.size)
                assertEquals(QwertySplitLayout.cutAfter(spec.row1.size), grid.rows[0].splitCutAfter)
                assertEquals(QwertySplitLayout.cutAfter(spec.row2.size), grid.rows[1].splitCutAfter)
                assertEquals(QwertySplitLayout.cutAfter(spec.row3.size), grid.rows[2].splitCutAfter)

                // Verify bottom row left/right weight balance is positive and reasonable
                val leftWeight = spec.bottomLeft.sumOf { it.weight.toDouble() } + spec.splitSpaceWeight
                val rightWeight = spec.bottomRight.sumOf { it.weight.toDouble() } + spec.splitSpaceWeight
                assertTrue("Left split weight too small for $profile", leftWeight >= 3.5)
                assertTrue("Right split weight too small for $profile", rightWeight >= 3.5)
                assertTrue("Positive full space weight for $profile", spec.spaceWeight > 1.5f)
            }
        }
    }
}
