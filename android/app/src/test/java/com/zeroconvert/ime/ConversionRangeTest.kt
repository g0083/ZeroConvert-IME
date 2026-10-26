package com.zeroconvert.ime

import com.zeroconvert.ime.azookey.input.ComposingText
import com.zeroconvert.ime.azookey.input.InputStyle
import com.zeroconvert.ime.assist.*
import org.junit.Assert.*
import org.junit.Test

class ConversionRangeTest {
    private fun roman(text: String) = ComposingText().apply { insertAtCursorPosition(text, InputStyle.ROMAN2KANA) }
    private fun direct(text: String) = ComposingText().apply { insertAtCursorPosition(text, InputStyle.DIRECT) }

    @Test fun boundaryCanShrinkExpandAndCancelWithoutChangingRawKeystrokes() {
        val input = roman("toukyouniiku")
        val before = input.clone()
        val range = ConversionRangeSession()
        assertTrue(range.begin(input, "東京に行く"))
        assertEquals(before.convertTarget.dropLast(1), input.convertTargetBeforeCursor)
        assertTrue(range.adjust(input, -1))
        assertTrue(range.adjust(input, 1))
        assertTrue(range.adjust(input, 1))
        assertEquals(before.convertTarget, input.convertTargetBeforeCursor)
        assertFalse(range.adjust(input, 1))
        assertEquals(before.input, input.input)
        assertEquals("東京に行く", range.originalPreview)
        assertTrue(range.cancel(input))
        assertEquals(before.input, input.input)
        assertEquals(before.convertTargetCursorPosition, input.convertTargetCursorPosition)
    }

    @Test fun partialCommitPreservesUnconsumedRomajiAndNextInsertion() {
        val input = roman("toukyounii")
        val range = ConversionRangeSession()
        range.begin(input, "東京にい")
        while (input.convertTargetBeforeCursor.length > "とうきょう".length) range.adjust(input, -1)
        assertEquals("とうきょう", input.convertTargetBeforeCursor)
        val reading = input.convertTargetBeforeCursor
        val candidate = Candidate("東京", "トウキョウ")
        assertEquals(listOf(candidate), ConversionRangeSession.candidatesForRange(input, listOf(candidate)))
        input.prefixComplete(reading.length)
        range.clear()
        assertEquals("にい", input.convertTarget)
        input.insertAtCursorPosition("ku", InputStyle.ROMAN2KANA)
        assertEquals("にいく", input.convertTarget)
        input.deleteBackwardFromCursorPosition(1)
        assertEquals("にい", input.convertTarget)
    }

    @Test fun rangeDoesNotSplitSurrogatePairsAndCannotBecomeEmpty() {
        val input = direct("あ😀い")
        val range = ConversionRangeSession()
        range.begin(input, input.convertTarget)
        assertEquals("あ😀", input.convertTargetBeforeCursor)
        range.adjust(input, -1)
        assertEquals("あ", input.convertTargetBeforeCursor)
        assertFalse(range.adjust(input, -1))
        range.adjust(input, 1)
        assertEquals("あ😀", input.convertTargetBeforeCursor)
    }

    @Test fun splittingADigraphKeepsTheUnconsumedKana() {
        val input = roman("kyaku")
        val range = ConversionRangeSession()
        range.begin(input, "客")
        range.adjust(input, -1)
        assertEquals("き", input.convertTargetBeforeCursor)
        input.prefixComplete(1)
        range.clear()
        assertEquals("ゃく", input.convertTarget)
        input.insertAtCursorPosition("e", InputStyle.ROMAN2KANA)
        assertEquals("ゃくえ", input.convertTarget)
    }

    @Test fun rangeCandidatesExcludeCompletionsAssistAndMismatchedReadings() {
        val input = direct("とうきょうに")
        val range = ConversionRangeSession()
        range.begin(input, "東京に")
        val exact = Candidate("東京", "トウキョウ")
        val completion = Candidate("東京駅", "トウキョウエキ")
        val assist = exact.copy(assist = AiAssistResult(AiAssistType.TYPO_CORRECTION, "東京", "補正"))
        assertEquals(listOf(exact), ConversionRangeSession.candidatesForRange(input,
            listOf(exact, completion, assist, Candidate("東\n京", "トウキョウ"))))
    }

    @Test fun editingTheSelectedPrefixKeepsTheSuffixInBothDisplayModes() {
        val input = direct("とうきょうにいく")
        val range = ConversionRangeSession()
        range.begin(input, "東京に行く")
        while (input.convertTargetBeforeCursor.length > "とうきょう".length) range.adjust(input, -1)
        assertEquals("東京にいく", ConversionRangeSession.preview(input, "東京", true))
        assertEquals("とうきょうにいく", ConversionRangeSession.preview(input, "東京", false))
        range.clear()
        input.insertAtCursorPosition("え", InputStyle.DIRECT)
        assertEquals("とうきょうえにいく", ConversionRangeSession.preview(input, "とうきょうえ", true))
    }

    @Test fun changedOrClearedInputCannotRestoreTheOldSnapshot() {
        val input = roman("nihongo")
        val range = ConversionRangeSession()
        range.begin(input, "日本語")
        input.insertAtCursorPosition("a", InputStyle.ROMAN2KANA)
        assertFalse(range.adjust(input, 1))
        assertFalse(range.cancel(input))
        assertFalse(range.active)
        assertTrue(input.convertTarget.contains("あ"))
    }

    @Test fun invalidRangeAndOldRequestModeAreRejected() {
        val range = ConversionRangeSession()
        assertFalse(range.begin(direct("あ"), "あ"))
        assertFalse(range.begin(direct("あ".repeat(129)), ""))
        val input = roman("nihongo")
        input.moveCursorFromCursorPosition(-1)
        assertFalse(range.begin(input, ""))
        val gate = AssistRequestGate()
        val key = AssistRequestGate.Key("にほんご", 4, "", "", 0, true)
        val ticket = gate.capture(key)
        assertFalse(gate.isCurrent(ticket, key.copy(conversionRange = true)))
    }
}
