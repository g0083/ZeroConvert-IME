package com.zeroconvert.ime.assist

import com.zeroconvert.ime.TouchKeyBias

/**
 * Resolves single-keystroke edge-touch ambiguities using measured QWERTY touch coordinates
 * ([TouchKeyBias]) for Japanese Romaji and English word recovery (Proposal 7B).
 */
object TouchBiasTypoResolver {

    data class BiasedVariant(
        val text: String,
        val confidence: Float
    )

    private const val MIN_VARIANT_CONFIDENCE = 0.20f

    /**
     * Generates single-edit Romaji variants ordered by descending border-proximity confidence.
     */
    fun biasedRomajiVariants(
        rawInput: String,
        touchBiases: List<TouchKeyBias>,
        minConfidence: Float = MIN_VARIANT_CONFIDENCE
    ): List<BiasedVariant> {
        val rawLower = normalizeAsciiRomaji(rawInput) ?: return emptyList()
        if (rawLower.length < 2 || touchBiases.isEmpty()) return emptyList()

        val offset = alignSuffixOffset(rawLower, touchBiases) ?: return emptyList()
        val variants = LinkedHashMap<String, Float>()

        for (bi in touchBiases.indices) {
            val rawIndex = offset + bi
            if (rawIndex !in rawLower.indices) continue
            val bias = touchBiases[bi]
            if (bias.nearbyChars.isEmpty()) continue

            for ((neighbor, conf) in bias.nearbyChars) {
                val adj = neighbor.lowercaseChar()
                if (adj !in 'a'..'z' || adj == rawLower[rawIndex] || conf < minConfidence) continue
                val mutated = rawLower.substring(0, rawIndex) + adj + rawLower.substring(rawIndex + 1)
                val prev = variants[mutated] ?: 0f
                if (conf > prev) variants[mutated] = conf
            }
        }

        return variants.entries
            .sortedWith(compareByDescending<Map.Entry<String, Float>> { it.value }.thenBy { it.key })
            .map { BiasedVariant(it.key, it.value) }
    }

    /**
     * Generates spatially-weighted 1-edit English prefix/word recovery candidates from a
     * frequency-ordered dictionary word list.
     */
    fun rankEnglishCorrections(
        prefix: String,
        touchBiases: List<TouchKeyBias>,
        words: Iterable<String>,
        limit: Int = 8,
        minConfidence: Float = 0.25f
    ): List<String> {
        if (prefix.length < 2 || touchBiases.isEmpty() || limit <= 0) return emptyList()
        val variants = biasedRomajiVariants(prefix, touchBiases, minConfidence)
        if (variants.isEmpty()) return emptyList()

        data class ScoredWord(val word: String, val score: Float)
        val bestByWord = LinkedHashMap<String, ScoredWord>()
        val capitalize = prefix.firstOrNull()?.isUpperCase() == true

        var wordRank = 0
        for (word in words) {
            val lowerWord = word.lowercase()
            val rankDecay = 1f / (1f + wordRank * 0.015f)
            for (variant in variants) {
                val p = variant.text
                if (lowerWord.startsWith(p)) {
                    val exactBonus = if (lowerWord == p) 1.5f else 1.0f
                    val score = variant.confidence * rankDecay * exactBonus
                    val formatted = if (capitalize && word.isNotEmpty()) {
                        word[0].uppercaseChar() + word.substring(1)
                    } else {
                        word
                    }
                    val existing = bestByWord[formatted.lowercase()]
                    if (existing == null || score > existing.score) {
                        bestByWord[formatted.lowercase()] = ScoredWord(formatted, score)
                    }
                }
            }
            wordRank++
            // Cap scan cost over large dictionaries once high-rank words have been inspected.
            if (wordRank >= 4000 && bestByWord.size >= limit * 2) break
        }

        return bestByWord.values
            .sortedByDescending { it.score }
            .take(limit)
            .map { it.word }
    }

    internal fun alignSuffixOffset(rawLower: String, touchBiases: List<TouchKeyBias>): Int? {
        if (rawLower.isEmpty() || touchBiases.isEmpty()) return null
        if (touchBiases.size == rawLower.length &&
            touchBiases.indices.all { touchBiases[it].typedChar.lowercaseChar() == rawLower[it] }
        ) {
            return 0
        }
        val offset = rawLower.length - touchBiases.size
        if (offset >= 0 &&
            touchBiases.indices.all { touchBiases[it].typedChar.lowercaseChar() == rawLower[offset + it] }
        ) {
            return offset
        }
        // Fallback: check if trailing subset of touchBiases aligns to trailing tail of rawLower.
        val overlap = minOf(rawLower.length, touchBiases.size)
        for (len in overlap downTo 2) {
            val rawStart = rawLower.length - len
            val biasStart = touchBiases.size - len
            val allMatch = (0 until len).all { k ->
                touchBiases[biasStart + k].typedChar.lowercaseChar() == rawLower[rawStart + k]
            }
            if (allMatch) {
                return rawStart - biasStart
            }
        }
        return null
    }

    private fun normalizeAsciiRomaji(rawInput: String): String? {
        val trimmed = rawInput.trim()
        if (trimmed.isEmpty()) return null
        val normalized = RomajiDecoder.normalizedRomaji(trimmed) ?: return null
        if (normalized.any { it !in 'a'..'z' && it != '\'' && it != '-' }) {
            // Contains unmapped non-ASCII glyphs; use trailing ASCII characters only if valid.
            val cleaned = normalized.filter { it in 'a'..'z' }
            return cleaned.takeIf { it.length >= 2 }
        }
        return normalized
    }

}
