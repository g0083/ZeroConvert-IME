package com.zeroconvert.ime

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class KeyboardTouchLogicTest {

    @Test
    fun calculateFlickDirectionWithHysteresis_respectsSensitivityThreshold() {
        assertEquals(
            FlickDirection.CENTER,
            calculateFlickDirectionWithHysteresis(dx = -11.9f, dy = 0f, thresholdPx = 12f)
        )
        assertEquals(
            FlickDirection.LEFT,
            calculateFlickDirectionWithHysteresis(dx = -12f, dy = 0f, thresholdPx = 12f)
        )
        assertEquals(
            FlickDirection.CENTER,
            calculateFlickDirectionWithHysteresis(dx = 20f, dy = 0f, thresholdPx = 28f)
        )
        assertEquals(
            FlickDirection.RIGHT,
            calculateFlickDirectionWithHysteresis(dx = 28f, dy = 0f, thresholdPx = 28f)
        )
        assertEquals(
            FlickDirection.UP,
            calculateFlickDirectionWithHysteresis(dx = 0f, dy = -18f, thresholdPx = 18f)
        )
        assertEquals(
            FlickDirection.DOWN,
            calculateFlickDirectionWithHysteresis(dx = 0f, dy = 18f, thresholdPx = 18f)
        )
    }

    @Test
    fun calculateFlickDirectionWithHysteresis_diagonalHysteresisAndCenterReturn() {
        // Already in LEFT: near-diagonal upward tremor stays LEFT instead of flapping to UP.
        assertEquals(
            FlickDirection.LEFT,
            calculateFlickDirectionWithHysteresis(
                dx = -25f,
                dy = -26f,
                thresholdPx = 18f,
                currentDirection = FlickDirection.LEFT
            )
        )
        // Clearly vertical beyond hysteresis ratio switches to UP.
        assertEquals(
            FlickDirection.UP,
            calculateFlickDirectionWithHysteresis(
                dx = -10f,
                dy = -30f,
                thresholdPx = 18f,
                currentDirection = FlickDirection.LEFT
            )
        )
        // Return hysteresis keeps UP at 15px (> 20 * 0.72 = 14.4) and returns to CENTER at 14px.
        assertEquals(
            FlickDirection.UP,
            calculateFlickDirectionWithHysteresis(
                dx = 0f,
                dy = -15f,
                thresholdPx = 20f,
                currentDirection = FlickDirection.UP
            )
        )
        assertEquals(
            FlickDirection.CENTER,
            calculateFlickDirectionWithHysteresis(
                dx = 0f,
                dy = -14f,
                thresholdPx = 20f,
                currentDirection = FlickDirection.UP
            )
        )
    }

    @Test
    fun calculateQwertyUpFlickDirection_upwardConeAndReturnHysteresis() {
        assertEquals(
            FlickDirection.UP,
            calculateQwertyUpFlickDirection(dx = 15f, dy = -20f, thresholdPx = 20f)
        )
        // Steep sideways swipe does not trigger UP even with -threshold dy.
        assertEquals(
            FlickDirection.CENTER,
            calculateQwertyUpFlickDirection(dx = 40f, dy = -20f, thresholdPx = 20f)
        )
        // Hysteresis while already UP: retains UP until crossing back inside 0.72 * threshold.
        assertEquals(
            FlickDirection.UP,
            calculateQwertyUpFlickDirection(
                dx = 0f,
                dy = -15f,
                thresholdPx = 20f,
                currentDirection = FlickDirection.UP
            )
        )
        assertEquals(
            FlickDirection.CENTER,
            calculateQwertyUpFlickDirection(
                dx = 0f,
                dy = -14f,
                thresholdPx = 20f,
                currentDirection = FlickDirection.UP
            )
        )
    }

    @Test
    fun computeRepeatIntervalMs_acceleratesAfterThresholds() {
        assertEquals(50L, computeRepeatIntervalMs(repeatCount = 0))
        assertEquals(50L, computeRepeatIntervalMs(repeatCount = 5))
        assertEquals(35L, computeRepeatIntervalMs(repeatCount = 6))
        assertEquals(35L, computeRepeatIntervalMs(repeatCount = 15))
        assertEquals(22L, computeRepeatIntervalMs(repeatCount = 16))
        assertEquals(22L, computeRepeatIntervalMs(repeatCount = 40))
        assertEquals(18L, computeRepeatIntervalMs(repeatCount = 25, baseIntervalMs = 20L))
    }

    @Test
    fun qwertyTracker_exactHitAndNearestEdgeSnap() {
        val tracker = QwertyTouchTracker()
        tracker.replaceTargets(
            listOf(
                QwertyKeyTarget("q", KeyRect(0f, 0f, 60f, 70f), supportsUpFlick = true),
                QwertyKeyTarget("w", KeyRect(60f, 0f, 120f, 70f), supportsUpFlick = true),
                // Expanded side overhang hitbox simulating English Row 2 (1.5f weight)
                QwertyKeyTarget("a", KeyRect(0f, 70f, 90f, 140f), supportsUpFlick = false),
                QwertyKeyTarget("s", KeyRect(90f, 70f, 150f, 140f), supportsUpFlick = false)
            )
        )

        // Touch in left overhang region hits 'a'.
        val overhangHit = tracker.findTargetAt(x = 8f, y = 105f)
        assertNotNull(overhangHit)
        assertEquals("a", overhangHit?.id)

        // Touch slightly below the bottom border snaps to nearest edge key ('s').
        val bottomSnapHit = tracker.findTargetAt(x = 120f, y = 148f)
        assertNotNull(bottomSnapHit)
        assertEquals("s", bottomSnapHit?.id)
    }

    @Test
    fun qwertyTracker_dragReselectionAcrossKeysAndUpFlickLock() {
        val tracker = QwertyTouchTracker()
        tracker.replaceTargets(
            listOf(
                QwertyKeyTarget("q", KeyRect(0f, 0f, 60f, 70f), supportsUpFlick = true),
                QwertyKeyTarget("w", KeyRect(60f, 0f, 120f, 70f), supportsUpFlick = true),
                QwertyKeyTarget("a", KeyRect(0f, 70f, 90f, 140f), supportsUpFlick = false),
                QwertyKeyTarget("s", KeyRect(90f, 70f, 150f, 140f), supportsUpFlick = false)
            )
        )

        val off = KeyboardShiftState(KeyboardShiftMode.OFF)
        tracker.onPointerDown(pointerId = 1L, x = 45f, y = 100f, shiftState = off)
        assertEquals(setOf("a"), tracker.visualState.pressedKeyIds)

        // Slide horizontally from 'a' onto 's' -> switches active key before release.
        tracker.onPointerMove(pointerId = 1L, x = 110f, y = 100f, thresholdPx = 20f)
        assertEquals(setOf("s"), tracker.visualState.pressedKeyIds)
        assertEquals(
            listOf(QwertyTouchEffect.CommitKey("s", upFlick = false, longPress = false, shiftState = off)),
            tracker.onPointerUp(1L)
        )

        // Horizontal slide across top row ('q' -> 'w') reselects to 'w'.
        tracker.onPointerDown(pointerId = 2L, x = 30f, y = 40f, shiftState = off)
        tracker.onPointerMove(pointerId = 2L, x = 75f, y = 34f, thresholdPx = 20f)
        assertEquals(setOf("w"), tracker.visualState.pressedKeyIds)
        assertEquals(
            listOf(QwertyTouchEffect.CommitKey("w", upFlick = false, longPress = false, shiftState = off)),
            tracker.onPointerUp(2L)
        )

        // Top-row 'q' pulled upward into cone locks to 'q' even if drifting sideways over 'w'.
        tracker.onPointerDown(pointerId = 3L, x = 30f, y = 40f, shiftState = off)
        tracker.onPointerMove(pointerId = 3L, x = 75f, y = 12f, thresholdPx = 20f)
        assertEquals(setOf("q"), tracker.visualState.pressedKeyIds)
        assertEquals(FlickDirection.UP, tracker.visualState.previews[3L]?.flickDirection)
        assertEquals(
            listOf(QwertyTouchEffect.CommitKey("q", upFlick = true, longPress = false, shiftState = off)),
            tracker.onPointerUp(3L)
        )

        // Step-by-step upward pull then wide sideways drift into 'w' remains locked to 'q'.
        tracker.onPointerDown(pointerId = 4L, x = 30f, y = 40f, shiftState = off)
        tracker.onPointerMove(pointerId = 4L, x = 32f, y = 15f, thresholdPx = 20f)
        tracker.onPointerMove(pointerId = 4L, x = 105f, y = 12f, thresholdPx = 20f)
        assertEquals(setOf("q"), tracker.visualState.pressedKeyIds)
        assertEquals(
            listOf(QwertyTouchEffect.CommitKey("q", upFlick = true, longPress = false, shiftState = off)),
            tracker.onPointerUp(4L)
        )
    }

    @Test
    fun qwertyTracker_fastRolloverCommitsInDownOrderAndConsumesOnceShift() {
        val tracker = QwertyTouchTracker()
        tracker.replaceTargets(
            listOf(
                QwertyKeyTarget("k", KeyRect(100f, 70f, 160f, 140f)),
                QwertyKeyTarget("a", KeyRect(0f, 70f, 90f, 140f)),
                QwertyKeyTarget("t", KeyRect(120f, 0f, 180f, 70f), supportsUpFlick = true),
                QwertyKeyTarget("h", KeyRect(160f, 70f, 220f, 140f))
            )
        )

        val off = KeyboardShiftState(KeyboardShiftMode.OFF)
        val downK = tracker.onPointerDown(pointerId = 1L, x = 130f, y = 100f, shiftState = off)
        assertEquals(listOf(QwertyTouchEffect.HapticTick("k")), downK)

        // Press 'a' before releasing 'k': 'k' commits immediately on 'a' down, then 'a' ticks.
        val downA = tracker.onPointerDown(pointerId = 2L, x = 40f, y = 100f, shiftState = off)
        assertEquals(
            listOf(
                QwertyTouchEffect.CommitKey("k", upFlick = false, longPress = false, shiftState = off),
                QwertyTouchEffect.HapticTick("a")
            ),
            downA
        )

        // Lift 'a' then 'k': 'a' commits, 'k' does not commit twice.
        assertEquals(
            listOf(QwertyTouchEffect.CommitKey("a", upFlick = false, longPress = false, shiftState = off)),
            tracker.onPointerUp(2L)
        )
        assertEquals(emptyList<QwertyTouchEffect>(), tracker.onPointerUp(1L))
        assertTrue(tracker.visualState.pressedKeyIds.isEmpty())

        // ONCE shift rollover: 't' gets ONCE; rolling into 'h' while ONCE hasn't cleared yet forces 'h' to OFF.
        val once = KeyboardShiftState(KeyboardShiftMode.ONCE)
        tracker.onPointerDown(pointerId = 3L, x = 150f, y = 30f, shiftState = once)
        val downH = tracker.onPointerDown(pointerId = 4L, x = 180f, y = 100f, shiftState = once)
        assertEquals(
            listOf(
                QwertyTouchEffect.CommitKey("t", upFlick = false, longPress = false, shiftState = once),
                QwertyTouchEffect.HapticTick("h")
            ),
            downH
        )
        assertEquals(
            listOf(QwertyTouchEffect.CommitKey("h", upFlick = false, longPress = false, shiftState = off)),
            tracker.onPointerUp(4L)
        )
        tracker.onPointerUp(3L)

        // LOCKED shift preserves LOCKED across rolled-over keys.
        val locked = KeyboardShiftState(KeyboardShiftMode.LOCKED)
        tracker.onPointerDown(pointerId = 5L, x = 150f, y = 30f, shiftState = locked)
        val downHLocked = tracker.onPointerDown(pointerId = 6L, x = 180f, y = 100f, shiftState = locked)
        assertEquals(
            listOf(
                QwertyTouchEffect.CommitKey("t", upFlick = false, longPress = false, shiftState = locked),
                QwertyTouchEffect.HapticTick("h")
            ),
            downHLocked
        )
        assertEquals(
            listOf(QwertyTouchEffect.CommitKey("h", upFlick = false, longPress = false, shiftState = locked)),
            tracker.onPointerUp(6L)
        )
        tracker.onPointerUp(5L)
    }

    @Test
    fun qwertyTracker_flushAllPendingCommitsBeforeActionKey() {
        val tracker = QwertyTouchTracker()
        tracker.replaceTargets(
            listOf(
                QwertyKeyTarget("a", KeyRect(0f, 70f, 90f, 140f))
            )
        )
        val off = KeyboardShiftState(KeyboardShiftMode.OFF)
        tracker.onPointerDown(pointerId = 10L, x = 40f, y = 100f, shiftState = off)
        assertTrue(tracker.isTrackingPointer(10L))

        val flushed = tracker.flushAllPending()
        assertEquals(
            listOf(QwertyTouchEffect.CommitKey("a", upFlick = false, longPress = false, shiftState = off)),
            flushed
        )
        assertTrue(tracker.visualState.previews.isEmpty())
        assertEquals(emptyList<QwertyTouchEffect>(), tracker.onPointerUp(10L))
    }

    @Test
    fun qwertyTracker_upFlickAndLongPressShiftSnapshotPreserved() {
        val tracker = QwertyTouchTracker()
        tracker.replaceTargets(
            listOf(
                QwertyKeyTarget("q", KeyRect(0f, 0f, 60f, 70f), supportsUpFlick = true)
            )
        )

        val off = KeyboardShiftState(KeyboardShiftMode.OFF)
        tracker.onPointerDown(pointerId = 1L, x = 30f, y = 40f, shiftState = off)
        assertFalse(tracker.visualState.previews[1L]?.guideExpanded ?: true)

        // Long press expands the flick guide while keeping center direction and initial shift state.
        tracker.onLongPressTimeout(1L)
        val previewAfterLongPress = tracker.visualState.previews[1L]
        assertNotNull(previewAfterLongPress)
        assertTrue(previewAfterLongPress!!.guideExpanded)
        assertFalse(previewAfterLongPress.isShifted)
        assertEquals(FlickDirection.CENTER, previewAfterLongPress.flickDirection)

        // Simulate recomposition updating targets mid-gesture; down shift state remains OFF.
        tracker.replaceTargets(
            listOf(
                QwertyKeyTarget("q", KeyRect(0f, 0f, 60f, 70f), supportsUpFlick = true)
            )
        )
        assertEquals(
            listOf(QwertyTouchEffect.CommitKey("q", upFlick = false, longPress = true, shiftState = off)),
            tracker.onPointerUp(1L)
        )

        // Long press followed by upward flick commits upFlick = true + longPress = true.
        tracker.onPointerDown(pointerId = 2L, x = 30f, y = 40f, shiftState = off)
        tracker.onLongPressTimeout(2L)
        tracker.onPointerMove(pointerId = 2L, x = 30f, y = 10f, thresholdPx = 20f)
        assertEquals(
            listOf(QwertyTouchEffect.CommitKey("q", upFlick = true, longPress = true, shiftState = off)),
            tracker.onPointerUp(2L)
        )
    }

    @Test
    fun qwertyTracker_pointerCancelDiscardsWithoutCommit() {
        val tracker = QwertyTouchTracker()
        tracker.replaceTargets(
            listOf(
                QwertyKeyTarget("a", KeyRect(0f, 70f, 90f, 140f))
            )
        )

        val off = KeyboardShiftState(KeyboardShiftMode.OFF)
        tracker.onPointerDown(pointerId = 7L, x = 40f, y = 100f, shiftState = off)
        assertEquals(setOf("a"), tracker.visualState.pressedKeyIds)

        tracker.onPointerCancel(7L)
        assertFalse(tracker.isTrackingPointer(7L))
        assertTrue(tracker.visualState.pressedKeyIds.isEmpty())
        assertTrue(tracker.visualState.previews.isEmpty())
        assertEquals(emptyList<QwertyTouchEffect>(), tracker.onPointerUp(7L))
    }

    @Test
    fun oneHandedMode_fromNameFallsBackSafely() {
        assertEquals(OneHandedMode.OFF, OneHandedMode.fromName(null))
        assertEquals(OneHandedMode.OFF, OneHandedMode.fromName("UNKNOWN"))
        assertEquals(OneHandedMode.LEFT, OneHandedMode.fromName("LEFT"))
        assertEquals(OneHandedMode.RIGHT, OneHandedMode.fromName("RIGHT"))
    }

    @Test
    fun nextKeyPredictor_japaneseRomajiWeights_predictsVowelsAndDigraphsAndReturnsEmptyOnBoundary() {
        assertTrue(NextKeyPredictor.japaneseRomajiWeights("").isEmpty())
        assertEquals("", NextKeyPredictor.trailingPendingRomaji("わたし"))
        assertEquals("sh", NextKeyPredictor.trailingPendingRomaji("わたSH"))

        val sWeights = NextKeyPredictor.japaneseRomajiWeights("s")
        assertEquals(0.9f, sWeights['i'] ?: 0f, 0.01f)
        assertEquals(1.0f, sWeights['a'] ?: 0f, 0.01f)
        assertEquals(1.0f, sWeights['h'] ?: 0f, 0.01f)
        assertFalse(sWeights.containsKey('q'))

        val shWeights = NextKeyPredictor.japaneseRomajiWeights("sh")
        assertEquals(1.0f, shWeights['i'] ?: 0f, 0.01f)
        assertTrue((shWeights['a'] ?: 0f) >= 0.85f)
        assertFalse(shWeights.containsKey('k'))
    }

    @Test
    fun nextKeyPredictor_englishWords_ranksLikelyNextLettersByFrequency() {
        assertTrue(NextKeyPredictor.computeEnglishNextCharWeights("", listOf("the")).isEmpty())
        val weights = NextKeyPredictor.computeEnglishNextCharWeights(
            prefix = "th",
            words = listOf("the", "they", "there", "that", "think")
        )
        assertEquals(1.0f, weights['e'] ?: 0f, 0.001f)
        assertTrue((weights['e'] ?: 0f) > (weights['a'] ?: 0f))
        assertTrue((weights['a'] ?: 0f) > (weights['i'] ?: 0f))
        assertFalse(weights.containsKey('z'))
    }

    @Test
    fun qwertyTracker_dynamicNextKeyExpansion_boundedSymmetricAndSkipsCenters() {
        val tracker = QwertyTouchTracker()
        tracker.replaceTargets(
            listOf(
                QwertyKeyTarget("k_u", KeyRect(0f, 0f, 100f, 100f), primaryChar = 'u'),
                QwertyKeyTarget("k_i", KeyRect(100f, 0f, 200f, 100f), primaryChar = 'i')
            )
        )
        val off = KeyboardShiftState(KeyboardShiftMode.OFF)

        // Asymmetric boost: 'i' predicted (1.0f), 'u' not predicted (0.0f).
        // With maxExpandPx = 20f (< 100 * 0.25 = 25f), boundary shifts left by 20 / 2 = 10px (at x = 90).
        val weights = mapOf('i' to 1.0f)
        val borderAssistTarget = tracker.findTargetAt(
            x = 92f,
            y = 50f,
            nextCharWeights = weights,
            maxExpandPx = 20f
        )
        assertEquals("k_i", borderAssistTarget?.id)

        // Center of 'u' (x = 50) must NEVER be stolen by 'i', even with huge requested expansion.
        val centerTarget = tracker.findTargetAt(
            x = 50f,
            y = 50f,
            nextCharWeights = weights,
            maxExpandPx = 100f
        )
        assertEquals("k_u", centerTarget?.id)

        // Symmetric equal weights keep the border strictly at midpoint x = 100.
        val equalWeights = mapOf('u' to 1.0f, 'i' to 1.0f)
        assertEquals("k_u", tracker.findTargetAt(99f, 50f, nextCharWeights = equalWeights, maxExpandPx = 20f)?.id)
        assertEquals("k_i", tracker.findTargetAt(101f, 50f, nextCharWeights = equalWeights, maxExpandPx = 20f)?.id)

        // Initial pointer-down uses expansion; drag move re-selection uses nominal rectangles.
        tracker.onPointerDown(
            pointerId = 1L,
            x = 92f,
            y = 50f,
            shiftState = off,
            nextCharWeights = weights,
            maxExpandPx = 20f
        )
        assertEquals(setOf("k_i"), tracker.visualState.pressedKeyIds)
        tracker.onPointerMove(pointerId = 1L, x = 92f, y = 50f, thresholdPx = 18f)
        assertEquals(setOf("k_u"), tracker.visualState.pressedKeyIds)
    }

    @Test
    fun qwertyTracker_recordsTouchBiasNearBorderAndSuppressesOnCenterOrUpFlick() {
        val tracker = QwertyTouchTracker()
        tracker.replaceTargets(
            listOf(
                QwertyKeyTarget("k_s", KeyRect(0f, 0f, 100f, 100f), supportsUpFlick = true, primaryChar = 's'),
                QwertyKeyTarget("k_d", KeyRect(100f, 0f, 200f, 100f), supportsUpFlick = true, primaryChar = 'd')
            )
        )
        val off = KeyboardShiftState(KeyboardShiftMode.OFF)

        // Dead-center tap on 'd' -> no nearby ambiguity.
        tracker.onPointerDown(1L, x = 150f, y = 50f, shiftState = off)
        val centerCommit = tracker.onPointerUp(1L).single() as QwertyTouchEffect.CommitKey
        assertNotNull(centerCommit.touchBias)
        assertEquals('d', centerCommit.touchBias?.typedChar)
        assertTrue(centerCommit.touchBias!!.nearbyChars.isEmpty())

        // Near left border of 'd' (at x = 105, 5px from 's') -> 's' recorded with high confidence.
        tracker.onPointerDown(2L, x = 105f, y = 50f, shiftState = off)
        val borderCommit = tracker.onPointerUp(2L).single() as QwertyTouchEffect.CommitKey
        assertEquals('d', borderCommit.touchBias?.typedChar)
        assertEquals('s', borderCommit.touchBias?.nearbyChars?.firstOrNull()?.first)
        assertTrue((borderCommit.touchBias?.nearbyChars?.firstOrNull()?.second ?: 0f) > 0.7f)

        // Upward flick suppresses touchBias so secondary digit entry is never confused with a letter typo.
        tracker.onPointerDown(3L, x = 105f, y = 50f, shiftState = off)
        tracker.onPointerMove(3L, x = 105f, y = 15f, thresholdPx = 18f)
        val upFlickCommit = tracker.onPointerUp(3L).single() as QwertyTouchEffect.CommitKey
        assertTrue(upFlickCommit.upFlick)
        assertEquals(null, upFlickCommit.touchBias)
    }

    @Test
    fun shouldVibrateOnEditorDelete_vibratesOnlyWhenTextRemoved() {
        assertTrue(shouldVibrateOnEditorDelete(textBeforeCursor = "あ", deleteSurroundingAccepted = true))
        assertTrue(shouldVibrateOnEditorDelete(textBeforeCursor = "a", deleteSurroundingAccepted = false))
        assertFalse(shouldVibrateOnEditorDelete(textBeforeCursor = "", deleteSurroundingAccepted = true))
        assertFalse(shouldVibrateOnEditorDelete(textBeforeCursor = "", deleteSurroundingAccepted = false))
        assertTrue(shouldVibrateOnEditorDelete(textBeforeCursor = null, deleteSurroundingAccepted = true))
        assertFalse(shouldVibrateOnEditorDelete(textBeforeCursor = null, deleteSurroundingAccepted = false))
    }
}
