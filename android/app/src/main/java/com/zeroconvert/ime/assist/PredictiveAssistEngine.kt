package com.zeroconvert.ime.assist

import com.zeroconvert.ime.KanaKanjiConverter
import com.zeroconvert.ime.ZenzNative

class ModelPredictor(private val inference: AssistInference, private val restore: (String) -> String? = ReadingCheck::readingForSurface) {
    private fun useful(text: String) = isUsefulPredictionText(text)

    fun suggest(context: String, reading: String, surface: String, baselineReading: String = ""): AiAssistResult? {
        if (reading.hasPendingRomaji()) return null
        if (com.zeroconvert.ime.BuildConfig.WINDOWS_ENGINE) {
            return com.zeroconvert.ime.engine.WindowsEngine.assist("prediction", context, reading, surface, inference, baselineReading)
        }
        if (surface.codePointSize() < 2 || reading.codePointSize() !in 1..512 ||
            context.any { it in '\uEE00'..'\uEE0F' } || (reading + surface).hasUnsafeTokens()) return null
        val modelReading = if (reading.any { it in '\u3400'..'\u9FFF' }) restore(surface) ?: return null else reading
        val left = context.map { if (it == ' ' || it.isISOControl()) '　' else it }.joinToString("").tailCodePoints(256)
        val target = KanaKanjiConverter.toKatakana(modelReading)
        val extension = inference.complete(AssistModel.PREDICTION, "\uEE02$left\uEE00$target", 24, 800)
        if (extension.stopReason in setOf("cancelled", "busy", "error", "unavailable", "timeout")) return null
        val generated = extension.text.trim()
        val boundary = generated.indices.firstOrNull { index -> generated[index] in "、。！？!?" && generated.take(index).count { it.isLetterOrDigit() } >= 2 }
        val continuation = if (boundary != null) generated.take(boundary + 1) else generated.takeIf { extension.finished } ?: return null
        if (!useful(continuation) || !continuation.all { it in 'ぁ'..'ゖ' || it in 'ァ'..'ヶ' || it in "ー、。！？!?・" }) return null
        val extended = target + KanaKanjiConverter.toKatakana(continuation)
        val conversion = inference.complete(AssistModel.PREDICTION, "\uEE02$left\uEE00$extended\uEE01", (extended.codePointSize() * 3 + 16).coerceIn(32, 256), 1000)
        val completed = conversion.text.trim()
        if (!conversion.finished) return null
        val suffix = validatePredictionSuffix(context, surface, completed) ?: return null
        return AiAssistResult(AiAssistType.PREDICTION, surface + suffix, "🔮 ${(surface + suffix).take(30)}", 60, reading, suffix)
    }
}

object PredictiveAssistEngine {
    @Suppress("UNUSED_PARAMETER")
    fun findPrediction(currentSurface: String, hiraganaReading: String, context: String, modelType: String = "zerocon"): String? =
        ModelPredictor(ZenzNative.assistSession()).suggest(context, hiraganaReading, currentSurface)?.text
}

private fun isUsefulPredictionText(text: String) =
    text.codePointSize() in 2..48 && !text.hasUnsafeTokens() && text.any { it.isLetterOrDigit() }

internal fun validatePredictionSuffix(context: String, surface: String, completed: String): String? {
    val trimmed = completed.trim()
    if (!trimmed.startsWith(surface)) return null
    val suffix = trimmed.removePrefix(surface)
    if (!isUsefulPredictionText(suffix) || suffix.contains(surface) || repeatsSurfaceClause(surface, suffix)) return null
    if (listOf("この記事", "その記事", "上記", "前述").any { it in suffix && it !in context && it !in surface }) return null
    return suffix
}

private val PREDICTION_CLAUSE_PUNCTUATION = setOf(
    '、', '。', '！', '？', '!', '?', '，', '．', ',', '.', ' ', '　', '\t', '\n', '\r'
)

internal fun repeatsSurfaceClause(surface: String, suffix: String): Boolean {
    val trimmedSurface = surface.trimEnd { it in PREDICTION_CLAUSE_PUNCTUATION }
    val cleanSuffix = suffix.trimStart { it in PREDICTION_CLAUSE_PUNCTUATION }
    if (trimmedSurface.isEmpty() || cleanSuffix.isEmpty()) return false
    for (idx in trimmedSurface.indices) {
        val tail = trimmedSurface.substring(idx)
        if (tail.length >= 4 && cleanSuffix.startsWith(tail)) return true
    }
    return suffix.split(*PREDICTION_CLAUSE_PUNCTUATION.toCharArray()).any { piece ->
        val trimmedPiece = piece.trim()
        trimmedPiece.length >= 4 && trimmedSurface.contains(trimmedPiece)
    }
}
