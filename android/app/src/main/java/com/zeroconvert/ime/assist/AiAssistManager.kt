package com.zeroconvert.ime.assist

import com.zeroconvert.ime.ZenzNative
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.selects.select
import kotlinx.coroutines.selects.onTimeout

enum class AiAssistType {
    TYPO_CORRECTION, // 💡 もしかして
    PREDICTION       // 🔮 先読み
}

data class AiAssistResult(
    val type: AiAssistType,
    val text: String,
    val label: String,
    val relevance: Int = 30,
    val sourceReading: String = "",
    val continuation: String = "",
    val autoApply: Boolean = false
)

object AiAssistManager {
    @Suppress("UNUSED_PARAMETER")
    suspend fun requestAiAssist(
        rawInput: String,
        hiragana: String,
        firstSurface: String,
        leftContext: String,
        predictiveModelType: String = "zerocon",
        allowCorrection: Boolean = true,
        inference: AssistInference = ZenzNative.assistSession(),
        allowPrediction: Boolean = true,
        policy: AssistPolicyTransport = SharedAssistPolicy.native
    ): AiAssistResult? = coroutineScope {
        val reading = hiragana.trim()
        if (reading.isEmpty()) return@coroutineScope null
        val plan = SharedAssistPolicy.requestPlan(allowCorrection, allowPrediction, false, reading, policy = policy)
        val correction = async(Dispatchers.IO) {
            if (plan.correction) ModelCorrector(inference).suggest(leftContext, reading, firstSurface) else null
        }
        val prediction = async(Dispatchers.IO) {
            if (plan.prediction) ModelPredictor(inference).suggest(leftContext, reading, firstSurface.ifEmpty { reading }) else null
        }
        choose(correction.await(), prediction.await(), policy)
    }

    fun choose(correction: AiAssistResult?, prediction: AiAssistResult?,
               policy: AssistPolicyTransport = SharedAssistPolicy.native): AiAssistResult? =
        SharedAssistPolicy.choose(correction, prediction, policy = policy)

    @OptIn(ExperimentalCoroutinesApi::class)
    suspend fun streamAiAssist(
        reading: String,
        baseline: String,
        context: String,
        allowCorrection: Boolean,
        inference: AssistInference = ZenzNative.assistSession(),
        baselineReading: String = "",
        policy: AssistPolicyTransport = SharedAssistPolicy.native,
        allowPrediction: Boolean = true,
        onUpdate: suspend (AssistUpdate) -> Unit
    ) = coroutineScope {
        val plan = SharedAssistPolicy.requestPlan(allowCorrection, allowPrediction, false,
            reading, baselineReading, policy)
        if (!plan.correction && !plan.prediction) return@coroutineScope
        val results = Channel<Pair<AiAssistType, AiAssistResult?>>(2)
        fun worker(type: AiAssistType, generate: () -> AiAssistResult?) = launch(Dispatchers.IO) {
            val result = try { generate() } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) { null }
            results.send(type to result)
        }
        worker(AiAssistType.TYPO_CORRECTION) {
            if (!plan.correction) {
                null
            } else {
                ModelCorrector(inference).suggest(context, reading, baseline, baselineReading)
            }
        }
        worker(AiAssistType.PREDICTION) {
            if (plan.prediction) ModelPredictor(inference).suggest(context, reading, baseline, baselineReading) else null
        }
        val presentation = AssistPresentation(policy)
        val presentationWaitMs = SharedAssistPolicy.presentationWaitMs(policy)
        val started = System.nanoTime()
        fun elapsedMs() = (System.nanoTime() - started) / 1_000_000
        try {
            var received = 0
            var waiting = true
            while (received < 2) {
                val remaining = (presentationWaitMs - elapsedMs()).coerceAtLeast(1)
                val next = if (waiting) select<Pair<AiAssistType, AiAssistResult?>?> {
                    results.onReceive { it }
                    onTimeout(remaining) { null }
                } else results.receive()
                val update = if (next == null) {
                    waiting = false
                    presentation.update(elapsedMs().coerceAtLeast(presentationWaitMs))
                } else {
                    received++
                    presentation.complete(next.first, next.second, elapsedMs())
                }
                if (update.selected != null) waiting = false
                onUpdate(update)
            }
        } finally { results.cancel() }
    }

}
