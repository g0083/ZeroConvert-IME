package com.zeroconvert.ime.assist

import android.content.Context
import com.zeroconvert.ime.KanaKanjiConverter
import java.nio.ByteBuffer
import java.nio.ByteOrder

/** Immutable AZR2 index generated from the same Android LOUDS source. */
internal class ReverseReadingDictionary(private val bytes: ByteArray) {
    private val buffer = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
    private val count: Int
    private val header: Int
    init {
        require(bytes.size >= 12 && bytes.copyOfRange(0, 4).contentEquals("AZR2".toByteArray()))
        count = buffer.getInt(4)
        require(count > 0 && count.toLong() * 4 + 12 <= bytes.size)
        header = 12 + count * 4
        require(buffer.getInt(8) == header && buffer.getInt(header - 4) == bytes.size)
        var last = header
        for (index in 0..count) {
            val offset = buffer.getInt(8 + index * 4)
            require(offset >= last && offset <= bytes.size)
            last = offset
        }
    }
    private fun record(index: Int): Pair<Int, Int>? {
        if (index !in 0 until count) return null
        val start = buffer.getInt(8 + index * 4)
        val end = buffer.getInt(12 + index * 4)
        if (start < header || end - start < 8) return null
        val wordSize = buffer.getShort(start).toInt() and 65535
        val readingSize = buffer.getShort(start + 2).toInt() and 65535
        if (start + 8L + wordSize + readingSize != end.toLong()) return null
        return start to wordSize
    }
    private fun compare(index: Int, query: ByteArray): Int {
        val (start, size) = record(index) ?: return 1
        for (pos in 0 until minOf(size, query.size)) {
            val difference = (bytes[start + 8 + pos].toInt() and 255) - (query[pos].toInt() and 255)
            if (difference != 0) return difference
        }
        return size - query.size
    }
    fun readings(surface: String): List<String> {
        val query = surface.toByteArray(Charsets.UTF_8)
        var low = 0; var high = count
        while (low < high) {
            val mid = low + (high - low) / 2
            if (compare(mid, query) < 0) low = mid + 1 else high = mid
        }
        val result = mutableListOf<String>()
        for (index in low until minOf(count, low + 128)) {
            if (compare(index, query) != 0) break
            val (start, size) = record(index) ?: break
            val readingSize = buffer.getShort(start + 2).toInt() and 65535
            result.add(String(bytes, start + 8 + size, readingSize, Charsets.UTF_8))
        }
        return result
    }
    private fun literal(word: String): Boolean = word.length == 1 && word.all {
        it in 'ぁ'..'ゖ' || it in 'ァ'..'ヶ' || it.code in 32..126 || it in "ー、。！？!?・"
    }
    fun matches(surface: String, reading: String, userReadings: Map<String, List<String>> = emptyMap()): Boolean {
        if (surface.length !in 1..128 || reading.length !in 1..128) return false
        val target = KanaKanjiConverter.toKatakana(reading)
        val paths = Array(surface.length + 1) { mutableSetOf<Int>() }
        paths[0].add(0)
        for (start in surface.indices) {
            if (paths[start].isEmpty()) continue
            for (end in start + 1..minOf(surface.length, start + 40)) {
                val word = surface.substring(start, end)
                val choices = (userReadings[word].orEmpty() + readings(word)).toMutableList()
                if (literal(word)) choices.add(KanaKanjiConverter.toKatakana(word))
                for (offset in paths[start]) for (choice in choices) {
                    if (target.startsWith(choice, offset)) paths[end].add(offset + choice.length)
                }
            }
        }
        return target.length in paths.last()
    }
    fun restore(surface: String, userReadings: Map<String, List<String>> = emptyMap()): String? {
        if (surface.length !in 1..128) return null
        val paths = arrayOfNulls<String>(surface.length + 1)
        paths[0] = ""
        for (start in surface.indices) {
            val prefix = paths[start] ?: continue
            for (end in minOf(surface.length, start + 40) downTo start + 1) {
                if (paths[end] != null) continue
                val word = surface.substring(start, end)
                val reading = userReadings[word]?.firstOrNull() ?: readings(word).firstOrNull() ?:
                    if (literal(word)) KanaKanjiConverter.toKatakana(word) else null
                if (reading != null) paths[end] = prefix + reading
            }
        }
        return paths.last()
    }
}

object ReadingCheck {
    @Volatile private var dictionary: ReverseReadingDictionary? = null
    fun load(context: Context) {
        if (dictionary != null) return
        dictionary = try {
            val asset = if (com.zeroconvert.ime.BuildConfig.WINDOWS_ENGINE)
                "shared-engine/Dictionary/Reverse/reverse-v2.bin" else "dictionary/reverse-v2.bin"
            context.assets.open(asset).use { ReverseReadingDictionary(it.readBytes()) }
        } catch (error: Exception) { null }
    }
    fun hasSameReading(surface: String, reading: String): Boolean =
        KanaKanjiConverter.toHiragana(surface) == KanaKanjiConverter.toHiragana(reading) ||
            dictionary?.matches(surface, reading, AssistVocabulary.userReadings()) == true

    fun readingForSurface(surface: String): String? {
        if (surface.isBlank()) return null
        val users = AssistVocabulary.userReadings()
        users[surface]?.firstOrNull()?.let { return KanaKanjiConverter.toHiragana(it) }
        if (surface.all { it in 'ぁ'..'ゖ' || it in 'ァ'..'ヶ' || it in "ー、。！？!?・" }) {
            return KanaKanjiConverter.toHiragana(surface)
        }
        return dictionary?.restore(surface, users)?.let(KanaKanjiConverter::toHiragana)
    }
}
