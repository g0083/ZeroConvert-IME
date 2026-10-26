package com.zeroconvert.ime

/** Shared candidate validity rules. Never repair generated text by deleting valid repetition. */
object ConversionCandidateMerge {
    private fun displayRuby(value: String): String = value.replace('-', 'ー').let {
        if (it.endsWith('n')) it.dropLast(1) + "ン" else it
    }

    private fun hasLexicalAlternative(top: Candidate, raw: String, from: Int? = null, to: Int? = null): Boolean {
        var offset = 0
        for (start in top.segments.indices) {
            val first = offset
            offset += displayRuby(top.segments[start].reading).codePointCount(0, displayRuby(top.segments[start].reading).length)
            if (from != null && from != first) continue
            var ruby = ""
            var word = ""
            var end = first
            for (index in start until top.segments.size) {
                val segment = top.segments[index]
                if (segment.correctionKind != 0) break
                ruby += displayRuby(segment.reading)
                word += segment.surface
                end += displayRuby(segment.reading).codePointCount(0, displayRuby(segment.reading).length)
                if (!raw.startsWith(ruby)) break
                if (raw != ruby) continue
                if (to != null && end != to) break
                return KanaKanjiConverter.toKatakana(word) != raw
            }
        }
        return false
    }

    /** Mirrors Swift's conservative promotion; unaligned free-form model text proves no lexical boundary. */
    fun mergeModelAndDictionary(model: Candidate?, normal: List<Candidate>, reading: String): List<Candidate> {
        if (model == null) return normal
        val top = model.copy(segments = model.segments.ifEmpty { normal.firstOrNull { it.text == model.text }?.segments.orEmpty() })
        var promotion: Candidate? = null
        if (top.segments.isNotEmpty() && top.segments.none { it.correctionKind in 1..2 }) {
            val first = normal.firstOrNull()
            val rewrites = first?.segments?.filter { it.correctionKind in 1..2 }.orEmpty()
            if (rewrites.size == 1) {
                val rewrite = rewrites.single()
                if (!hasLexicalAlternative(top, displayRuby(rewrite.reading), rewrite.originalStart, rewrite.originalEnd)) promotion = first
            }
            if (promotion == null) first?.segments?.firstOrNull { it.correctionKind == 3 }?.let { entry ->
                if (!hasLexicalAlternative(top, displayRuby(entry.reading))) promotion = first
            }
        }
        val result = mutableListOf<Candidate>()
        appendDistinct(result, listOfNotNull(promotion, top).filter { validSurface(it.text, reading) })
        appendDistinct(result, normal)
        return result
    }

    fun validSurface(surface: String, reading: String): Boolean = surface.isNotBlank() &&
        surface.codePointCount(0, surface.length) <= reading.codePointCount(0, reading.length) * 3 + 16 &&
        surface.none { it.isISOControl() || it in '\uEE00'..'\uEE0F' }

    fun appendDistinct(candidates: MutableList<Candidate>, incoming: List<Candidate>) {
        val keys = candidates.mapTo(mutableSetOf()) { it.text }
        for (candidate in incoming) if (keys.add(candidate.text)) candidates.add(candidate)
    }

    fun withCorrection(current: List<Candidate>, correction: com.zeroconvert.ime.assist.AiAssistResult?): List<Candidate> {
        val ordinary = current.filter { it.assist == null }.toMutableList()
        if (correction != null && correction.type == com.zeroconvert.ime.assist.AiAssistType.TYPO_CORRECTION) {
            ordinary.add(minOf(1, ordinary.size), Candidate(correction.text,
                KanaKanjiConverter.toKatakana(correction.sourceReading), assist = correction))
        }
        return ordinary
    }
}
