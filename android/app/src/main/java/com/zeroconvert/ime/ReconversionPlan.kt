package com.zeroconvert.ime

import com.zeroconvert.ime.assist.ReadingCheck
import com.zeroconvert.ime.azookey.input.ComposingText
import com.zeroconvert.ime.azookey.input.InputStyle

data class ReconversionPlan(val original: String, val reading: String, val candidates: List<Candidate>) {
    companion object {
        suspend fun create(
            selected: String,
            restore: (String) -> String? = if (BuildConfig.WINDOWS_ENGINE)
                com.zeroconvert.ime.engine.WindowsEngine::readingForSurface else ReadingCheck::readingForSurface,
            dictionaryCandidates: suspend (ComposingText) -> List<Candidate>
        ): ReconversionPlan? {
            if (selected.length !in 1..128 || selected.any { it.isISOControl() || it in '\uEE00'..'\uEE0F' }) return null
            val reading = restore(selected)?.takeIf { it.isNotBlank() && it.length <= 512 } ?: return null
            val input = ComposingText().apply { insertAtCursorPosition(reading, InputStyle.DIRECT) }
            val candidates = mutableListOf(Candidate(selected, KanaKanjiConverter.toKatakana(reading)))
            ConversionCandidateMerge.appendDistinct(candidates, dictionaryCandidates(input))
            return ReconversionPlan(selected, reading, candidates)
        }
    }
}
