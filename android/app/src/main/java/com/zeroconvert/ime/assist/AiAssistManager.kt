package com.zeroconvert.ime.assist

enum class AiAssistType {
    TYPO_CORRECTION, // 💡 もしかして
    PREDICTION       // 🔮 先読み
}

data class AiAssistResult(
    val type: AiAssistType,
    val text: String,
    val label: String
)

object AiAssistManager {

    suspend fun requestAiAssist(
        rawInput: String,
        hiragana: String,
        firstSurface: String,
        leftContext: String,
        predictiveModelType: String = "rule_based"
    ): AiAssistResult? {
        val cleanHira = hiragana.trim()
        val cleanRaw = rawInput.trim()
        if (cleanHira.isEmpty() && cleanRaw.isEmpty()) return null

        // 1. Typo Correction has priority (💡 もしかして)
        val typoFix = TypoCorrectionEngine.findCorrection(
            rawInput = if (cleanHira.isNotEmpty()) cleanHira else cleanRaw,
            context = leftContext
        )
        if (typoFix != null && typoFix != firstSurface && typoFix != cleanHira) {
            return AiAssistResult(
                type = AiAssistType.TYPO_CORRECTION,
                text = typoFix,
                label = "💡 $typoFix"
            )
        }

        // 2. Predictive Assist (🔮 先読み)
        val prediction = PredictiveAssistEngine.findPrediction(
            currentSurface = firstSurface.ifEmpty { cleanHira },
            hiraganaReading = cleanHira,
            context = leftContext,
            modelType = predictiveModelType
        )
        if (prediction != null && prediction != firstSurface && prediction != cleanHira) {
            val displayLabel = if (prediction.length > 15) {
                prediction.take(14) + "…"
            } else {
                prediction
            }
            return AiAssistResult(
                type = AiAssistType.PREDICTION,
                text = prediction,
                label = "🔮 $displayLabel"
            )
        }

        return null
    }
}
