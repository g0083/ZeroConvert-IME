package com.zeroconvert.ime.assist

import android.content.Context
import com.zeroconvert.ime.KanaKanjiConverter
import java.io.File
import java.io.FileInputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.channels.FileChannel

/** Immutable AZR2 index generated from the same Android LOUDS source. */
internal class ReverseReadingDictionary private constructor(data: ByteBuffer) {
    constructor(bytes: ByteArray) : this(ByteBuffer.wrap(bytes))

    private val buffer = data.asReadOnlyBuffer().order(ByteOrder.LITTLE_ENDIAN)
    private val size = buffer.limit()
    private val count: Int
    private val header: Int
    init {
        require(size >= 12 && buffer.getInt(0) == 0x32525a41) // AZR2 in little-endian order
        count = buffer.getInt(4)
        require(count > 0 && count.toLong() * 4 + 12 <= size)
        header = 12 + count * 4
        require(buffer.getInt(8) == header && buffer.getInt(header - 4) == size)
        var last = header
        for (index in 0..count) {
            val offset = buffer.getInt(8 + index * 4)
            require(offset >= last && offset <= size)
            last = offset
        }
    }

    companion object {
        fun map(file: File): ReverseReadingDictionary = FileInputStream(file).channel.use { channel ->
            ReverseReadingDictionary(channel.map(FileChannel.MapMode.READ_ONLY, 0, channel.size()))
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
            val difference = (buffer.get(start + 8 + pos).toInt() and 255) - (query[pos].toInt() and 255)
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
            val bytes = ByteArray(readingSize)
            buffer.asReadOnlyBuffer().apply { position(start + 8 + size); get(bytes) }
            result.add(String(bytes, Charsets.UTF_8))
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

    /**
     * 逆引き辞書を読む。APKには辞書を同梱しないため、初回起動で取得した
     * `files/dictionary/Dictionary/Reverse/reverse-v2.bin` を先に探し、
     * 無ければ旧APKのアセット（`WINDOWS_ENGINE=false` の旧経路）を使う。
     */
    fun load(context: Context) {
        if (dictionary != null) return
        dictionary = try {
            val downloaded = File(
                context.filesDir,
                "dictionary/Dictionary/Reverse/reverse-v2.bin"
            )
            if (downloaded.isFile) {
                ReverseReadingDictionary.map(downloaded)
            } else {
                val asset = if (com.zeroconvert.ime.BuildConfig.WINDOWS_ENGINE)
                    "shared-engine/Dictionary/Reverse/reverse-v2.bin" else "dictionary/reverse-v2.bin"
                context.assets.open(asset).use { ReverseReadingDictionary(it.readBytes()) }
            }
        } catch (error: Exception) { null }
    }
    /** 辞書を取得した後に呼ぶ。次回の [load] で新しい辞書を読み直す。 */
    fun reload(context: Context) {
        dictionary = null
        load(context)
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
