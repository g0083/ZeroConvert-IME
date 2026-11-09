package com.zeroconvert.ime

import org.junit.Assert.assertEquals
import org.junit.Test

class PanelTextCommitTest {
    private fun decide(
        translationMode: Boolean = false,
        committingTranslation: Boolean = false,
        compositionEmpty: Boolean = false,
        englishBufferNonEmpty: Boolean = false,
        candidateMatches: Boolean = false
    ) = PanelTextCommit.decide(
        translationMode, committingTranslation, compositionEmpty, englishBufferNonEmpty, candidateMatches)

    @Test fun emptyCompositionCommitsThePanelTextDirectly() {
        assertEquals(PanelTextCommitDecision.Direct, decide(compositionEmpty = true, candidateMatches = true))
    }

    @Test fun englishBufferKeepsItsOwnCommitPathEvenWhenTheTextMatchesACandidate() {
        assertEquals(PanelTextCommitDecision.Direct, decide(englishBufferNonEmpty = true, candidateMatches = true))
    }

    @Test fun translationModeAppendsTheLiteralUnlessTheTranslationItselfIsCommitting() {
        assertEquals(PanelTextCommitDecision.Direct, decide(translationMode = true))
        assertEquals(PanelTextCommitDecision.Direct, decide(translationMode = true, compositionEmpty = true))
        assertEquals(PanelTextCommitDecision.AdoptCandidate, decide(translationMode = true, committingTranslation = true, candidateMatches = true))
        assertEquals(PanelTextCommitDecision.ConfirmThenAppend, decide(translationMode = true, committingTranslation = true))
    }

    @Test fun matchingCandidateIsAdoptedWithItsLearningEvent() {
        assertEquals(PanelTextCommitDecision.AdoptCandidate, decide(candidateMatches = true))
    }

    @Test fun unmatchedPanelTextConfirmsTheReadingBeforeAppending() {
        assertEquals(PanelTextCommitDecision.ConfirmThenAppend, decide())
    }

    @Test fun trailingTextNeverEntersTheLearnedSurface() {
        val candidate = Candidate("漢字", "カンジ")
        assertEquals("漢字", ConversionCommitLearning.capture("かんじ", "漢字。", candidate, trailingText = "。", canWrite = true)?.surface)
        assertEquals("漢字", ConversionCommitLearning.capture("かんじ", "漢字!", candidate, trailingText = "!", canWrite = true)?.surface)
    }
}