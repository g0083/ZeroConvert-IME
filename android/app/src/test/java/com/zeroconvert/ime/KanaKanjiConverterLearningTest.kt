package com.zeroconvert.ime

import com.zeroconvert.ime.azookey.input.ComposingText
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

class KanaKanjiConverterLearningTest {

    @Before
    fun setUp() {
        KanaKanjiConverter.clearLearningMemoryForTest()
    }

    @Test
    fun multipleSelectionsForSameReadingArePreservedInLRUOrder() {
        // 過去に「効果」を確定し、直近で「降下」を確定
        KanaKanjiConverter.recordSelectionForTest("こうか", "効果")
        KanaKanjiConverter.recordSelectionForTest("こうか", "降下")

        val surfaces = KanaKanjiConverter.getLearnedSurfaces("こうか")
        assertEquals(listOf("降下", "効果"), surfaces)
        assertEquals("降下", KanaKanjiConverter.getLearnedSurface("こうか"))
    }

    @Test
    fun reselectingExistingEntryBumpsItToTheTop() {
        KanaKanjiConverter.recordSelectionForTest("こうか", "効果")
        KanaKanjiConverter.recordSelectionForTest("こうか", "降下")
        KanaKanjiConverter.recordSelectionForTest("こうか", "硬化")
        assertEquals(listOf("硬化", "降下", "効果"), KanaKanjiConverter.getLearnedSurfaces("こうか"))

        // 再度「効果」を確定すると先頭へ昇格
        KanaKanjiConverter.recordSelectionForTest("こうか", "効果")
        assertEquals(listOf("効果", "硬化", "降下"), KanaKanjiConverter.getLearnedSurfaces("こうか"))
    }

    @Test
    fun maxOutputsPerRubyIsCappedAtFive() {
        for (i in 1..7) {
            KanaKanjiConverter.recordSelectionForTest("てすと", "候補$i")
        }
        val surfaces = KanaKanjiConverter.getLearnedSurfaces("てすと")
        assertEquals(5, surfaces.size)
        assertEquals(listOf("候補7", "候補6", "候補5", "候補4", "候補3"), surfaces)
    }

    @Test
    fun prefixMatchesAreSuggestedForTwoOrMoreChars() = runBlocking {
        KanaKanjiConverter.recordSelectionForTest("ぷろぐらみんぐ", "プログラミング")

        val converter = KanaKanjiConverter()
        val text = ComposingText()
        text.insertAtCursorPosition("ぷろ", inputStyle = com.zeroconvert.ime.azookey.input.InputStyle.DIRECT)

        val candidates = converter.requestCandidates(
            composingText = text,
            leftContext = "",
            enableAI = false,
            personalize = true
        )

        val hasSuggested = candidates.any { it.text == "プログラミング" }
        assertTrue("Expected 'プログラミング' to be suggested from learning memory prefix match", hasSuggested)

        val disabledCandidates = converter.requestCandidates(
            composingText = text,
            leftContext = "",
            enableAI = false,
            personalize = true,
            predictiveConversion = false
        )
        val hasSuggestedDisabled = disabledCandidates.any { it.text == "プログラミング" }
        assertFalse("Expected 'プログラミング' to be suppressed when predictive conversion is disabled", hasSuggestedDisabled)
    }
}
