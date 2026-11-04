package com.zeroconvert.ime.assist

enum class AssistModel { PREDICTION, CORRECTOR }
data class ModelCompletion(val text: String, val stopReason: String) {
    val finished: Boolean get() = stopReason == "eos" || stopReason == "stop"
}

/** Implementations run only on a worker; tests can supply the exact native completion contract. */
interface AssistInference {
    val revision: Long
    fun complete(model: AssistModel, prompt: String, maxTokens: Int, timeoutMs: Int): ModelCompletion
}

internal fun String.codePointSize(): Int = codePointCount(0, length)
internal fun String.tailCodePoints(max: Int): String =
    if (codePointSize() <= max) this else substring(offsetByCodePoints(0, codePointSize() - max))
internal fun String.hasUnsafeTokens(): Boolean = any { it.isISOControl() || it in '\uEE00'..'\uEE0F' }

/** A terminal Latin letter, including n, can still be completed by the next romaji key. */
internal fun String.hasPendingRomaji(): Boolean = lastOrNull()?.let { it in 'a'..'z' || it in 'A'..'Z' } == true
