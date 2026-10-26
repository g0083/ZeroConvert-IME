package com.zeroconvert.ime.assist

object AutoCorrectionEvidence {
    fun permits(
        input: String,
        surface: String,
        learned: LearnedMatch?,
        uniqueRepair: () -> String? = { if (AssistVocabulary.isReady) RomajiDecoder.uniqueAdjacentCorrection(input) else null }
    ): Boolean {
        if (input.isBlank() || surface.isBlank() || input.hasUnsafeTokens() || surface.hasUnsafeTokens()) return false
        if (learned?.reading == input && learned.surface == surface && learned.confirmedCount >= 3) return true
        if (AssistVocabulary.isKnownCleanReading(input)) return false
        val reading = uniqueRepair() ?: return false
        return surface == reading || surface == AssistVocabulary.surface(reading)
    }
}
