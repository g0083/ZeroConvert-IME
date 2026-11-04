package com.zeroconvert.ime.assist

import com.zeroconvert.ime.KanaKanjiConverter
import com.zeroconvert.ime.ZenzNative

class ModelPredictor(private val inference: AssistInference, private val restore: (String) -> String? = ReadingCheck::readingForSurface) {
    private fun useful(text: String) = text.codePointSize() in 2..48 && !text.hasUnsafeTokens() && text.any { it.isLetterOrDigit() }

    fun suggest(context: String, reading: String, surface: String): AiAssistResult? {
        if (reading.hasPendingRomaji()) return null
        if (com.zeroconvert.ime.BuildConfig.WINDOWS_ENGINE) {
            return com.zeroconvert.ime.engine.WindowsEngine.assist("prediction", context, reading, surface, inference)
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
        if (!conversion.finished || !completed.startsWith(surface)) return null
        val suffix = completed.removePrefix(surface)
        if (!useful(suffix) || suffix.contains(surface)) return null
        if (listOf("この記事", "その記事", "上記", "前述").any { it in suffix && it !in context && it !in surface }) return null
        return AiAssistResult(AiAssistType.PREDICTION, surface + suffix, "🔮 ${(surface + suffix).take(30)}", 60, reading, suffix)
    }
}

object PredictiveAssistEngine {
    @Suppress("UNUSED_PARAMETER")
    fun findPrediction(currentSurface: String, hiraganaReading: String, context: String, modelType: String = "zerocon"): String? =
        ModelPredictor(ZenzNative.assistSession()).suggest(context, hiraganaReading, currentSurface)?.text
}
