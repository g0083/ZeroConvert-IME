package com.zeroconvert.ime

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class KeyboardShiftStateTest {
    private fun type(initial: KeyboardShiftState, keys: String): Pair<String, KeyboardShiftState> {
        var state = initial
        val text = buildString {
            for (key in keys) {
                append(state.characterFor(key))
                state = state.afterCharacter()
            }
        }
        return text to state
    }

    @Test fun singleTapUppercasesOnlyTheNextCharacter() {
        val once = KeyboardShiftState().tap(100, 300)
        assertTrue(once.isShifted)
        assertFalse(once.isLocked)
        val (text, after) = type(once, "ab")
        assertEquals("Ab", text)
        assertEquals(KeyboardShiftMode.OFF, after.mode)
    }

    @Test fun quickDoubleTapLocksAcrossCharacters() {
        val locked = KeyboardShiftState().tap(100, 300).tap(200, 300)
        assertTrue(locked.isLocked)
        val (text, after) = type(locked, "abc")
        assertEquals("ABC", text)
        assertTrue(after.isLocked)
    }

    @Test fun tappingLockedShiftUnlocksAndStartsAFreshTapSequence() {
        val off = KeyboardShiftState().tap(100, 300).tap(200, 300).tap(250, 300)
        assertFalse(off.isShifted)
        assertEquals(KeyboardShiftMode.ONCE, off.tap(300, 300).mode)
    }

    @Test fun slowSecondTapCancelsOneShotInsteadOfLocking() {
        val off = KeyboardShiftState().tap(100, 300).tap(401, 300)
        assertFalse(off.isShifted)
    }

    @Test fun doubleTapUsesTheProvidedPlatformTimeout() {
        assertTrue(KeyboardShiftState().tap(100, 100).tap(200, 100).isLocked)
        assertFalse(KeyboardShiftState().tap(100, 100).tap(201, 100).isShifted)
    }

    @Test fun typingBetweenShiftTapsDoesNotAccidentallyLock() {
        val (_, off) = type(KeyboardShiftState().tap(100, 300), "a")
        val (text, after) = type(off.tap(200, 300), "bc")
        assertEquals("Bc", text)
        assertFalse(after.isLocked)
    }

    @Test fun aFirstTapAtZeroUptimeCanStillBeDoubleTapped() {
        assertTrue(KeyboardShiftState().tap(0, 300).tap(50, 300).isLocked)
    }

    @Test fun aBackwardsTimestampDoesNotEnableCapsLock() {
        assertFalse(KeyboardShiftState().tap(100, 300).tap(99, 300).isShifted)
    }

    @Test fun rapidLettersUseCurrentShiftEvenWithStaleUppercaseLabels() {
        val (text, _) = type(KeyboardShiftState().tap(100, 300), "AB")
        assertEquals("Ab", text)
    }

    @Test fun shiftedNumberRowIsOneShotAndRecoversStaleSymbolLabels() {
        assertEquals("@3", type(KeyboardShiftState().tap(100, 300), "@#").first)
        val locked = KeyboardShiftState().tap(100, 300).tap(150, 300)
        assertEquals("!@", type(locked, "12").first)
        assertEquals("12", type(KeyboardShiftState(), "12").first)
    }

    @Test fun punctuationConsumesOneShotButCapsLockSurvivesSpace() {
        assertEquals(".a", type(KeyboardShiftState().tap(100, 300), ".a").first)
        val locked = KeyboardShiftState().tap(100, 300).tap(150, 300)
        assertTrue(type(locked, " ").second.isLocked)
    }
}
