package com.zeroconvert.ime.assist

data class AssistUpdate(val selected: AiAssistResult?, val automaticCorrection: AiAssistResult? = null,
    val suggestions: List<AiAssistResult> = emptyList())

/** One immutable input snapshot. Once shown, a tap target stays fixed until the next edit. */
class AssistPresentation(private val policy: AssistPolicyTransport = SharedAssistPolicy.native) {
    private var predictionDone = false
    private var correctionDone = false
    private var prediction: AiAssistResult? = null
    private var correction: AiAssistResult? = null
    private var selected: AiAssistResult? = null

    fun complete(type: AiAssistType, result: AiAssistResult?, elapsedMs: Long = 0): AssistUpdate {
        if (type == AiAssistType.PREDICTION) {
            predictionDone = true
            prediction = result
        } else {
            correctionDone = true
            correction = result
        }
        return update(elapsedMs, result?.takeIf { type == AiAssistType.TYPO_CORRECTION && it.autoApply })
    }

    fun update(elapsedMs: Long, automatic: AiAssistResult? = null): AssistUpdate {
        if (correction == null && prediction == null) return AssistUpdate(selected)
        val ready = SharedAssistPolicy.ready(predictionDone, correctionDone, automatic != null, elapsedMs, policy)
        if (ready) selected = SharedAssistPolicy.choose(correction, prediction, selected, policy)
        return AssistUpdate(selected, automatic, if (ready) listOfNotNull(correction, prediction) else emptyList())
    }
}
