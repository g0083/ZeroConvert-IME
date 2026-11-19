package com.zeroconvert.ime

import org.junit.Assert.*
import org.junit.Test

class ExpandedCandidateLayoutTest {
    @Test fun emptyCandidatesProduceNoRows() {
        assertTrue(ExpandedCandidateLayout.packRows(emptyList(), emptyList(), 320, 4).isEmpty())
    }

    @Test fun packsMultipleShortCandidatesIntoOneRow() {
        val candidates = listOf("あ", "愛", "会", "合", "相", "青", "赤").map { Candidate(it, "あ") }
        val widths = List(candidates.size) { 44 }
        // 44 * 6 + 4 * 5 = 284 <= 320, 7th would be 284 + 4 + 44 = 332 > 320
        val rows = ExpandedCandidateLayout.packRows(candidates, widths, maxRowWidthPx = 320, horizontalGapPx = 4)
        assertEquals(2, rows.size)
        assertEquals(listOf(0, 1, 2, 3, 4, 5), rows[0].map { it.index })
        assertEquals(listOf(6), rows[1].map { it.index })
    }

    @Test fun wideCandidateGetsOwnRowAndPreservesOrder() {
        val candidates = listOf(
            Candidate("短い", "みじかい"),
            Candidate("とても長い文節の変換候補テキスト", "ながい"),
            Candidate("次", "つぎ")
        )
        val rows = ExpandedCandidateLayout.packRows(
            candidates = candidates,
            itemWidthsPx = listOf(60, 450, 44),
            maxRowWidthPx = 300,
            horizontalGapPx = 4
        )
        assertEquals(3, rows.size)
        assertEquals(listOf("短い"), rows[0].map { it.candidate.text })
        assertEquals(listOf("とても長い文節の変換候補テキスト"), rows[1].map { it.candidate.text })
        assertEquals(listOf("次"), rows[2].map { it.candidate.text })
    }

    @Test fun barPolicyRestoresToolbarAndPlacementMenuWhenCandidatesAreExpanded() {
        val collapsed = CandidateBarDisplayPolicy.resolve(
            hasCandidates = true,
            isCandidatesExpanded = false,
            hasSelectionCount = false,
            translationActive = false,
            selectionTranslationActive = false,
            clipboardSuggestionDismissed = false
        )
        assertEquals(CandidateBarContentMode.CANDIDATE_STRIP, collapsed.mode)
        assertFalse(collapsed.showClipboardSuggestion)
        assertFalse(collapsed.showKeyboardPlacementMenu)
        assertTrue(collapsed.showCandidateToggle)
        assertFalse(collapsed.isCandidateToggleExpanded)

        val expanded = CandidateBarDisplayPolicy.resolve(
            hasCandidates = true,
            isCandidatesExpanded = true,
            hasSelectionCount = false,
            translationActive = false,
            selectionTranslationActive = false,
            clipboardSuggestionDismissed = false
        )
        assertEquals(CandidateBarContentMode.IDLE_TOOLBAR, expanded.mode)
        assertTrue(expanded.showClipboardSuggestion)
        assertTrue(expanded.showKeyboardPlacementMenu)
        assertTrue(expanded.showCandidateToggle)
        assertTrue(expanded.isCandidateToggleExpanded)
    }

    @Test fun barPolicyHidesToggleWhenNoCandidatesAndPrioritizesTranslation() {
        val empty = CandidateBarDisplayPolicy.resolve(
            hasCandidates = false,
            isCandidatesExpanded = true,
            hasSelectionCount = false,
            translationActive = false,
            selectionTranslationActive = false,
            clipboardSuggestionDismissed = false
        )
        assertEquals(CandidateBarContentMode.IDLE_TOOLBAR, empty.mode)
        assertTrue(empty.showClipboardSuggestion)
        assertTrue(empty.showKeyboardPlacementMenu)
        assertFalse(empty.showCandidateToggle)
        assertFalse(empty.isCandidateToggleExpanded)

        val translating = CandidateBarDisplayPolicy.resolve(
            hasCandidates = true,
            isCandidatesExpanded = true,
            hasSelectionCount = false,
            translationActive = true,
            selectionTranslationActive = false,
            clipboardSuggestionDismissed = false
        )
        assertEquals(CandidateBarContentMode.TRANSLATION, translating.mode)
        assertFalse(translating.showClipboardSuggestion)
        assertFalse(translating.showKeyboardPlacementMenu)
        assertFalse(translating.showCandidateToggle)
    }
}
