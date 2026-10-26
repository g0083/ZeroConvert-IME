package com.zeroconvert.ime

enum class KeyboardShiftMode { OFF, ONCE, LOCKED }

/** Immediate single-tap shift; a second tap within the platform interval enables Caps Lock. */
data class KeyboardShiftState(
    val mode: KeyboardShiftMode = KeyboardShiftMode.OFF,
    private val lastTapMillis: Long? = null
) {
    val isShifted: Boolean get() = mode != KeyboardShiftMode.OFF
    val isLocked: Boolean get() = mode == KeyboardShiftMode.LOCKED

    fun tap(uptimeMillis: Long, doubleTapTimeoutMillis: Long): KeyboardShiftState = when (mode) {
        KeyboardShiftMode.OFF -> KeyboardShiftState(KeyboardShiftMode.ONCE, uptimeMillis)
        KeyboardShiftMode.ONCE -> {
            val interval = lastTapMillis?.let { uptimeMillis - it }
            if (interval != null && interval in 0..doubleTapTimeoutMillis) {
                KeyboardShiftState(KeyboardShiftMode.LOCKED)
            } else KeyboardShiftState()
        }
        KeyboardShiftMode.LOCKED -> KeyboardShiftState()
    }

    fun afterCharacter(): KeyboardShiftState = if (mode == KeyboardShiftMode.ONCE) KeyboardShiftState() else this

    /** Use the current state even if a second key's label came from the previous Compose frame. */
    fun characterFor(key: Char): Char {
        if (key.isLetter()) return if (isShifted) key.uppercaseChar() else key.lowercaseChar()
        val shiftedIndex = SHIFTED_DIGITS.indexOf(key)
        val digit = if (shiftedIndex >= 0) DIGITS[shiftedIndex] else key
        val digitIndex = DIGITS.indexOf(digit)
        return if (isShifted && digitIndex >= 0) SHIFTED_DIGITS[digitIndex] else digit
    }

    private companion object {
        const val DIGITS = "1234567890"
        const val SHIFTED_DIGITS = "!@#$%^&*()"
    }
}
