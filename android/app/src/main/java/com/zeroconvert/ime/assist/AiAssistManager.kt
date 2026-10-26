package com.zeroconvert.ime.assist

import com.zeroconvert.ime.ZenzNative
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.CancellationException

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
        inference: AssistInference = ZenzNative.assistSession()
    ): AiAssistResult? = coroutineScope {
        val reading = hiragana.trim()
        if (reading.isEmpty()) return@coroutineScope null
        val correction = async(Dispatchers.IO) {
            if (allowCorrection) ModelCorrector(inference).suggest(leftContext, reading, firstSurface) else null
        }
        val prediction = async(Dispatchers.IO) {
            ModelPredictor(inference).suggest(leftContext, reading, firstSurface.ifEmpty { reading })
        }
        choose(correction.await(), prediction.await())
    }

    fun choose(correction: AiAssistResult?, prediction: AiAssistResult?): AiAssistResult? =
        listOfNotNull(correction, prediction).maxWithOrNull(compareBy<AiAssistResult> { it.relevance }
            .thenBy { it.type == AiAssistType.PREDICTION })

    suspend fun streamAiAssist(
        reading: String,
        baseline: String,
        context: String,
        allowCorrection: Boolean,
        inference: AssistInference = ZenzNative.assistSession(),
        onUpdate: suspend (AssistUpdate) -> Unit
    ) = coroutineScope {
        val results = Channel<Pair<AiAssistType, AiAssistResult?>>(2)
        fun worker(type: AiAssistType, generate: () -> AiAssistResult?) = launch(Dispatchers.IO) {
            val result = try { generate() } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) { null }
            results.send(type to result)
        }
        worker(AiAssistType.TYPO_CORRECTION) {
            if (allowCorrection) ModelCorrector(inference).suggest(context, reading, baseline) else null
        }
        worker(AiAssistType.PREDICTION) { ModelPredictor(inference).suggest(context, reading, baseline) }
        val presentation = AssistPresentation()
        try {
            repeat(2) {
                val (type, result) = results.receive()
                onUpdate(presentation.complete(type, result))
            }
        } finally { results.cancel() }
    }
}
