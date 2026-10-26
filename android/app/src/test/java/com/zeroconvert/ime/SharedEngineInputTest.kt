package com.zeroconvert.ime

import com.zeroconvert.ime.azookey.input.*
import com.zeroconvert.ime.engine.engineInputElements
import org.junit.Assert.*
import org.junit.Test

class SharedEngineInputTest {
    @Test fun nativePartialCandidatePreservesTheUnconsumedReadingInPreviewAndCommit() {
        val input = ComposingText().apply { insertAtCursorPosition("toukyouniiku", InputStyle.ROMAN2KANA) }
        val candidate = Candidate("東京", "トウキョウ", engineConsumedReading = 5)
        assertEquals("東京にいく", candidate.previewForReading(input.convertTarget))
        input.prefixComplete(checkNotNull(candidate.engineConsumedReading))
        assertEquals("にいく", input.convertTarget)
    }
    @Test fun supplementaryCharactersCrossTheBridgeAsWholeUtf8Characters() {
        val input = ComposingText()
        input.insertAtCursorPosition("😀日本", InputStyle.DIRECT)
        val encoded = engineInputElements(input)
        assertEquals("😀日本", encoded.single().text)
        assertEquals(3, encoded.single().text.codePointCount(0, encoded.single().text.length))
    }

    @Test fun rawRomanizationAndFlickInputKeepTheirIndependentStyles() {
        val input = ComposingText()
        input.insertAtCursorPosition("kyo", InputStyle.ROMAN2KANA)
        input.insertAtCursorPosition("う", InputStyle.DIRECT)
        val encoded = engineInputElements(input)
        assertEquals(listOf("kyo", "う"), encoded.map { it.text })
        assertEquals(listOf("ROMAN2KANA", "DIRECT"), encoded.map { it.style })
        assertEquals("きょう", input.convertTarget)
    }

    @Test fun deletedAndReenteredInputRetainsTheCompositionSeparator() {
        val input = ComposingText()
        input.insertAtCursorPosition("kya", InputStyle.ROMAN2KANA)
        input.moveCursorFromCursorPosition(-1)
        input.insertAtCursorPosition("ん", InputStyle.DIRECT)
        val encoded = engineInputElements(input)
        assertTrue(encoded.any { it.separator })
        assertEquals(input.input.count { it.piece is InputPiece.CompositionSeparator }, encoded.count { it.separator })
        assertEquals(input.input.mapNotNull { (it.piece as? InputPiece.CharPiece)?.char }.joinToString(""),
            encoded.joinToString("") { it.text })
    }
}
