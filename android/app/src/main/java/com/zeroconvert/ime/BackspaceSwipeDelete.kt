package com.zeroconvert.ime

import kotlin.math.floor

/**
 * Pure-Kotlin state machine for Gboard-style Backspace swipe-left batch deletion.
 *
 * Dragging left past [activationThresholdPx] activates swipe-delete mode (cancelling
 * tap/auto-repeat). Further leftward movement increments [steps]; dragging back toward
 * the origin decrements [steps] and returns to 0 to cancel cleanly before release.
 */
class BackspaceSwipeDeleteTracker(
    private val originX: Float,
    private val activationThresholdPx: Float,
    private val stepPx: Float
) {
    var isActive: Boolean = false
        private set

    var steps: Int = 0
        private set

    /**
     * Updates horizontal pointer position. Returns the new step count when it changes
     * (including initial activation at 1 or returning to 0), or `null` if unchanged.
     */
    fun move(currentX: Float): Int? {
        val safeActivation = activationThresholdPx.coerceAtLeast(1f)
        val safeStep = stepPx.coerceAtLeast(1f)
        val leftDragPx = originX - currentX

        if (!isActive) {
            if (leftDragPx < safeActivation) return null
            isActive = true
        }

        val nextSteps = if (leftDragPx < safeActivation * 0.5f) {
            0
        } else {
            val extra = (leftDragPx - safeActivation).coerceAtLeast(0f)
            1 + floor(extra / safeStep).toInt()
        }

        if (nextSteps == steps) return null
        steps = nextSteps
        return nextSteps
    }
}

/**
 * Segments text before the cursor into natural Japanese/English deletion chunks for
 * Backspace swipe-left deletion.
 */
object WordDeleteSegmenter {
    private enum class CharClass {
        SPACE,
        NEWLINE,
        ASCII_WORD,
        HIRAGANA,
        KATAKANA,
        KANJI,
        OTHER
    }

    private fun classify(ch: Char): CharClass = when {
        ch == '\n' || ch == '\r' -> CharClass.NEWLINE
        ch.isWhitespace() -> CharClass.SPACE
        ch in 'a'..'z' || ch in 'A'..'Z' || ch in '0'..'9' || ch == '_' || ch == '\'' -> CharClass.ASCII_WORD
        ch in '\u3040'..'\u309F' -> CharClass.HIRAGANA
        ch in '\u30A0'..'\u30FF' || ch == 'ー' -> CharClass.KATAKANA
        ch in '\u4E00'..'\u9FFF' || ch in '\u3400'..'\u4DBF' || ch == '々' -> CharClass.KANJI
        else -> CharClass.OTHER
    }

    /**
     * Returns the number of trailing characters in [textBeforeCursor] to delete for [steps]
     * leftward swipe steps. Returns `0` when [steps] <= 0 or [textBeforeCursor] is empty.
     */
    fun deletionLengthForSteps(textBeforeCursor: String, steps: Int): Int {
        if (steps <= 0 || textBeforeCursor.isEmpty()) return 0
        var pos = textBeforeCursor.length
        var remainingSteps = steps

        while (remainingSteps > 0 && pos > 0) {
            // Skip trailing horizontal spaces into the preceding word on the same step,
            // matching Gboard's word-delete feel ("hello world " -> deletes "world ").
            val initialClass = classify(textBeforeCursor[pos - 1])
            if (initialClass == CharClass.NEWLINE) {
                pos--
                if (pos > 0 && textBeforeCursor[pos] == '\n' && textBeforeCursor[pos - 1] == '\r') {
                    pos--
                }
                remainingSteps--
                continue
            }

            if (initialClass == CharClass.SPACE) {
                while (pos > 0 && classify(textBeforeCursor[pos - 1]) == CharClass.SPACE) {
                    pos--
                }
                if (pos == 0 || classify(textBeforeCursor[pos - 1]) == CharClass.NEWLINE) {
                    remainingSteps--
                    continue
                }
            }

            val wordClass = classify(textBeforeCursor[pos - 1])
            if (wordClass == CharClass.OTHER) {
                // Consume consecutive punctuation/symbols (or single surrogate pair) as one chunk.
                while (pos > 0 && classify(textBeforeCursor[pos - 1]) == CharClass.OTHER) {
                    pos--
                }
            } else {
                while (pos > 0 && classify(textBeforeCursor[pos - 1]) == wordClass) {
                    pos--
                }
            }
            remainingSteps--
        }

        // Avoid splitting a UTF-16 surrogate pair at the boundary.
        if (pos in 1 until textBeforeCursor.length &&
            Character.isLowSurrogate(textBeforeCursor[pos]) &&
            Character.isHighSurrogate(textBeforeCursor[pos - 1])
        ) {
            pos--
        }

        return (textBeforeCursor.length - pos).coerceIn(0, textBeforeCursor.length)
    }
}
