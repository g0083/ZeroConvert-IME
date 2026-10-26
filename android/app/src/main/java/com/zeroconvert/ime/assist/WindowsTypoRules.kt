package com.zeroconvert.ime.assist

import com.zeroconvert.ime.KanaKanjiConverter

/** Bounded offline fallback. Suggestions do not by themselves permit automatic application. */
internal class WindowsTypoRules(
    private val standard: Map<String, String> = WindowsTypoData.standard,
    private val lookup: (String) -> String? = AssistVocabulary::surface
) {
    private val rules = WindowsTypoData.rules.entries.map { KanaKanjiConverter.toHiragana(it.key) to it.value }
    private val boundaries = " 　、。！？!?,.・，．：:；;をにはがでとへものねよなかばやら"

    fun suggest(input: String): String? {
        if (input.length !in 2..128 || input.hasUnsafeTokens() || lookup(input) != null) return null
        rules.firstOrNull { it.first == input }?.let { return it.second }
        rules.filter { input.startsWith(it.first) }.maxByOrNull { it.first.length }?.let {
            return it.second + input.substring(it.first.length)
        }
        for (index in input.indices) {
            if (input[index] !in boundaries) continue
            val rest = input.substring(index + 1)
            val match = rules.filter { (typo, _) -> typo.length >= 3 && rest.startsWith(typo) &&
                (rest.length == typo.length || rest[typo.length] in boundaries) }.maxByOrNull { it.first.length }
            if (match != null) return input.take(index + 1) + match.second + rest.substring(match.first.length)
        }
        val phonetic = buildList {
            if (input.length == 2) add(input.take(1) + "っ" + input.drop(1))
            for (index in 1 until input.length) add(input.take(index) + "ん" + input.drop(index))
            add(input + "ー")
            if (input.endsWith('わ')) add(input.dropLast(1) + "は")
            for ((from, to) in listOf("すい" to "しい", "すかった" to "しかった", "すく" to "しく")) {
                if (input.length > from.length && input.endsWith(from)) add(input.dropLast(from.length) + to)
            }
        }
        phonetic.firstNotNullOfOrNull { standard[it] }?.let { return it }
        val matches = standard.entries.filter { (reading, _) -> kotlin.math.abs(reading.length - input.length) <= 1 &&
            TypoCorrectionEngine.damerauLevenshtein(input, reading) == 1 }
        (matches.firstOrNull { TypoVariant.hasAdjacentDifference(input, it.key) } ?: matches.firstOrNull())?.let { return it.value }

        data class Prefix(val adjacent: Boolean, val length: Int, val surface: String)
        var best: Prefix? = null
        for ((reading, surface) in standard) {
            if (reading.length < 3 || reading.length >= input.length || input.startsWith(reading)) continue
            for (size in reading.length - 1..minOf(reading.length + 1, input.length - 1)) {
                val prefix = input.take(size)
                if (lookup(prefix) != null || TypoCorrectionEngine.damerauLevenshtein(prefix, reading) != 1) continue
                val candidate = Prefix(TypoVariant.hasAdjacentDifference(prefix, reading), reading.length, surface + input.drop(size))
                val previous = best
                if (previous == null || (candidate.adjacent && !previous.adjacent) ||
                    (candidate.adjacent == previous.adjacent && candidate.length > previous.length)) best = candidate
            }
        }
        return best?.surface ?: TypoVariant.findQwertyCorrection(input, lookup)?.surface
    }
}
