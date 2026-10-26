package com.zeroconvert.ime

import org.junit.Assert.*
import org.junit.Test

class EditorContextTest {
    @Test fun aCursorInsideTheReadingStripsOnlyTheVisiblePrefix() {
        assertEquals("前文", EditorContext.confirmedLeft("前文とうきょう", "とうきょうにいく", 5))
        assertEquals("前文", EditorContext.confirmedLeft("前文", "とうきょうにいく", 0))
    }

    @Test fun aConvertedPrefixUsesItsSurfaceLengthAndStillDetectsDocumentChanges() {
        assertEquals("前文", EditorContext.confirmedLeft("前文東京", "東京にいく", 2))
        assertEquals("別文", EditorContext.confirmedLeft("別文東京", "東京にいく", 2))
        assertNull(EditorContext.confirmedLeft("前文大阪", "東京にいく", 2))
    }

    @Test fun invalidPreviewCursorAndSplitSurrogatesAreRejected() {
        assertNull(EditorContext.confirmedLeft("かな", "かな", -1))
        assertNull(EditorContext.confirmedLeft("かな", "かな", 3))
        assertNull(EditorContext.confirmedLeft("あ😀", "あ😀い", 2))
        assertEquals("前文", EditorContext.confirmedLeft("前文あ😀", "あ😀い", 3))
    }

    @Test fun aChangedDocumentIsDetectedEvenWhenTheCompositionAndCursorAreUnchanged() {
        val original = EditorContext.confirmedLeft("東京へ今日は", "今日は")
        val changed = EditorContext.confirmedLeft("大阪へ今日は", "今日は")
        assertEquals("東京へ", original)
        assertNotEquals(original, changed)
        assertEquals(original, EditorContext.confirmedLeft("東京へきょうは", "きょうは"))
    }

    @Test fun missingOrReplacedCompositionCannotBeMistakenForConfirmedContext() {
        assertNull(EditorContext.confirmedLeft(null, "入力"))
        assertNull(EditorContext.confirmedLeft("前文変更済み", "入力"))
        assertEquals("前文", EditorContext.confirmedLeft("前文", ""))
    }

    @Test fun leftContextKeepsLongRangeInformationWithoutSplittingSupplementaryCharacters() {
        val prefix = "😀".repeat(520)
        val left = EditorContext.confirmedLeft(prefix + "かな", "かな")!!
        assertEquals(512, left.codePointCount(0, left.length))
        assertEquals("😀".repeat(512), left)
    }
}
