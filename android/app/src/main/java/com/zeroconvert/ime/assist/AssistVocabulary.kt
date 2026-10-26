package com.zeroconvert.ime.assist

import android.content.Context

object AssistVocabulary {
    private data class Index(val surfaces: Map<String, String>, val standard: Set<String>)
    @Volatile private var index: Index? = null
    private data class UserIndex(val surfaces: Map<String, String>, val reverse: Map<String, List<String>>, val revision: Long)
    @Volatile private var users = UserIndex(emptyMap(), emptyMap(), 0)
    val revision: Long get() = users.revision
    @Synchronized
    fun replaceUserEntries(entries: List<Pair<String, String>>) {
        val updated = linkedMapOf<String, String>()
        val reverse = linkedMapOf<String, MutableList<String>>()
        for ((reading, surface) in entries) {
            if (reading.length in 1..128 && surface.length in 1..512 &&
                !reading.hasUnsafeTokens() && !surface.hasUnsafeTokens()) {
                updated.putIfAbsent(reading, surface)
                val ruby = com.zeroconvert.ime.KanaKanjiConverter.toKatakana(reading)
                val readings = reverse.getOrPut(surface) { mutableListOf() }
                if (ruby !in readings) readings.add(ruby)
            }
        }
        if (updated.entries.toList() != users.surfaces.entries.toList() || reverse != users.reverse)
            users = UserIndex(updated, reverse.mapValues { it.value.toList() }, users.revision + 1)
    }
    internal fun userReadings(): Map<String, List<String>> = users.reverse
    val isReady: Boolean get() = index != null
    fun load(context: Context) {
        val surfaces = linkedMapOf<String, String>()
        val standard = mutableSetOf<String>()
        try {
            context.assets.open("assist-vocabulary.tsv").bufferedReader().useLines { lines ->
                lines.filterNot { it.startsWith('#') }.forEach { line ->
                    val fields = line.split('\t')
                    if (fields.size == 3 && fields[0].isNotEmpty() && fields[1].isNotEmpty()) {
                        surfaces.putIfAbsent(fields[0], fields[1])
                        if (fields[2] == "standard") standard.add(fields[0])
                    }
                }
            }
            require(surfaces.size >= 2000 && standard.size >= 300)
            index = Index(surfaces, standard)
        } catch (_: Exception) {
            // A missing/truncated vocabulary must not make a tiny fallback list
            // appear to establish uniqueness for automatic correction.
            index = null
        }
    }
    fun builtinEntries(): Map<String, String> = index?.surfaces ?: WindowsTypoData.standard
    fun surface(reading: String): String? = builtinEntries()[reading] ?: users.surfaces[reading] ?:
        TypoCorrectionEngine.STANDARD_VOCAB[reading]
    fun containsReading(reading: String): Boolean = surface(reading) != null
    fun isKnownCleanReading(reading: String): Boolean = containsReading(reading)
}
