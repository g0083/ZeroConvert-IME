package com.zeroconvert.ime.layout

import com.zeroconvert.ime.FlickKeyDefinitions
import com.zeroconvert.ime.KeyboardMode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class LayoutPresetIntegrityTest {

    private val sampleJapaneseCenterRows = listOf(
        Triple(FlickKeyDefinitions.keyA, FlickKeyDefinitions.keyKa, FlickKeyDefinitions.keySa),
        Triple(FlickKeyDefinitions.keyTa, FlickKeyDefinitions.keyNa, FlickKeyDefinitions.keyHa),
        Triple(FlickKeyDefinitions.keyMa, FlickKeyDefinitions.keyYa, FlickKeyDefinitions.keyRa),
        Triple(FlickKeyDefinitions.keyMod, FlickKeyDefinitions.keyWa, FlickKeyDefinitions.keyPunc)
    )

    @Test
    fun profileIdsAreUniqueAndResolvable() {
        val ids = KeyboardLayoutProfile.entries.map { it.id }
        assertEquals(ids.size, ids.toSet().size)
        for (profile in KeyboardLayoutProfile.entries) {
            assertEquals(profile, KeyboardLayoutProfile.fromId(profile.id))
            assertEquals(profile, KeyboardLayoutProfile.fromId(profile.id.uppercase()))
        }
        assertEquals(KeyboardLayoutProfile.ZEROCON, KeyboardLayoutProfile.fromId(null))
        assertEquals(KeyboardLayoutProfile.ZEROCON, KeyboardLayoutProfile.fromId("unknown"))
    }

    @Test
    fun allFlickPresetsContainRequiredActionsAndUniqueIds() {
        for (profile in KeyboardLayoutProfile.entries) {
            for (mode in KeyboardMode.entries) {
                val flickSpec = FlickLayoutPresets.specFor(profile, mode)
                assertEquals(4, flickSpec.sideColumns.left.size)
                assertEquals(4, flickSpec.sideColumns.right.size)

                val grid = flickSpec.toKeyboardGridSpec(sampleJapaneseCenterRows)
                assertEquals(4, grid.rows.size)
                val allSlots = grid.allSlots()
                assertEquals(20, allSlots.size)

                val ids = allSlots.map { it.id }
                assertEquals("Duplicate slot IDs in $profile / $mode", ids.size, ids.toSet().size)

                val actions = allSlots.map { it.action }
                assertTrue("Missing Backspace in $profile / $mode", actions.any { it is KeyAction.Backspace })
                assertTrue("Missing Space in $profile / $mode", actions.any { it is KeyAction.Space })
                assertTrue("Missing Enter in $profile / $mode", actions.any { it is KeyAction.Enter })
                assertTrue("Missing ModeCycleAa1 in $profile / $mode", actions.any { it is KeyAction.ModeCycleAa1 })
                assertEquals(12, actions.count { it is KeyAction.FlickKey })
            }
        }
    }

    @Test
    fun allQwertyPresetsContainAllLettersRequiredActionsAndUniqueIds() {
        val expectedLetters = ('a'..'z').toSet()
        for (profile in KeyboardLayoutProfile.entries) {
            for (isJapanese in listOf(true, false)) {
                val spec = QwertyLayoutPresets.specFor(profile, isJapanese)
                val grid = spec.toKeyboardGridSpec(showNumberRow = true)
                assertEquals(5, grid.rows.size)

                val allSlots = grid.allSlots()
                val ids = allSlots.map { it.id }
                assertEquals("Duplicate slot IDs in QWERTY $profile / ja=$isJapanese", ids.size, ids.toSet().size)

                val actions = allSlots.map { it.action }
                assertTrue("Missing Shift in $profile", actions.any { it is KeyAction.Shift })
                assertTrue("Missing Backspace in $profile", actions.any { it is KeyAction.Backspace })
                assertTrue("Missing Space in $profile", actions.any { it is KeyAction.Space })
                assertTrue("Missing Enter in $profile", actions.any { it is KeyAction.Enter })
                assertTrue("Missing ModeCycleAa1 in $profile", actions.any { it is KeyAction.ModeCycleAa1 })

                val committedChars = actions.filterIsInstance<KeyAction.CommitChar>().map { it.normal }.toSet()
                assertTrue(
                    "Missing alphabet keys in $profile / ja=$isJapanese: ${expectedLetters - committedChars}",
                    committedChars.containsAll(expectedLetters)
                )
                assertEquals(
                    "QWERTY bottom-left key must always be ModeCycleAa1 in $profile / ja=$isJapanese",
                    KeyAction.ModeCycleAa1,
                    spec.bottomLeft.first().action
                )
            }
        }
    }

    @Test
    fun qwertyNumericModeAlwaysKeepsModeSwitchAtBottomLeft() {
        for (profile in KeyboardLayoutProfile.entries) {
            val numericSpec = FlickLayoutPresets.specFor(
                profile = profile,
                mode = KeyboardMode.NUMERIC,
                keepModeSwitchAtBottomLeft = true
            )
            assertEquals(
                "Numeric bottom-left key must stay ModeCycleAa1 when opened from QWERTY ($profile)",
                KeyAction.ModeCycleAa1,
                numericSpec.sideColumns.left[3].action
            )
        }
    }
}
