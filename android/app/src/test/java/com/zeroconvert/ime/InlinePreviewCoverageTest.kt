package com.zeroconvert.ime

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class InlinePreviewCoverageTest {
    private fun native(text: String, reading: String, consumed: Int) =
        Candidate(text, reading, engineConsumedReading = consumed)

    @Test fun aCandidateThatConsumesPartOfTheReadingDoesNotCoverIt() {
        assertFalse(native("安間", "アンマリ", 3).coversReading("あんまり"))
        assertFalse(native("祖", "ソ", 1).coversReading("そう"))
    }

    @Test fun aCandidateThatConsumesTheWholeReadingCoversIt() {
        assertTrue(native("あんまり", "アンマリ", 4).coversReading("あんまり"))
        assertTrue(native("そう", "ソウ", 2).coversReading("そう"))
    }

    @Test fun localCandidatesCoverTheirReadingBecauseTheyCarryNoConsumedCount() {
        assertTrue(Candidate("安間り", "アンマリ").coversReading("あんまり"))
        assertTrue(Candidate("祖う", "ソウ").coversReading("そう"))
    }

    @Test fun thePartialCandidatePaddingStillAppliesWhenItIsTheOnlyOption() {
        assertEquals("安間り", native("安間", "アンマリ", 3).previewForReading("あんまり"))
        assertEquals("祖う", native("祖", "ソ", 1).previewForReading("そう"))
    }

    @Test fun coveringCandidatesPreviewTheirOwnSurface() {
        assertEquals("あんまり", native("あんまり", "アンマリ", 4).previewForReading("あんまり"))
        assertEquals("そう", native("そう", "ソウ", 2).previewForReading("そう"))
    }

    @Test fun inlineSelectionPrefersTheFullReadingCandidateOverTheTopPartialOne() {
        // The engine ranks 祖 first for そ and 按摩 first for あんま.
        val sour = listOf(native("祖", "ソ", 1), native("そう", "ソウ", 2))
        assertEquals("そう", InlinePreviewPolicy.select(sour, "そう")?.previewForReading("そう"))
        val anmari = listOf(native("按摩", "アンマ", 3), native("あんまり", "アンマリ", 4))
        assertEquals("あんまり", InlinePreviewPolicy.select(anmari, "あんまり")?.previewForReading("あんまり"))
    }

    @Test fun inlineSelectionFallsBackToAPartialCandidateWhenNoFullOneExists() {
        val selected = InlinePreviewPolicy.select(listOf(native("祖", "ソ", 1)), "そう")
        assertEquals("祖う", selected?.previewForReading("そう"))
    }

    @Test fun inlineSelectionIsNullForAnEmptyCandidateList() {
        assertNull(InlinePreviewPolicy.select(emptyList(), "そう"))
    }

    @Test fun typingMoreKanaIsNotTreatedAsADowngrade() {
        // 按摩 shown for あんま, then り arrives: the longer reading is a new inference.
        assertFalse(
            InlinePreviewPolicy.isSameReadingKanaDowngrade(
                previousReading = "あんま",
                reading = "あんまり",
                previousPreview = "按摩り",
                preview = "あんまり"
            )
        )
        assertFalse(
            InlinePreviewPolicy.isSameReadingKanaDowngrade(
                previousReading = "そ",
                reading = "そう",
                previousPreview = "祖う",
                preview = "そう"
            )
        )
    }

    @Test fun reConvertingTheSameReadingStillKeepsAnEstablishedKanji() {
        assertTrue(
            InlinePreviewPolicy.isSameReadingKanaDowngrade(
                previousReading = "とうきょう",
                reading = "とうきょう",
                previousPreview = "東京",
                preview = "とうきょう"
            )
        )
    }

    @Test fun aKanaOnlyResultIsNeverADowngrade() {
        assertFalse(
            InlinePreviewPolicy.isSameReadingKanaDowngrade(
                previousReading = "そう",
                reading = "そう",
                previousPreview = "そう",
                preview = "そう"
            )
        )
    }
}
