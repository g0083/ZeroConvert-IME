package com.zeroconvert.ime.translation

/**
 * PFML3 prompt and validation shared by input translation and selection
 * translation. Pure Kotlin so the host tests can exercise the exact contract
 * without loading the native model.
 */
internal object TranslationText {
    const val AUTO = "auto"
    const val EN = "en"
    const val JA = "ja"

    const val MAX_SOURCE_CODE_POINTS = 512
    const val MAX_OUTPUT_CODE_POINTS = 512

    fun hasJapanese(text: String): Boolean = text.any { ch ->
        ch in '\u3041'..'\u30FF' ||
            ch in '\u3400'..'\u4DBF' ||
            ch in '\u4E00'..'\u9FFF' ||
            ch in '\uF900'..'\uFAFF' ||
            ch in '\uFF66'..'\uFF9D'
    }

    fun targetFor(source: String, configured: String = AUTO): String = when (configured) {
        EN, JA -> configured
        else -> if (hasJapanese(source)) EN else JA
    }

    fun sourceLanguageFor(target: String): String = if (target == EN) JA else EN

    fun validSource(source: String): Boolean {
        if (source.isEmpty()) return false
        if (source.codePointCount(0, source.length) !in 1..MAX_SOURCE_CODE_POINTS) return false
        if (source.contains("<|")) return false
        return source.none { it.isISOControl() }
    }

    fun validOutput(output: String): Boolean {
        val text = output.trim()
        if (text.isEmpty()) return false
        if (text.codePointCount(0, text.length) > MAX_OUTPUT_CODE_POINTS) return false
        if (text.contains("<|")) return false
        return text.none { it.isISOControl() }
    }

    fun prompt(source: String, target: String): String {
        val sourceLanguage = sourceLanguageFor(target)
        return "<|plamo:bos|><|plamo:tag|>dataset<|plamo:msg|>translation" +
            "<|plamo:tag|>input<|plamo:key|>lang<|plamo:val|>$sourceLanguage" +
            "<|plamo:msg|>$source" +
            "<|plamo:tag|>output<|plamo:key|>lang<|plamo:val|>$target<|plamo:msg|>"
    }

    fun targetLabel(target: String): String = if (target == EN) "英語" else "日本語"
}
