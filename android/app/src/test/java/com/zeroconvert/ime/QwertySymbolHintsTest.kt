package com.zeroconvert.ime

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class QwertySymbolHintsTest {
    @Test fun row1MapsDigitsWhenNumberRowHiddenAndSymbolsWhenShown() {
        assertEquals('1', QwertySymbolHints.secondarySymbolFor('q', isJapanese = true, showNumberRow = false, longPressSymbolsEnabled = false))
        assertEquals('!', QwertySymbolHints.secondarySymbolFor('q', isJapanese = true, showNumberRow = false, longPressSymbolsEnabled = false, shifted = true))
        assertEquals('!', QwertySymbolHints.secondarySymbolFor('q', isJapanese = true, showNumberRow = true, longPressSymbolsEnabled = true))
        assertNull(QwertySymbolHints.secondarySymbolFor('q', isJapanese = true, showNumberRow = true, longPressSymbolsEnabled = false))
    }

    @Test fun row2AndRow3RespectLongPressSettingAndLanguage() {
        assertEquals('@', QwertySymbolHints.secondarySymbolFor('a', isJapanese = true, showNumberRow = false, longPressSymbolsEnabled = true))
        assertEquals('￥', QwertySymbolHints.secondarySymbolFor('d', isJapanese = true, showNumberRow = false, longPressSymbolsEnabled = true))
        assertEquals('$', QwertySymbolHints.secondarySymbolFor('d', isJapanese = false, showNumberRow = false, longPressSymbolsEnabled = true))
        assertEquals('〜', QwertySymbolHints.secondarySymbolFor('ー', isJapanese = true, showNumberRow = false, longPressSymbolsEnabled = true))
        assertEquals('?', QwertySymbolHints.secondarySymbolFor('m', isJapanese = true, showNumberRow = false, longPressSymbolsEnabled = true))
        assertNull(QwertySymbolHints.secondarySymbolFor('a', isJapanese = true, showNumberRow = false, longPressSymbolsEnabled = false))
        assertNull(QwertySymbolHints.secondarySymbolFor('m', isJapanese = false, showNumberRow = false, longPressSymbolsEnabled = false))
    }

    @Test fun periodPopupProvidesLanguageSpecificQuickSymbols() {
        val jp = QwertySymbolHints.periodPopupSymbols(isJapanese = true)
        val en = QwertySymbolHints.periodPopupSymbols(isJapanese = false)
        assertEquals("。", jp.first())
        assertTrue("？" in jp && "！" in jp && "「」" in jp)
        assertEquals(".", en.first())
        assertTrue("?" in en && "!" in en && "," in en)
    }
}
