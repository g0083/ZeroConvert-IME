package com.zeroconvert.ime.translation

import com.zeroconvert.ime.ZenzNative
import com.zeroconvert.ime.assist.ModelCompletion
import java.io.File

/**
 * Owns the optional translation llama.cpp runtime. The runtime is separate
 * from the kana-kanji conversion model so a slow translation cannot block
 * normal input.
 */
internal object TranslationEngine {
    private val loadLock = Any()

    fun isLoaded(file: File): Boolean = ZenzNative.isTranslationLoadedFor(file.absolutePath)

    fun load(file: File): Boolean = synchronized(loadLock) {
        if (ZenzNative.isTranslationLoadedFor(file.absolutePath)) return true
        ZenzNative.initTranslation(file.absolutePath)
    }

    fun close() = synchronized(loadLock) {
        ZenzNative.closeTranslation()
    }

    fun translate(
        prompt: String,
        maxTokens: Int = 512,
        timeoutMs: Int = 15_000
    ): ModelCompletion = ZenzNative.translate(prompt, maxTokens, timeoutMs)
}
