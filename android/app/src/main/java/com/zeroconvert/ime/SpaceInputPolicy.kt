package com.zeroconvert.ime

/**
 * Resolves the space character inserted by the space key based on the active
 * keyboard mode and the user's "always half-width space" preference.
 *
 * Default behavior (`alwaysHalfwidth == false`):
 * - Japanese mode -> full-width ideographic space (`\u3000`)
 * - English / Numeric mode -> ASCII half-width space (` `)
 */
object SpaceInputPolicy {
    const val HALFWIDTH_SPACE = " "
    const val FULLWIDTH_SPACE = "\u3000"

    fun spaceText(mode: KeyboardMode, alwaysHalfwidth: Boolean): String {
        if (alwaysHalfwidth) return HALFWIDTH_SPACE
        return when (mode) {
            KeyboardMode.JAPANESE -> FULLWIDTH_SPACE
            KeyboardMode.ENGLISH, KeyboardMode.NUMERIC -> HALFWIDTH_SPACE
        }
    }
}
