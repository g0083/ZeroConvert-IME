package com.zeroconvert.ime

import com.zeroconvert.ime.azookey.input.ComposingText
import com.zeroconvert.ime.azookey.input.InputStyle
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ComposingTextTest {

    @Test
    fun testBasicTyping() {
        val c = ComposingText()
        "kyouha".forEach { ch ->
            c.insertAtCursorPosition(ch.toString(), InputStyle.ROMAN2KANA)
        }
        assertEquals("きょうは", c.convertTarget)
        assertEquals(4, c.convertTargetCursorPosition)
    }

    @Test
    fun testLongSentenceNoTruncation() {
        val c = ComposingText()
        val text = "ashitanotenkigaharedattararyokounyikitaina"
        text.forEach { ch ->
            c.insertAtCursorPosition(ch.toString(), InputStyle.ROMAN2KANA)
        }
        assertEquals("あしたのてんきがはれだったらりょこうにぃきたいな", c.convertTarget)
    }

    @Test
    fun testDoubleConsonantSokuon() {
        val c = ComposingText()
        "gakkou".forEach { ch ->
            c.insertAtCursorPosition(ch.toString(), InputStyle.ROMAN2KANA)
        }
        assertEquals("がっこう", c.convertTarget)
    }

    @Test
    fun testHatsuonN() {
        val c = ComposingText()
        "kannna".forEach { ch ->
            c.insertAtCursorPosition(ch.toString(), InputStyle.ROMAN2KANA)
        }
        assertEquals("かんな", c.convertTarget)

        val c2 = ComposingText()
        "konnya".forEach { ch ->
            c2.insertAtCursorPosition(ch.toString(), InputStyle.ROMAN2KANA)
        }
        assertEquals("こんや", c2.convertTarget)

        val c3 = ComposingText()
        "kanji".forEach { ch ->
            c3.insertAtCursorPosition(ch.toString(), InputStyle.ROMAN2KANA)
        }
        assertEquals("かんじ", c3.convertTarget)
    }

    @Test
    fun testBackspaceDeletion() {
        val c = ComposingText()
        "kansha".forEach { ch ->
            c.insertAtCursorPosition(ch.toString(), InputStyle.ROMAN2KANA)
        }
        assertEquals("かんしゃ", c.convertTarget)

        // Delete 'ゃ'
        c.deleteBackwardFromCursorPosition(1)
        assertEquals("かんし", c.convertTarget)

        // Delete 'し'
        c.deleteBackwardFromCursorPosition(1)
        assertEquals("かん", c.convertTarget)
    }

    @Test
    fun testCursorMovementAndInsertion() {
        val c = ComposingText()
        "ja".forEach { ch ->
            c.insertAtCursorPosition(ch.toString(), InputStyle.ROMAN2KANA)
        }
        assertEquals("じゃ", c.convertTarget)

        // Move cursor between 'じ' and 'ゃ'
        c.moveCursorFromCursorPosition(-1)
        assertEquals(1, c.convertTargetCursorPosition)

        // Insert 'u'
        c.insertAtCursorPosition("u", InputStyle.ROMAN2KANA)
        assertEquals("じうゃ", c.convertTarget)
    }

    @Test
    fun digitsJoinTheCompositionLikeTheWindowsClient() {
        // QWERTY digits now take the same path as Windows UserAction::Number ->
        // AppendText: they stay in the reading and conversion includes them.
        val c = ComposingText()
        "10kai".forEach { ch ->
            c.insertAtCursorPosition(ch.toString(), InputStyle.ROMAN2KANA)
        }
        assertEquals("10かい", c.convertTarget)
        assertEquals(4, c.convertTargetCursorPosition)
    }

    @Test
    fun punctuationJoinsTheReadingLikeTheWindowsDefault() {
        // Windows keeps punctuation in the composition unless punctuation_commit is on.
        val c = ComposingText()
        "kaigi".forEach { ch ->
            c.insertAtCursorPosition(ch.toString(), InputStyle.ROMAN2KANA)
        }
        c.insertAtCursorPosition("、", InputStyle.ROMAN2KANA)
        assertEquals("かいぎ、", c.convertTarget)
        assertEquals(4, c.convertTargetCursorPosition)
    }

    @Test
    fun digitAfterPendingNCommitsTheN() {
        // The romaji table's n+Any1 rule fires for digits too, matching Windows.
        val c = ComposingText()
        "san5".forEach { ch ->
            c.insertAtCursorPosition(ch.toString(), InputStyle.ROMAN2KANA)
        }
        assertEquals("さん5", c.convertTarget)
    }

    @Test
    fun digitsOnlyReadingsAreNotTranslationSources() {
        // Translation mode keeps digits/symbols-only readings idle, matching
        // the Windows kind=3 eligibility that requires kana in the reading.
        for (source in listOf("123", "1234-5678", "?!", "、。", "")) {
            assertFalse(
                "digits/symbols-only source must not translate: $source",
                ZeroConvertInputMethodService.translationSourceEligible(false, source)
            )
            assertFalse(ZeroConvertInputMethodService.hasJapaneseKana(source))
        }
        for (source in listOf("123あ", "会議", "アいう")) {
            assertTrue(
                "a source with kana stays translatable: $source",
                ZeroConvertInputMethodService.translationSourceEligible(false, source)
            )
        }
        // English sources keep the englishComposing path and stay eligible.
        assertTrue(ZeroConvertInputMethodService.translationSourceEligible(true, "123"))
        assertTrue(ZeroConvertInputMethodService.translationSourceEligible(true, "hello"))
    }

    @Test
    fun twelveKeyLiteralDigitReflectsIntoTheReading() {
        // The 12-key translation route appends digits as literals (DIRECT),
        // like the QWERTY path, so the reading mixes kana and digits. Flick
        // keys send kana directly (no pending romaji) before the digit.
        val c = ComposingText()
        for (kana in listOf("さ", "ん")) {
            c.insertAtCursorPosition(kana, InputStyle.DIRECT)
        }
        c.insertAtCursorPosition("1", InputStyle.DIRECT)
        assertEquals("さん1", c.convertTarget)
        assertTrue(ZeroConvertInputMethodService.hasJapaneseKana(c.convertTarget))
        // Digits and fullwidth punctuation route through the literal branch.
        for (ch in "0123456789、。？！") {
            assertTrue(ZeroConvertInputMethodService.isTranslationLiteralChar(ch))
        }
        assertFalse(
            "multi-char kana stays on the normal composition path",
            "あ".singleOrNull()?.let { ZeroConvertInputMethodService.isTranslationLiteralChar(it) } == true
        )
    }
}
