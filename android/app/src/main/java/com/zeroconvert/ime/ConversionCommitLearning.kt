package com.zeroconvert.ime

/** Immutable selection captured before calling the editor, which may reenter the IME. */
internal data class ConversionCommitLearning(val reading: String, val surface: String,
                                           val segments: List<DictionarySegment> = emptyList(),
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
            val segments = selected.segments.takeIf {
                it.isNotEmpty() && it.joinToString("") { span -> span.surface } == selected.text &&
                    KanaKanjiConverter.toHiragana(it.joinToString("") { span -> span.reading }) == KanaKanjiConverter.toHiragana(reading)
            }.orEmpty().map { it.copy(reading = KanaKanjiConverter.toKatakana(it.reading)) }
            return ConversionCommitLearning(reading, selected.text, segments, selected.engineCandidateId)
        }
    }
}
