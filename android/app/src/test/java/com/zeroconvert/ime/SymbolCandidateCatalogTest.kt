package com.zeroconvert.ime

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SymbolCandidateCatalogTest {

    @Test
    fun halfwidthParenthesesProduceBracketPairCandidatesIncludingSumitsuki() {
        val symbols = SymbolCandidateCatalog.symbolsFor("()")
        assertEquals("()", symbols.first())
        assertTrue(symbols.contains("（）"))
        assertTrue(symbols.contains("【】"))
        assertTrue(symbols.contains("「」"))
        assertTrue(symbols.contains("『』"))
        assertTrue(symbols.contains("[]"))
        assertTrue(symbols.contains("［］"))
    }

    @Test
    fun fullwidthParenthesesProduceBracketPairCandidatesIncludingSumitsuki() {
        val symbols = SymbolCandidateCatalog.symbolsFor("（）")
        assertEquals("（）", symbols.first())
        assertTrue(symbols.contains("()"))
        assertTrue(symbols.contains("【】"))
        assertTrue(symbols.contains("「」"))
        assertTrue(symbols.contains("『』"))
    }

    @Test
    fun singleOpenBracketSuggestsOtherOpenBracketsAndCommonPairs() {
        val symbols = SymbolCandidateCatalog.symbolsFor("（")
        assertEquals("（", symbols.first())
        assertTrue(symbols.contains("("))
        assertTrue(symbols.contains("「"))
        assertTrue(symbols.contains("【"))
        assertTrue(symbols.contains("『"))
        assertTrue(symbols.contains("（）"))
        assertTrue(symbols.contains("【】"))
        assertTrue(symbols.contains("「」"))
    }

    @Test
    fun numericPadSymbolsProduceExpectedRelatedCandidates() {
        val arrow = SymbolCandidateCatalog.symbolsFor("→")
        assertEquals("→", arrow.first())
        assertTrue(arrow.contains("←"))
        assertTrue(arrow.contains("↑"))
        assertTrue(arrow.contains("↓"))
        assertTrue(arrow.contains("⇒"))

        val star = SymbolCandidateCatalog.symbolsFor("☆")
        assertEquals("☆", star.first())
        assertTrue(star.contains("★"))
        assertTrue(star.contains("※"))

        val circle = SymbolCandidateCatalog.symbolsFor("○")
        assertEquals("○", circle.first())
        assertTrue(circle.contains("●"))
        assertTrue(circle.contains("◎"))

        val music = SymbolCandidateCatalog.symbolsFor("♪")
        assertEquals("♪", music.first())
        assertTrue(music.contains("♫"))

        val postal = SymbolCandidateCatalog.symbolsFor("〒")
        assertEquals("〒", postal.first())
        assertTrue(postal.contains("※"))

        val yen = SymbolCandidateCatalog.symbolsFor("¥")
        assertEquals("¥", yen.first())
        assertTrue(yen.contains("￥"))
        assertTrue(yen.contains("$"))
    }

    @Test
    fun punctuationAndExclamationProduceExpectedRelatedSymbols() {
        val comma = SymbolCandidateCatalog.symbolsFor("、")
        assertEquals("、", comma.first())
        assertTrue(comma.contains(","))
        assertTrue(comma.contains("，"))
        assertTrue(comma.contains("…"))

        val exclaim = SymbolCandidateCatalog.symbolsFor("！")
        assertEquals("！", exclaim.first())
        assertTrue(exclaim.contains("!"))
        assertTrue(exclaim.contains("！？"))
        assertTrue(exclaim.contains("!?"))

        val dash = SymbolCandidateCatalog.symbolsFor("ー")
        assertEquals("ー", dash.first())
        assertTrue(dash.contains("-"))
        assertTrue(dash.contains("→"))
        assertTrue(dash.contains("〜"))
    }

    @Test
    fun plainKanaHasNoSymbolCandidates() {
        assertTrue(SymbolCandidateCatalog.symbolsFor("あいう").isEmpty())
        assertTrue(SymbolCandidateCatalog.symbolsFor("").isEmpty())
    }

    @Test
    fun shouldComposeSymbolActivatesOnlyWhenEmptyOrSymbolOnly() {
        assertTrue(SymbolCandidateCatalog.shouldComposeSymbol('(', ""))
        assertTrue(SymbolCandidateCatalog.shouldComposeSymbol(')', "("))
        assertTrue(SymbolCandidateCatalog.shouldComposeSymbol('!', ""))
        assertTrue(SymbolCandidateCatalog.shouldComposeSymbolText("()", ""))
        assertTrue(SymbolCandidateCatalog.shouldComposeSymbolText("→", ""))
        assertFalse(SymbolCandidateCatalog.shouldComposeSymbolText("1", ""))
        assertFalse(SymbolCandidateCatalog.shouldComposeSymbol('!', "きょう"))
        assertFalse(SymbolCandidateCatalog.shouldComposeSymbol('(', "てすと"))
        assertFalse(SymbolCandidateCatalog.shouldComposeSymbol('a', ""))
    }

    @Test
    fun mergePreservesLearnedEngineCandidateAtTopAndAppendsRemainingSymbolsWithoutDuplicates() {
        val learned = Candidate(text = "【】", reading = "（）", cost = 1f)
        val extraEngine = Candidate(text = "カスタム", reading = "（）", cost = 50f)
        val merged = SymbolCandidateCatalog.mergeWithEngineCandidates("（）", listOf(learned, extraEngine))

        assertEquals("【】", merged[0].text)
        assertTrue(merged.any { it.text == "（）" })
        assertTrue(merged.any { it.text == "()" })
        assertTrue(merged.any { it.text == "「」" })
        assertEquals("カスタム", merged.last().text)
        assertEquals(merged.size, merged.map { it.text }.toSet().size)
        assertTrue(merged.all { it.coversReading("（）") })
    }
}
