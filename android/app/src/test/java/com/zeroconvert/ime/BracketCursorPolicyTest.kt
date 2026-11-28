package com.zeroconvert.ime

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class BracketCursorPolicyTest {
    @Test fun recognizesCommonBracketPairs() {
        for (pair in listOf("「」", "『』", "（）", "()", "【】", "［］", "[]", "｛｝", "{}", "“”", "\"\"")) {
            assertTrue("expected bracket pair: $pair", BracketCursorPolicy.isBracketPair(pair))
            assertTrue(BracketCursorPolicy.shouldMoveCursorInside(pair, trailingText = "", enabled = true))
        }
    }

    @Test fun rejectsSingleBracketsAndNormalWords() {
        for (text in listOf("「", "」", "（", "）", "あ", "かっこ", "（あ）", "")) {
            assertFalse("expected non-pair: $text", BracketCursorPolicy.isBracketPair(text))
            assertFalse(BracketCursorPolicy.shouldMoveCursorInside(text, trailingText = "", enabled = true))
        }
    }

    @Test fun respectsDisabledFlagAndTrailingText() {
        assertFalse(BracketCursorPolicy.shouldMoveCursorInside("「」", trailingText = "", enabled = false))
        assertFalse(BracketCursorPolicy.shouldMoveCursorInside("「」", trailingText = "。", enabled = true))
    }
}
