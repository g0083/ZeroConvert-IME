package com.zeroconvert.ime

import com.zeroconvert.ime.assist.CorrectionSession

/** Immutable selection captured before calling the editor, which may reenter the IME. */
internal data class ConversionCommitLearning(val reading: String, val surface: String,
                                           val engineCandidateId: String = "",
                                           val explicitSelection: Boolean = false) {
    companion object {
        fun recordCorrections(
            accepted: List<CorrectionSession.Acceptance>,
            canRecord: () -> Boolean,
            record: (String, String) -> Unit
        ) {
            for ((reading, surface) in accepted.distinct()) {
                if (!canRecord()) return
                record(reading, surface)
            }
        }

        fun capture(
            reading: String,
            committedText: String,
            candidate: Candidate?,
            trailingText: String = "",
            sensitive: Boolean = false,
            correctionApplied: Boolean = false,
            canWrite: Boolean = LearningPolicy.canWrite,
            explicitSelection: Boolean = false
        ): ConversionCommitLearning? {
            if (!canWrite || sensitive || correctionApplied || reading.isEmpty()) return null
            val selected = candidate ?: return null
            if (!selected.isLearningTarget || selected.assist != null || selected.text.isEmpty()) return null
            if (committedText != selected.text + trailingText) return null
            if (KanaKanjiConverter.toHiragana(selected.reading) != KanaKanjiConverter.toHiragana(reading)) return null
            return ConversionCommitLearning(reading, selected.text, selected.engineCandidateId, explicitSelection)
        }
    }
}
