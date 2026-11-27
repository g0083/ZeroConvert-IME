package com.zeroconvert.ime

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.charset.Charset

class UserDictionaryFileTest {
    private val codec = UserDictionaryFile(JSONObject(generateSequence(File("").absoluteFile) { it.parentFile }
        .map { File(it, "data/user-dictionary-pos.json") }
        .first { it.isFile }.readText()))

    @Test
    fun allPosAndMultilineEntriesRoundTripInZeroConvertJson() {
        val entries = UserDictionaryPos.entries.mapIndexed { index, pos ->
            UserDictEntry("ヨミ$index", "一行\n二行", pos)
        }
        assertEquals(UserDictionaryPos.entries.toSet(), codec.positions)
        assertEquals(entries, codec.parse(codec.encode(entries, DictionaryExportFormat.JSON).decodeToString()).entries)
        val legacy = codec.parse("""{"user_dictionary":{"entries":[{"reading":"よみ","word":"単語"}]}}""")
        assertEquals(UserDictionaryPos.PROPER_NOUN, legacy.entries.single().pos)
        assertThrows(IllegalArgumentException::class.java) {
            codec.parse("""{"format":"zeroconvert-user-dictionary","version":2,"entries":[]}""")
        }
    }

    @Test
    fun exportsUseTargetPosNamesAndMicrosoftBomAndHeader() {
        val entries = listOf(UserDictEntry("へんかん", "変換", UserDictionaryPos.SAHEN_NOUN),
            UserDictEntry("そしき", "団体", UserDictionaryPos.ORGANIZATION))
        val googleText = codec.encode(entries, DictionaryExportFormat.MOZC).decodeToString()
        assertTrue(googleText.contains("へんかん\t変換\t名詞サ変\t\n"))
        assertEquals(entries, codec.parse(googleText).entries)
        val msime = codec.encode(entries, DictionaryExportFormat.MSIME)
        assertEquals(listOf(0xff.toByte(), 0xfe.toByte()), msime.take(2))
        val microsoftText = readDictionaryText(msime.inputStream())
        assertTrue(microsoftText.startsWith("!Microsoft IME Dictionary Tool\r\n!Version:\r\n!Format:WORDLIST\r\n"))
        assertTrue(microsoftText.contains("へんかん\t変換\tさ変名詞\t\r\n"))
        assertEquals(listOf(entries[0], entries[1].copy(pos = UserDictionaryPos.PROPER_NOUN)), codec.parse(microsoftText).entries)
    }

    @Test
    fun shiftJisAndAtokImportPreserveSupportedPosAndReportRemappings() {
        val atok = "!!ATOK_TANGO_TEXT_HEADER_1\r\nたなか\t田中\t固有人姓*\r\n" +
            "おおきい\t大きい\t形容詞\r\nてすと\tテスト\t抑制単語\r\n"
        val bytes = atok.toByteArray(Charset.forName("windows-31j"))
        val parsed = codec.parse(readDictionaryText(bytes.inputStream()))
        assertEquals(listOf(UserDictionaryPos.FAMILY_NAME, UserDictionaryPos.COMMON_NOUN), parsed.entries.map { it.pos })
        assertEquals(1, parsed.skipped)
        assertEquals(1, parsed.mapped)
        assertThrows(IllegalArgumentException::class.java) {
            readDictionaryText(byteArrayOf(0xff.toByte(), 0xfe.toByte(), 0x30).inputStream())
        }
    }

    @Test
    fun mergingKeepsExistingPosForEquivalentReadingsAndEnforcesTheLimit() {
        val existing = UserDictEntry("が", "同じ", UserDictionaryPos.SYMBOL)
        val duplicate = UserDictEntry("ガ", "同じ", UserDictionaryPos.FAMILY_NAME)
        val full = listOf(existing) + List(MAX_USER_DICTIONARY_ENTRIES - 1) { UserDictEntry("よみ$it", "単語") }
        val result = mergeDictionaryEntries(full, listOf(duplicate, UserDictEntry("あたらしい", "新")))
        assertEquals(full, result.entries)
        assertEquals(1, result.duplicates)
        assertEquals(1, result.overflow)
    }
}
