package com.zeroconvert.ime

/** Immutable selection captured before calling the editor, which may reenter the IME. */
internal data class ConversionCommitLearning(val reading: String, val surface: String,
                                           val engineCandidateId: String = "") {
    companion object {
        fun capture(
            reading: String,
            committedText: String,
            candidate: Candidate?,
            trailingText: String = "",
            sensitive: Boolean = false,
            correctionApplied: Boolean = false,
            canWrite: Boolean = LearningPolicy.canWrite
        ): ConversionCommitLearning? {
            if (!canWrite || sensitive || correctionApplied || reading.isEmpty()) return null
            val selected = candidate ?: return null
            if (!selected.isLearningTarget || selected.assist != null || selected.text.isEmpty()) return null
            if (committedText != selected.text + trailingText) return null
            if (KanaKanjiConverter.toHiragana(selected.reading) != KanaKanjiConverter.toHiragana(reading)) return null
            return ConversionCommitLearning(reading, selected.text, selected.engineCandidateId)
        }
    }
}
