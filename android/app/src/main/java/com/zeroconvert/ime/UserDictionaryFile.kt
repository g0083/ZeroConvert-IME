package com.zeroconvert.ime

import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.nio.ByteBuffer
import java.nio.charset.CharacterCodingException
import java.nio.charset.Charset
import java.text.Normalizer

private const val DICTIONARY_FORMAT = "zeroconvert-user-dictionary"
private const val MAX_FILE_BYTES = 32 * 1024 * 1024
private val invalidField = Regex("[\\t\\r\\n\\u0000]")
private val lines = Regex("\\r\\n|\\r|\\n")
private val plainRow = Regex("^(\\S+)[ 　]+(.+)$")
private val atokPosMarker = Regex("[$*]$")
private val skippedPos = setOf("サジェストのみ", "抑制単語", "制御単語")

internal fun hiraganaReading(value: String): String =
    Normalizer.normalize(value.trim(), Normalizer.Form.NFC).map { kana ->
        if (kana in 'ァ'..'ヶ' || kana in 'ヽ'..'ヿ') kana - 96 else kana
    }.joinToString("")

internal fun normalizedDictionaryEntry(reading: String, word: String, pos: UserDictionaryPos): UserDictEntry? {
    val cleanReading = reading.trim()
    val cleanWord = word.trim()
    if (cleanReading.isEmpty() || cleanWord.isEmpty()) return null
    return UserDictEntry(cleanReading, cleanWord, pos)
}

internal fun UserDictEntry.dictionaryKey(): Pair<String, String> =
    hiraganaReading(reading) to Normalizer.normalize(word.trim(), Normalizer.Form.NFC)

internal data class DictionaryMerge(val entries: List<UserDictEntry>, val added: Int, val duplicates: Int, val overflow: Int)

internal fun mergeDictionaryEntries(current: List<UserDictEntry>, incoming: List<UserDictEntry>): DictionaryMerge {
    val entries = current.toMutableList()
    val seen = current.mapTo(HashSet()) { it.dictionaryKey() }
    var duplicates = 0
    var overflow = 0
    for (entry in incoming) {
        val key = entry.dictionaryKey()
        when {
            key in seen -> duplicates++
            entries.size >= MAX_USER_DICTIONARY_ENTRIES -> overflow++
            else -> {
                seen.add(key)
                entries.add(entry)
            }
        }
    }
    return DictionaryMerge(entries, entries.size - current.size, duplicates, overflow)
}

internal enum class DictionaryExportFormat(val label: String, val mimeType: String, val extension: String) {
    JSON("ZeroConvert JSON（品詞を保持）", "application/json", "json"),
    MOZC("Google 日本語入力 / Mozc（UTF-8）", "text/plain", "txt"),
    MSIME("Microsoft IME（UTF-16）", "text/plain", "txt")
}

internal fun List<UserDictEntry>.exportableEntries(format: DictionaryExportFormat): List<UserDictEntry> =
    mapNotNull { normalizedDictionaryEntry(it.reading, it.word, it.pos) }.filter { entry ->
        format == DictionaryExportFormat.JSON || (
            !invalidField.containsMatchIn(entry.reading) && !invalidField.containsMatchIn(entry.word) &&
                !entry.reading.startsWith(if (format == DictionaryExportFormat.MSIME) '!' else '#')
        )
    }

internal data class DictionaryImport(val entries: List<UserDictEntry>, val skipped: Int, val mapped: Int)

internal class UserDictionaryFile(catalog: JSONObject) {
    private data class Preset(val label: String, val mozc: String, val msime: String)
    private val presets = linkedMapOf<UserDictionaryPos, Preset>()
    private val aliases = mutableMapOf<String, UserDictionaryPos>()

    init {
        // Android's JSONObject does not guarantee the iteration order of its keys.
        for (pos in POS_ORDER) {
            val row = catalog.getJSONObject(pos.key)
            val preset = Preset(row.getString("label"), row.getString("mozc"), row.getString("msime"))
            presets[pos] = preset
            val extra = row.getJSONArray("aliases")
            for (alias in listOf(pos.key, preset.label, preset.mozc, preset.msime) +
                (0 until extra.length()).map(extra::getString)) {
                aliases.putIfAbsent(alias, pos)
            }
        }
    }

    val positions: Set<UserDictionaryPos> get() = presets.keys
    fun label(pos: UserDictionaryPos): String = presets.getValue(pos).label

