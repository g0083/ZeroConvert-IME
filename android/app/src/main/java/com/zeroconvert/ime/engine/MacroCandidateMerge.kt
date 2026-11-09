package com.zeroconvert.ime.engine

import com.zeroconvert.ime.Candidate

/**
 * Places deterministic macro expansions directly after the top conversion candidate
 * (the Windows desktop client's index 1) instead of leaving them at the end of the
 * list. Existing surfaces win, duplicates are dropped, and macro candidates never
 * write learning history.
 */
object MacroCandidateMerge {
    const val INDEX_AFTER_TOP = 1

    fun merge(converted: List<Candidate>, macroTexts: List<String>, reading: String): List<Candidate> {
        if (macroTexts.isEmpty()) return converted
        val existing = converted.mapTo(HashSet()) { it.text }
        val seen = HashSet<String>()
        val additions = macroTexts.asSequence()
            .filter { it.isNotBlank() }
            .filter { it !in existing && seen.add(it) }
            .map { text -> Candidate(text, reading, cost = 0f, isAI = false, learningAllowed = false) }
            .toList()
        if (additions.isEmpty()) return converted
        val insertAt = if (converted.isEmpty()) 0 else INDEX_AFTER_TOP
        return buildList(converted.size + additions.size) {
            addAll(converted.take(insertAt))
            addAll(additions)
            addAll(converted.drop(insertAt))
        }
    }
}