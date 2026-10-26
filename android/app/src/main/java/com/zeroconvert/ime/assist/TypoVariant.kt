package com.zeroconvert.ime.assist

/** Windows kana-to-keystroke adjacency, with at most eight variants per token. */
object TypoVariant {
    data class Correction(val surface: String, val tokenReading: String, val tokenSurface: String)
    private const val ALL_KANA = "あいうえおかきくけこさしすせそたちつてとなにぬねのはひふへほまみむめもやゆよらりるれろわをんがぎぐげござじずぜぞだぢづでどばびぶべぼぱぴぷぺぽゃゅょっゔ"
    private val rows = listOf("qwertyuiop", "asdfghjkl", "zxcvbnm")
    private fun keysAdjacent(a: Char, b: Char): Boolean {
        val ar = rows.indexOfFirst { a in it }
        val br = rows.indexOfFirst { b in it }
        return ar >= 0 && br >= 0 && kotlin.math.abs(ar - br) <= 1 &&
            kotlin.math.abs(rows[ar].indexOf(a) - rows[br].indexOf(b)) <= 1
    }
    fun kanaAdjacent(a: Char, b: Char): Boolean {
        if (a == b) return false
        val first = WindowsTypoData.romaji[a.toString()].orEmpty()
        val second = WindowsTypoData.romaji[b.toString()].orEmpty()
        if (first.isNotEmpty() && first.length == second.length) {
            val changed = first.indices.filter { first[it] != second[it] }
            if (changed.size == 1 && keysAdjacent(first[changed[0]], second[changed[0]])) return true
        }
        return keysAdjacent(a, b)
    }
    fun hasAdjacentDifference(a: String, b: String): Boolean {
        if (a.length != b.length) return false
        val changed = a.indices.filter { a[it] != b[it] }
        return changed.size == 1 && kanaAdjacent(a[changed[0]], b[changed[0]])
    }
    private val alternatives by lazy { ALL_KANA.associateWith { source -> ALL_KANA.filter { kanaAdjacent(source, it) } } }
    fun generateQwertyVariants(reading: String): List<String> {
        if (reading.length !in 2..128) return emptyList()
        val variants = linkedSetOf<String>()
        for (index in reading.indices.reversed()) {
            for (alternative in alternatives[reading[index]].orEmpty()) {
                variants.add(reading.substring(0, index) + alternative + reading.substring(index + 1))
                if (variants.size == 8) return variants.toList()
            }
        }
        return variants.toList()
    }
    fun builtinIndex(): Map<String, String> = AssistVocabulary.builtinEntries()
    fun lookupSurface(reading: String): String? = AssistVocabulary.surface(reading)
    fun findIndexedSurface(reading: String): String? = lookupSurface(reading.trim())
    fun findQwertyCorrection(hiragana: String, lookup: (String) -> String? = AssistVocabulary::surface): Correction? {
        val reading = hiragana.trim()
        if (reading.length !in 2..128 || lookup(reading) != null) return null
        fun tokenCorrection(token: String): String? {
            if (lookup(token) != null) return null
            val hits = generateQwertyVariants(token).mapNotNull(lookup)
            return hits.firstOrNull { it.length > 1 } ?: hits.firstOrNull()
        }
        tokenCorrection(reading)?.let { return Correction(it, reading, it) }
        for (start in 1 until reading.length - 2) {
            val suffix = reading.substring(start)
            tokenCorrection(suffix)?.let { return Correction(reading.take(start) + it, suffix, it) }
        }
        return null
    }
}
