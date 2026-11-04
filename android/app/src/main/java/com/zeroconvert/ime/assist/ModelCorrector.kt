package com.zeroconvert.ime.assist

import com.zeroconvert.ime.KanaKanjiConverter

class ModelCorrector(
    private val inference: AssistInference,
    private val sameReading: (String, String) -> Boolean = ReadingCheck::hasSameReading,
    private val fallback: (String) -> String? = { TypoCorrectionEngine.findCorrection(it) }
) {
    fun suggest(context: String, input: String, baseline: String): AiAssistResult? {
        if (input.hasPendingRomaji()) return null
        if (com.zeroconvert.ime.BuildConfig.WINDOWS_ENGINE) {
            return com.zeroconvert.ime.engine.WindowsEngine.assist("correction", context, input, baseline, inference)
        }
        if (input.codePointSize() !in 2..512 || input.hasUnsafeTokens()) return null
        var prefix = ""
        var target = input.trim()
        if (target.codePointSize() > 128) {
            val boundaries = "。、！？!?"
            val inputBoundary = target.indexOfLast { it in boundaries }
            val surfaceBoundary = baseline.indexOfLast { it in boundaries }
            if (inputBoundary < 0 || surfaceBoundary < 0 || target[inputBoundary] != baseline[surfaceBoundary] ||
                target.count { it in boundaries } != baseline.count { it in boundaries }) return null
            prefix = baseline.substring(0, surfaceBoundary + 1)
            target = target.substring(inputBoundary + 1)
        }
        if (target.codePointSize() !in 2..128) return null
        val learned = TypoLearning.lookup(target)
        val surface: String?
        if (learned != null) surface = learned.surface else {
            if (AssistVocabulary.isKnownCleanReading(KanaKanjiConverter.toHiragana(target))) return null
            val decoded = RomajiDecoder.decodeFloatingConsonantTypo(target)
            val modelInput = decoded ?: target
            val left = (context + prefix).filterNot { it in '\uEE00'..'\uEE0F' }
                .map { if (it == ' ' || it.isISOControl()) '　' else it }.joinToString("").tailCodePoints(512)
            val prompt = (if (left.isEmpty()) "" else "\uEE02$left") + "\uEE00${KanaKanjiConverter.toKatakana(modelInput)}\uEE01"
            val response = inference.complete(AssistModel.CORRECTOR, prompt, (modelInput.codePointSize() * 3 + 16).coerceIn(32, 384), 1200)
            if (response.stopReason in setOf("cancelled", "busy")) return null
            val completed = response.text.trim().takeIf {
                response.finished && it.isNotEmpty() && !it.hasUnsafeTokens() && it.codePointSize() <= modelInput.codePointSize() * 3 + 16
            }
            surface = if (decoded != null) {
                val expected = TypoCorrectionEngine.surfaceForReading(decoded)
                completed?.takeIf { it == expected || it == decoded } ?: expected
            } else if (completed != null) {
                if (KanaKanjiConverter.toHiragana(completed) == KanaKanjiConverter.toHiragana(target)) return null
                completed
            } else fallback(target)
        }
        val corrected = surface ?: return null
        if (corrected.isBlank() || corrected.hasUnsafeTokens() || corrected.codePointSize() > target.codePointSize() * 3 + 16) return null
        if (sameReading(corrected, target)) return null
        val full = prefix + corrected
        if (full == baseline || full == input) return null
        val autoApply = AutoCorrectionEvidence.permits(target, corrected, learned)
        return AiAssistResult(AiAssistType.TYPO_CORRECTION, full, "💡 $full",
            relevance = if (autoApply) 100 else if (learned != null) 70 else 30,
            sourceReading = input, autoApply = autoApply)
    }
}
