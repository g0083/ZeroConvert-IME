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

    @Test fun inlineSelectionKeepsCompletedKanjiWhenTrailingConsonantIsEchoed() {
        val kyouW = listOf(native("きょうw", "キョウw", 4), native("今日", "キョウ", 3))
        assertEquals("今日w", InlinePreviewPolicy.select(kyouW, "きょうw")?.previewForReading("きょうw"))

        val kyouSh = listOf(native("キョウsh", "キョウsh", 5), native("今日", "キョウ", 3))
        assertEquals("今日sh", InlinePreviewPolicy.select(kyouSh, "きょうsh")?.previewForReading("きょうsh"))

        val anmaR = listOf(native("あんまr", "アンマr", 4), native("案", "アン", 1), native("あんま", "アンマ", 3))
        assertEquals("あんまr", InlinePreviewPolicy.select(anmaR, "あんまr")?.previewForReading("あんまr"))
    }

    @Test fun inlineSelectionWithTrailingNHonorsCompletedNWordsWhileAvoidingEcho() {
        val kaN = listOf(native("勘", "カン", 2), native("かん", "カン", 2))
        assertEquals("勘", InlinePreviewPolicy.select(kaN, "かn")?.previewForReading("かn"))

        val paN = listOf(native("パン", "パン", 2), native("ぱn", "パn", 2))
        assertEquals("パン", InlinePreviewPolicy.select(paN, "ぱn")?.previewForReading("ぱn"))

        val kyouN = listOf(native("きょうん", "キョウn", 4), native("今日", "キョウ", 3))
        assertEquals("今日n", InlinePreviewPolicy.select(kyouN, "きょうn")?.previewForReading("きょうn"))
    }

    @Test fun tentativePrefixPreservesConvertedPrefixAcrossRomajiAndModifiers() {
        val history = listOf(InferenceSnapshot("きょう", "今日"), InferenceSnapshot("きょうは", "今日は"))

        // Trailing consonant resolves to kana ('w' -> 'わ').
        assertEquals(
            TentativePrefix("今日わ", "きょう", "今日"),
            InlinePreviewPolicy.tentativePrefix("きょうわ", "きょうw", "今日w", history)
        )
        // Same recovery works even before a shorter snapshot existed in history.
        assertEquals(
            TentativePrefix("今日わ", "きょう", "今日"),
            InlinePreviewPolicy.tentativePrefix("きょうわ", "きょうw", "今日w", emptyList())
        )
        // Multi-consonant typing ('きょうs' -> 'きょうしゃ').
        assertEquals(
            TentativePrefix("今日しゃ", "きょう", "今日"),
            InlinePreviewPolicy.tentativePrefix("きょうしゃ", "きょうs", "今日s", history)
        )
        // 12-key dakuten modifier ('きょうは' -> 'きょうば') and character toggle ('きょうか' -> 'きょうき').
        assertEquals(
            TentativePrefix("今日ば", "きょう", "今日"),
            InlinePreviewPolicy.tentativePrefix("きょうば", "きょうは", "今日は", history)
        )
        assertEquals(
            TentativePrefix("今日き", "きょう", "今日"),
            InlinePreviewPolicy.tentativePrefix("きょうき", "きょうか", "今日か", emptyList())
        )
    }

    @Test fun tentativePrefixPreservesHistoryOnBackspaceAndProtectsWholeStems() {
        val kyouHistory = listOf(
            InferenceSnapshot("きょ", "巨"),
            InferenceSnapshot("きょう", "今日"),
            InferenceSnapshot("きょうは", "今日は")
        )
        assertEquals(
            TentativePrefix("今日", "きょう", "今日"),
            InlinePreviewPolicy.tentativePrefix("きょう", "きょうは", "今日は", kyouHistory)
        )

        // When a shorter history snapshot matches at the same length, it beats suffix-chopping ('走る' -> '走').
        val hashiruHistory = listOf(
            InferenceSnapshot("はし", "橋"),
            InferenceSnapshot("はしる", "走る")
        )
        assertEquals(
            TentativePrefix("橋", "はし", "橋"),
            InlinePreviewPolicy.tentativePrefix("はし", "はしる", "走る", hashiruHistory)
        )

        // Surrogate pair prefix mismatch snaps back to code-point boundary without splitting UTF-16 pairs.
        assertEquals(
            TentativePrefix("a\uD842\uDFB8b", "a", "a"),
            InlinePreviewPolicy.tentativePrefix("a\uD842\uDFB8b", "a\uD842\uDFB7b", "a\uD842\uDFB7b", emptyList())
        )
        assertEquals(
            TentativePrefix("\uD842\uDFB8b", "", ""),
            InlinePreviewPolicy.tentativePrefix("\uD842\uDFB8b", "\uD842\uDFB7b", "\uD842\uDFB7b", emptyList())
        )
        assertEquals(
            TentativePrefix("あ", "", ""),
            InlinePreviewPolicy.tentativePrefix("あ", "かん", "漢", emptyList())
        )
    }

    @Test fun shouldSuppressPreviewDowngradeGuardsSameReadingAndTrailingConsonant() {
        assertTrue(InlinePreviewPolicy.shouldSuppressPreviewDowngrade("とうきょう", "とうきょう", "東京", "とうきょう"))
        assertTrue(InlinePreviewPolicy.shouldSuppressPreviewDowngrade("きょう", "きょうw", "今日", "きょうw"))
        assertTrue(InlinePreviewPolicy.shouldSuppressPreviewDowngrade("きょうs", "きょうsh", "今日s", "きょうsh"))
        assertTrue(InlinePreviewPolicy.shouldSuppressPreviewDowngrade("ぱん", "ぱんk", "パン", "ぱんk"))

        assertFalse(InlinePreviewPolicy.shouldSuppressPreviewDowngrade("か", "かn", "蚊", "かん"))
        assertFalse(InlinePreviewPolicy.shouldSuppressPreviewDowngrade("きょうw", "きょうわ", "今日w", "きょうわ"))
    }
}
