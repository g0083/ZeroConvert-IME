package com.zeroconvert.ime

/**
 * How a panel symbol/digit tap should reach the editor.
 *
 * Panel input differs from candidate selection: the character is not necessarily a
 * conversion result, so adopting it must not discard the reading that is still being
 * composed. Confirming first keeps the reading and appends the symbol through
 * [ZeroConvertInputMethodService.confirmAsync], which commits candidate and trailing
 * text exactly once so learning still sees only the candidate surface.
 */
enum class PanelTextCommitDecision { Direct, ConfirmThenAppend, AdoptCandidate }

object PanelTextCommit {
    /**
     * @param translationMode translation mode is active
     * @param committingTranslation the translation result itself is being committed
     * @param compositionEmpty no Japanese composition is pending
     * @param englishBufferNonEmpty the English composition buffer owns the pending text
     * @param candidateMatches the pending candidate list already contains this exact text
     */
    fun decide(
        translationMode: Boolean,
        committingTranslation: Boolean,
        compositionEmpty: Boolean,
        englishBufferNonEmpty: Boolean,
        candidateMatches: Boolean
    ): PanelTextCommitDecision {
        if (translationMode && !committingTranslation) return PanelTextCommitDecision.Direct
        if (compositionEmpty || englishBufferNonEmpty) return PanelTextCommitDecision.Direct
        if (candidateMatches) return PanelTextCommitDecision.AdoptCandidate
        return PanelTextCommitDecision.ConfirmThenAppend
    }
}