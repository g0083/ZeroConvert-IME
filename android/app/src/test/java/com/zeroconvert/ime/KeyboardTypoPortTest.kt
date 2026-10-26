package com.zeroconvert.ime

import com.zeroconvert.ime.azookey.input.ComposingText
import com.zeroconvert.ime.azookey.input.InputStyle
import org.junit.Assert.*
import org.junit.Test

class KeyboardTypoPortTest {
    private fun segment(reading: String, word: String, kind: Int = 0, from: Int = 0, to: Int = reading.length,
                        lexical: String = reading) = DictionarySegment(reading, word, 1, 1, 1, -5f, kind, from, to, lexical)
    private fun candidate(word: String, reading: String, kind: Int = 0) =
        Candidate(word, reading, segments = listOf(segment(reading, word, kind)))

    @Test fun romanRunsStopAtDirectKanaAndFrozenCursorBoundaries() {
        val input = ComposingText().apply {
            insertAtCursorPosition("文", InputStyle.DIRECT)
            insertAtCursorPosition("kittte", InputStyle.ROMAN2KANA)
            insertAtCursorPosition("かな", InputStyle.DIRECT)
        }
        assertEquals("文きっってかな", input.convertTarget)
        assertArrayEquals(intArrayOf(0, 5, 5, 5, 5, 0, 0), input.keyboardTypoSurfaceEnds())
        val frozen = ComposingText().apply { insertAtCursorPosition("きっって", InputStyle.FROZEN) }
        assertArrayEquals(IntArray(4), frozen.keyboardTypoSurfaceEnds())
        val direct = ComposingText().apply { insertAtCursorPosition("きっって", InputStyle.DIRECT) }
        assertArrayEquals(IntArray(4), direct.keyboardTypoSurfaceEnds())
    }

    @Test fun oneRewriteCanPrecedeALiteralModelOutputWithoutRemovingNormalCandidates() {
        val reading = "キッッテ"
        val corrected = candidate("切手", reading, 1)
        val literal = candidate("きっって", reading)
        val other = candidate("キッッテ", reading)
        val result = ConversionCandidateMerge.mergeModelAndDictionary(literal.copy(isAI = true), listOf(corrected, literal, other), reading)
        assertEquals(listOf("切手", "きっって", "キッッテ"), result.map { it.text })
        assertFalse(result.first().isLearningTarget)
    }

    @Test fun aWholeSpanLexicalAlternativeProtectsTheModelSurface() {
        val reading = "カンイ"
        val corrected = candidate("会員", reading, 3)
        val normal = candidate("簡易", reading)
        val merged = ConversionCandidateMerge.mergeModelAndDictionary(normal.copy(isAI = true), listOf(corrected, normal), reading)
        assertEquals("簡易", merged.first().text)
        assertEquals("会員", merged[1].text)
    }

    @Test fun anAlreadyCorrectedModelPathKeepsItsSurfaceChoice() {
        val reading = "コンンニチハ"
        val normal = candidate("今日は", reading, 2)
        val model = candidate("こんにちは", reading, 2).copy(isAI = true)
        assertEquals("こんにちは", ConversionCandidateMerge.mergeModelAndDictionary(model, listOf(normal, model), reading).first().text)
    }

    @Test fun unknownModelAlignmentAndMultipleRewritesCannotCertifyPromotion() {
        val reading = "キッッテモッッテ"
        val normal = Candidate("切手持って", reading, segments = listOf(
            segment("キッッテ", "切手", 1, 0, 4), segment("モッッテ", "持って", 1, 4, 8)))
        val literal = candidate("きっってもっって", reading)
        assertEquals(literal.text, ConversionCandidateMerge.mergeModelAndDictionary(literal, listOf(normal, literal), reading).first().text)
        val unknown = Candidate("文脈表記", reading, isAI = true)
        assertEquals(unknown.text, ConversionCandidateMerge.mergeModelAndDictionary(unknown, listOf(normal), reading).first().text)
    }

    @Test fun nativeMetadataRetainsOriginalConsumptionAndCorrectedLexicalReading() {
        fun hex(value: String) = value.toByteArray().joinToString("") { "%02x".format(it) }
        val encoded = "${hex("キッッテ")}:${hex("切手")}:1:1:1:-5:1:0:4:${hex("キッテ")}"
        val parsed = DictionarySegments.parse(encoded, "キッッテ", "切手").single()
        assertEquals("キッッテ", parsed.reading)
        assertEquals("キッテ", parsed.lexicalReading)
        assertEquals(4, parsed.originalEnd)
        assertFalse(Candidate("切手", "キッッテ", segments = listOf(parsed)).isLearningTarget)
        assertTrue(DictionarySegments.parse(encoded.replace(":0:4:", ":1:5:"), "キッッテ", "切手").isEmpty())
    }
}