    fun parse(text: String): DictionaryImport {
        val content = text.removePrefix("\uFEFF")
        val trimmed = content.trim()
        require(trimmed.isNotEmpty()) { "ファイルが空です。" }
        val entries = mutableListOf<UserDictEntry>()
        var skipped = 0
        var mapped = 0

        fun add(reading: String?, word: String?, rawPos: String?, defaultPos: UserDictionaryPos) {
            val alias = rawPos?.let {
                Normalizer.normalize(it, Normalizer.Form.NFKC).substringBefore(':').trim().replace(atokPosMarker, "")
            }.orEmpty()
            val pos = if (alias.isEmpty()) defaultPos else aliases[alias]
            val entry = normalizedDictionaryEntry(reading.orEmpty(), word.orEmpty(), pos ?: UserDictionaryPos.COMMON_NOUN)
            if (entry == null || alias in skippedPos) {
                skipped++
                return
            }
            if (pos == null) mapped++
            entries.add(entry)
        }

        val json = trimmed.startsWith('{') || trimmed.startsWith('[')
        if (json) {
            val rows = if (trimmed.startsWith('[')) JSONArray(trimmed) else {
                val objectValue = JSONObject(trimmed)
                require(objectValue.optString("format") != DICTIONARY_FORMAT || objectValue.opt("version") == 1) {
                    "このバージョンの辞書ファイルは読み込めません。"
                }
                objectValue.optJSONArray("entries") ?: objectValue.optJSONObject("user_dictionary")?.optJSONArray("entries")
                ?: throw IllegalArgumentException("辞書データが見つかりませんでした（entries がありません）。")
            }
            for (i in 0 until rows.length()) {
                val row = rows.optJSONObject(i)
                add(row?.opt("reading") as? String, row?.opt("word") as? String, row?.opt("pos") as? String,
                    UserDictionaryPos.PROPER_NOUN)
            }
        } else {
            val header = trimmed.lineSequence().first().trim().lowercase()
            val gboard = header == "# gboard dictionary version:1"
            val commentPrefix = if (header.startsWith("!microsoft ime") || header.startsWith("!!atok_tango_text_header") ||
                header.startsWith("!!dicut")) '!' else '#'
            for (line in lines.splitToSequence(content)) {
                if (line.isBlank() || line.startsWith(commentPrefix)) continue
                val columns = line.split('\t', limit = 4)
                if (columns.size >= 2) {
                    add(columns[0], columns[1], if (gboard) "shortcut" else columns.getOrNull(2), UserDictionaryPos.COMMON_NOUN)
                    continue
                }
                if (line.trimStart().startsWith('#') || line.trimStart().startsWith('!')) continue
                val match = plainRow.matchEntire(line.trim())
                add(match?.groupValues?.get(1), match?.groupValues?.get(2), null, UserDictionaryPos.COMMON_NOUN)
            }
            require(entries.isNotEmpty()) { "取り込める「読み」と「単語」の行が見つかりませんでした。" }
        }
        return DictionaryImport(entries, skipped, mapped)
    }

    fun encode(entries: List<UserDictEntry>, format: DictionaryExportFormat): ByteArray {
        if (format == DictionaryExportFormat.JSON) {
            val rows = JSONArray()
            for (entry in entries) {
                rows.put(JSONObject().put("reading", entry.reading).put("word", entry.word).put("pos", entry.pos.key))
            }
            return (JSONObject().put("format", DICTIONARY_FORMAT).put("version", 1)
                .put("entry_count", entries.size).put("entries", rows).toString(2) + "\n").toByteArray(Charsets.UTF_8)
        }
        val msime = format == DictionaryExportFormat.MSIME
        val newline = if (msime) "\r\n" else "\n"
        val text = buildString {
            if (msime) {
                append("\uFEFF!Microsoft IME Dictionary Tool").append(newline)
                append("!Version:").append(newline)
                append("!Format:WORDLIST").append(newline)
            }
            for (entry in entries) {
                val preset = presets.getValue(entry.pos)
                append(entry.reading).append('\t').append(entry.word).append('\t')
                append(if (msime) preset.msime else preset.mozc).append('\t').append(newline)
            }
        }
        return text.toByteArray(if (msime) Charsets.UTF_16LE else Charsets.UTF_8)
    }

    companion object {
        private val POS_ORDER = listOf(
            UserDictionaryPos.COMMON_NOUN, UserDictionaryPos.PROPER_NOUN, UserDictionaryPos.PERSONAL_NAME,
            UserDictionaryPos.FAMILY_NAME, UserDictionaryPos.GIVEN_NAME, UserDictionaryPos.ORGANIZATION,
            UserDictionaryPos.PLACE_NAME, UserDictionaryPos.SAHEN_NOUN, UserDictionaryPos.SYMBOL, UserDictionaryPos.SHORTCUT
        )
    }
}

internal fun readDictionaryText(stream: InputStream): String {
    val buffer = ByteArray(8192)
    val output = ByteArrayOutputStream()
    while (true) {
        val count = stream.read(buffer)
        if (count < 0) break
        require(output.size() + count <= MAX_FILE_BYTES) { "ファイルが大きすぎます（32 MB まで）。" }
        output.write(buffer, 0, count)
    }
    val bytes = output.toByteArray()
    fun startsWith(vararg prefix: Int) = bytes.size >= prefix.size &&
        prefix.indices.all { (bytes[it].toInt() and 0xff) == prefix[it] }
    fun decode(charset: Charset, offset: Int = 0) =
        charset.newDecoder().decode(ByteBuffer.wrap(bytes, offset, bytes.size - offset)).toString()

    try {
        return when {
            startsWith(0xff, 0xfe) -> decode(Charsets.UTF_16LE, 2)
            startsWith(0xfe, 0xff) -> decode(Charsets.UTF_16BE, 2)
            startsWith(0xef, 0xbb, 0xbf) -> decode(Charsets.UTF_8, 3)
            else -> try {
                decode(Charsets.UTF_8)
            } catch (_: CharacterCodingException) {
                decode(Charset.forName("windows-31j"))
            }
        }
    } catch (_: CharacterCodingException) {
        throw IllegalArgumentException("UTF-8、UTF-16、Shift_JIS（Windows-31J）で読み込めませんでした。")
    }
}
