package com.zeroconvert.ime

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class QwertyNumberRowTest {
    @Test fun topRowLettersMapToDigitsInOrder() {
        assertEquals('1', QwertyNumberRow.digitFor('q'))
        assertEquals('2', QwertyNumberRow.digitFor('w'))
        assertEquals('3', QwertyNumberRow.digitFor('e'))
        assertEquals('4', QwertyNumberRow.digitFor('r'))
        assertEquals('5', QwertyNumberRow.digitFor('t'))
        assertEquals('6', QwertyNumberRow.digitFor('y'))
        assertEquals('7', QwertyNumberRow.digitFor('u'))
        assertEquals('8', QwertyNumberRow.digitFor('i'))
        assertEquals('9', QwertyNumberRow.digitFor('o'))
        assertEquals('0', QwertyNumberRow.digitFor('p'))
    }

    @Test fun uppercaseLabelsStillResolveTheirDigit() {
        assertEquals('1', QwertyNumberRow.digitFor('Q'))
        assertEquals('0', QwertyNumberRow.digitFor('P'))
    }

    @Test fun otherRowsHaveNoFlickDigit() {
        assertNull(QwertyNumberRow.digitFor('a'))
        assertNull(QwertyNumberRow.digitFor('l'))
        assertNull(QwertyNumberRow.digitFor('m'))
        assertNull(QwertyNumberRow.digitFor('ー'))
    }

    @Test fun shiftedSymbolsFollowTheSameTopRowOrder() {
        assertEquals('!', QwertyNumberRow.shiftedSymbolFor('q'))
        assertEquals('@', QwertyNumberRow.shiftedSymbolFor('w'))
        assertEquals('(', QwertyNumberRow.shiftedSymbolFor('o'))
        assertEquals(')', QwertyNumberRow.shiftedSymbolFor('p'))
        assertNull(QwertyNumberRow.shiftedSymbolFor('a'))
    }

    @Test fun digitsComposeWhilePunctuationStillCommits() {
        // Digits join the composition (Windows UserAction::Number parity)...
        for (digit in '0'..'9') {
            assertFalse(ZeroConvertInputMethodService.isImmediateCommitChar(digit))
            assertTrue(ZeroConvertInputMethodService.isTranslationLiteralChar(digit))
        }
        // ...while punctuation and shifted symbols keep committing immediately.
        for (punct in listOf('、', '。', '.', ',', '?', '!', '？', '！', '．', '，')) {
            assertTrue(ZeroConvertInputMethodService.isImmediateCommitChar(punct))
        }
        for (symbol in "!@#$%^&*()") {
            assertTrue(ZeroConvertInputMethodService.isImmediateCommitChar(symbol))
        }
    }
}
