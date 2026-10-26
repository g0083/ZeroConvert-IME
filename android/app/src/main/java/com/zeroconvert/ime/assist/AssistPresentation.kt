package com.zeroconvert.ime.assist

data class AssistUpdate(val selected: AiAssistResult?, val automaticCorrection: AiAssistResult? = null,
    val suggestions: List<AiAssistResult> = emptyList())

/** One immutable input snapshot. Once shown, a tap target stays fixed until the next edit. */
class AssistPresentation {
    private var predictionDone = false
    private var prediction: AiAssistResult? = null
    private var correction: AiAssistResult? = null
    private var selected: AiAssistResult? = null

    fun complete(type: AiAssistType, result: AiAssistResult?): AssistUpdate {
        if (type == AiAssistType.PREDICTION) {
            predictionDone = true
            prediction = result
        } else correction = result
        if (selected == null && predictionDone) selected = AiAssistManager.choose(correction, prediction)
        return AssistUpdate(selected, result?.takeIf { type == AiAssistType.TYPO_CORRECTION && it.autoApply },
            listOfNotNull(correction, prediction))
    }
}
