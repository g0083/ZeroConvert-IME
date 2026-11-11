package com.zeroconvert.ime

import com.zeroconvert.ime.assist.*
import org.junit.Assert.*
import org.junit.Test

class LearningParityTest {
    @Test fun removingOneTypoKeepsTheOtherReadings() {
        val history = TypoLearningHistory()
        history.record("きんにちは", "こんにちは")
        history.record("しんずく", "新宿")

        assertTrue(history.remove("きんにちは", "こんにちは"))
        assertNull(history.lookup("きんにちは"))
        assertEquals("新宿", history.lookup("しんずく")!!.surface)
    }

    @Test fun removingATypoRequiresTheStoredSurface() {
        val history = TypoLearningHistory()
        history.record("きんにちは", "こんにちは")

        assertFalse(history.remove("きんにちは", "今日は"))
        assertFalse(history.remove("ちがう", "こんにちは"))
        assertEquals("こんにちは", history.lookup("きんにちは")!!.surface)
    }

    @Test fun clearDropsEveryEntry() {
        val history = TypoLearningHistory()
        history.record("きんにちは", "こんにちは")
        history.record("しんずく", "新宿")

        history.clear()

        assertTrue(history.snapshot().isEmpty())
        assertNull(history.lookup("しんずく"))
    }

    @Test fun exactMatchWinsAndSuffixSearchChoosesTheLongestToken() {
        val history = TypoLearningHistory()
        history.record("しんずく", "新宿")
        history.record("のしんずく", "の新宿")
        assertEquals("のしんずく", history.lookupRule("きのうのしんずく")!!.reading)
        history.record("きのうのしんずく", "昨日の新宿")
        assertEquals("きのうのしんずく", history.lookupRule("きのうのしんずく")!!.reading)
        assertNull(history.lookup("あしたのしんずく"))
    }
    @Test fun shortSuffixDoesNotCorrectAnUnrelatedWord() {
        val history = TypoLearningHistory()
        history.record("けか", "結果")
        assertNull(history.lookupRule("ぞぞけか"))
        assertEquals("結果", history.lookupRule("けか")!!.surface)
    }
    @Test fun persistedTimestampsDetermineEvictionRegardlessOfJsonOrder() {
        val history = TypoLearningHistory(2)
        history.restore(LearnedMatch("新しい", "新", 1, 1, 300))
        history.restore(LearnedMatch("古い", "古", 1, 1, 100))
        history.restore(LearnedMatch("中間", "中", 1, 1, 200))
        assertNull(history.lookup("古い"))
        assertNotNull(history.lookup("新しい"))
        assertNotNull(history.lookup("中間"))
    }
    @Test fun clockRollbackCannotMakeANewAcceptanceTheOldestEntry() {
        var clock = 300L
        val history = TypoLearningHistory(2) { clock }
        history.record("がこう", "学校")
        clock = 100
        history.record("がこう", "学校")
        assertEquals(301L, history.lookup("がこう")!!.updatedAtMs)
        assertEquals(2, history.lookup("がこう")!!.confirmedCount)
        history.record("がこう", "別表記")
        assertEquals(1, history.lookup("がこう")!!.confirmedCount)
    }
}
