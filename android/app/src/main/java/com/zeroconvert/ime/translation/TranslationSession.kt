package com.zeroconvert.ime.translation

/**
 * Generation guard for asynchronous translation work. A result may only be
 * applied while its token is still current, so a newer keystroke, a mode exit,
 * or a new selection invalidates older native work.
 */
internal class TranslationSession {
    private var generation = 0L

    fun begin(): Long {
        generation += 1
        return generation
    }

    fun cancel() {
        generation += 1
    }

    fun isCurrent(token: Long): Boolean = token == generation
}
