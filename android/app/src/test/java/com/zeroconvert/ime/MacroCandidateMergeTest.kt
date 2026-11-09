package com.zeroconvert.ime

import com.zeroconvert.ime.engine.MacroCandidateMerge
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Test

class MacroCandidateMergeTest {
    private fun candidate(text: String, reading: String = "キョウ") = Candidate(text, reading)

    @Test fun macrosAreInsertedDirectlyAfterTheTopCandidate() {
        val converted = listOf(candidate("今日"), candidate("京"), candidate("きょう"))
        val merged = MacroCandidateMerge.merge(converted, listOf("2026年11月9日"), "キョウ")
        assertEquals(listOf("今日", "2026年11月9日", "京", "きょう"), merged.map { it.text })
    }

    @Test fun multipleMacrosKeepTheirOrderAfterTheTopCandidate() {
        val converted = listOf(candidate("今日"), candidate("京"))
        val merged = MacroCandidateMerge.merge(converted, listOf("2026年11月9日", "15時1分"), "キョウ")
        assertEquals(listOf("今日", "2026年11月9日", "15時1分", "京"), merged.map { it.text })
    }

    @Test fun aSingleCandidateStillGetsTheMacrosAfterIt() {
        val converted = listOf(candidate("今日"))
        val merged = MacroCandidateMerge.merge(converted, listOf("2026年11月9日"), "キョウ")
        assertEquals(listOf("今日", "2026年11月9日"), merged.map { it.text })
    }

    @Test fun emptyCandidateListStartsWithTheMacros() {
        val merged = MacroCandidateMerge.merge(emptyList(), listOf("2026年11月9日", "15時1分"), "キョウ")
        assertEquals(listOf("2026年11月9日", "15時1分"), merged.map { it.text })
    }

    @Test fun macroDuplicatingAnExistingSurfaceIsDropped() {
        val converted = listOf(candidate("今日"), candidate("2026年11月9日"))
        val merged = MacroCandidateMerge.merge(converted, listOf("2026年11月9日"), "キョウ")
        assertSame(converted, merged)
    }

    @Test fun duplicateMacroTextsAreInsertedOnlyOnce() {
        val merged = MacroCandidateMerge.merge(listOf(candidate("今日")), listOf("15時1分", "15時1分"), "キョウ")
        assertEquals(listOf("今日", "15時1分"), merged.map { it.text })
    }

    @Test fun blankMacroTextsAreIgnored() {
        val converted = listOf(candidate("今日"))
        assertSame(converted, MacroCandidateMerge.merge(converted, listOf("", "   "), "キョウ"))
        assertSame(converted, MacroCandidateMerge.merge(converted, emptyList(), "キョウ"))
    }

    @Test fun macroCandidatesCarryTheWholeReadingAndCannotWriteLearning() {
        val merged = MacroCandidateMerge.merge(listOf(candidate("今日")), listOf("2026年11月9日"), "キョウ")
        val macro = merged[1]
        assertEquals("2026年11月9日", macro.text)
        assertEquals("キョウ", macro.reading)
        assertEquals(0f, macro.cost, 0f)
        assertFalse(macro.isAI)
        assertFalse(macro.learningAllowed)
        assertFalse(macro.isLearningTarget)
    }

    @Test fun existingCandidatesAreKeptUnchanged() {
        val top = candidate("今日").copy(isAI = true, learningAllowed = true)
        val second = candidate("京", "キョウ")
        val merged = MacroCandidateMerge.merge(listOf(top, second), listOf("15時1分"), "キョウ")
        assertEquals(top, merged[0])
        assertEquals(second, merged[2])
    }
}