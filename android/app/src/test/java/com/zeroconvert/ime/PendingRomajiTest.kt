package com.zeroconvert.ime

import com.zeroconvert.ime.assist.*
import com.zeroconvert.ime.azookey.input.ComposingText
import com.zeroconvert.ime.azookey.input.InputStyle
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class PendingRomajiTest {
    private fun input(raw: String) = ComposingText().apply {
        raw.forEach { insertAtCursorPosition(it.toString(), InputStyle.ROMAN2KANA) }
    }
    private val neverInfer = object : AssistInference {
        override val revision = 1L
        override fun complete(model: AssistModel, prompt: String, maxTokens: Int, timeoutMs: Int): ModelCompletion =
            error("Unfinished romaji reached $model")
    }

    @Test fun nasalPrefixesRemainEditableUntilTheNextVowel() {
        for ((raw, kana) in listOf("nan" to "な", "non" to "の", "man" to "ま")) {
            val composing = input(raw)
            assertEquals(kana + "n", composing.convertTarget)
            assertTrue(composing.convertTarget.hasPendingRomaji())
            composing.insertAtCursorPosition("i", InputStyle.ROMAN2KANA)
            assertEquals(kana + "に", composing.convertTarget)
            assertFalse(composing.convertTarget.hasPendingRomaji())
        }
    }

    @Test fun allKanaRowsAndPendingNRefuseCorrectionAndPredictionBeforeNativeDispatch() {
        for (prefix in listOf("a", "ka", "sa", "ta", "na", "ha", "ma", "ya", "ra", "wa", "ga", "za", "da", "ba", "pa", "no")) {
            val reading = input(prefix + "n").convertTarget
            assertTrue(reading.hasPendingRomaji())
            assertNull(ModelCorrector(neverInfer).suggest("", reading, "候補"))
            assertNull(ModelPredictor(neverInfer).suggest("", reading, "候補"))
        }
    }

    @Test fun everyTerminalLatinLetterIsProtectedIncludingCapitalN() {
        for (letter in ('a'..'z') + ('A'..'Z')) {
            val reading = "かな$letter"
            assertTrue(reading.hasPendingRomaji())
            assertNull(ModelCorrector(neverInfer).suggest("", reading, "候補"))
            assertNull(ModelPredictor(neverInfer).suggest("", reading, "候補"))
        }
    }

    @Test fun realInternalConsonantTyposAreStillEligible() {
        assertFalse("おkせり".hasPendingRomaji())
        assertFalse("あrがとう".hasPendingRomaji())
        assertFalse("なん".hasPendingRomaji())
        assertFalse("なに".hasPendingRomaji())
    }

    @Test fun aLateAutomaticResultCannotReplacePendingInputOrMoveTheCursor() {
        val composing = input("nan")
        val original = composing.clone()
        val session = CorrectionSession(RustAssistPolicy)
        assertFalse(session.apply(composing, "晩", "何", explicit = false))
        assertEquals(original.input, composing.input)
        assertEquals(original.convertTarget, composing.convertTarget)
        assertEquals(original.convertTargetCursorPosition, composing.convertTargetCursorPosition)
        assertFalse(session.isApplied)
    }

    @Test fun learnedEvidenceCannotOverridePendingRomajiProtection() {
        assertFalse(AutoCorrectionEvidence.permits("なn", "晩", LearnedMatch("なn", "晩", 30, 30)) { "ばん" })
    }

    @Test fun explicitAcceptanceAndCompletedKanaCorrectionStillWork() {
        assertTrue(CorrectionSession(RustAssistPolicy).apply(input("nan"), "何", "なn", explicit = true))
        val completed = input("gakou")
        assertTrue(CorrectionSession(RustAssistPolicy).apply(completed, "学校", "がこう", explicit = false))
    }

    @Test fun deletingAndReenteringThePendingConsonantPreservesRawInput() {
        for ((raw, kana) in listOf("nan" to "な", "non" to "の", "man" to "ま")) {
            val composing = input(raw)
            composing.deleteBackwardFromCursorPosition(1)
            assertEquals(kana, composing.convertTarget)
            composing.insertAtCursorPosition("ni", InputStyle.ROMAN2KANA)
            assertEquals(kana + "に", composing.convertTarget)
        }
    }

    @Test fun streamedAssistPublishesNoSuggestionsForPendingInput() = runBlocking {
        AiAssistManager.streamAiAssist("なn", "何", "", true, neverInfer, policy = RustAssistPolicy) { update ->
            assertTrue(update.suggestions.isEmpty())
            assertNull(update.automaticCorrection)
            assertNull(update.selected)
        }
    }
}
