package com.zeroconvert.ime

import org.junit.Assert.*
import org.junit.Test

class ConversionCommitLearningTest {
    private val normal = Candidate("漢字", "カンジ")

    @Test fun normalConfirmationUsesTheCapturedCandidateEvenAfterTheListChanges() {
        val candidates = mutableListOf(normal)
        val captured = ConversionCommitLearning.capture("かんじ", "漢字", candidates.first(), canWrite = true)
        candidates.clear()
        candidates.add(Candidate("感じ", "カンジ"))
        assertEquals(ConversionCommitLearning("かんじ", "漢字"), captured)
    }

    @Test fun punctuationIsCommittedButNotAddedToTheLearnedSurface() {
        val captured = ConversionCommitLearning.capture("かんじ", "漢字。", normal, trailingText = "。", canWrite = true)
        assertEquals("漢字", captured?.surface)
        assertNull(ConversionCommitLearning.capture("かんじ", "漢字。", normal, canWrite = true))
        assertNull(ConversionCommitLearning.capture("かんじ", "記号", normal, canWrite = true))
    }

    @Test fun missingOrStaleCandidatesCannotTeachAnUnrelatedReading() {
        assertNull(ConversionCommitLearning.capture("かんじ", "漢字", null, canWrite = true))
        assertNull(ConversionCommitLearning.capture("べつ", "漢字", normal, canWrite = true))
        assertNull(ConversionCommitLearning.capture("", "漢字", normal, canWrite = true))
        assertNotNull(ConversionCommitLearning.capture("かんじ", "漢字", normal.copy(isAI = true), canWrite = true))
    }

    @Test fun privacyReadOnlyAndCorrectedCandidatesCannotWriteNormalHistory() {
        assertNull(ConversionCommitLearning.capture("かんじ", "漢字", normal, sensitive = true, canWrite = true))
        assertNull(ConversionCommitLearning.capture("かんじ", "漢字", normal, canWrite = false))
        assertNull(ConversionCommitLearning.capture("かんじ", "漢字", normal, correctionApplied = true, canWrite = true))
        assertNull(ConversionCommitLearning.capture("かんじ", "漢字", normal.copy(learningAllowed = false), canWrite = true))
        val corrected = normal.copy(segments = listOf(DictionarySegment("カンジ", "漢字", 1288, 1288, 501, -5f, correctionKind = 3)))
        assertNull(ConversionCommitLearning.capture("かんじ", "漢字", corrected, canWrite = true))
    }

    @Test fun onlyAlignedMorphologyIsCapturedAndItsListIsCopiedBeforeCommit() {
        val spans = mutableListOf(DictionarySegment("カンジ", "漢字", 1288, 1288, 501, -5f))
        val selected = normal.copy(segments = spans)
        val captured = ConversionCommitLearning.capture("かんじ", "漢字", selected, canWrite = true)!!
        spans.clear()
        assertEquals("漢字", captured.segments.single().surface)
        val mismatched = normal.copy(segments = listOf(DictionarySegment("ベツ", "別", 1288, 1288, 501, -5f)))
        assertTrue(ConversionCommitLearning.capture("かんじ", "漢字", mismatched, canWrite = true)!!.segments.isEmpty())
    }
}
