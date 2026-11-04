package com.zeroconvert.ime

import org.junit.Assert.*
import org.junit.Test

class ClipboardSuggestionPolicyTest {
    private val now = 10_000_000L
    private fun entry(ageMillis: Long = 0, pinned: Boolean = false, id: Long = 1) =
        ClipboardEntry(id = id, text = "検証用$id", timestamp = now - ageMillis, pinned = pinned)
    private fun select(vararg history: ClipboardEntry, minutes: Int = 3, at: Long = now) =
        ClipboardSuggestionPolicy.select(history.toList(), minutes, at)

    @Test fun defaultsAndStoredValuesAreBounded() {
        assertEquals(3, ClipboardSuggestionPolicy.DEFAULT_MINUTES)
        assertEquals(0, ClipboardSuggestionPolicy.normalizeMinutes(-1))
        assertEquals(30, ClipboardSuggestionPolicy.normalizeMinutes(Int.MAX_VALUE))
    }

    @Test fun noHistoryOrOnlyPinnedEntriesShowsNothing() {
        assertNull(select())
        assertNull(select(entry(pinned = true)))
    }

    @Test fun pinnedHeadDoesNotHideARecentUnpinnedEntry() {
        val recent = entry(1_000, id = 2)
        assertEquals(recent, select(entry(pinned = true), recent))
    }

    @Test fun pinReorderingDoesNotDetermineTheNewestSuggestion() {
        val recent = entry(1_000, id = 2)
        assertEquals(recent, select(entry(50_000), recent, entry(pinned = true, id = 3)))
    }

    @Test fun expiresExactlyAtTheConfiguredDeadline() {
        val copied = entry()
        assertEquals(copied, select(copied, at = now + 179_999))
        assertNull(select(copied, at = now + 180_000))
        assertNull(select(copied, at = now + 180_001))
    }

    @Test fun disabledSettingHidesFreshEntries() {
        assertNull(select(entry(), minutes = 0))
    }

    @Test fun changingDurationUsesTheOriginalAdditionTime() {
        val copied = entry(120_000)
        assertNull(select(copied, minutes = 1))
        assertEquals(copied, select(copied, minutes = 3))
        assertEquals(60_000L, ClipboardSuggestionPolicy.remainingMillis(copied, 3, now))
    }

    @Test fun reopeningDoesNotRestartTheDisplayTime() {
        val copied = entry()
        assertNotNull(select(copied, at = now + 60_000))
        assertNull(select(copied, at = now + 180_000))
    }

    @Test fun unpinningDoesNotRefreshAnOldEntry() {
        assertNull(select(entry(600_000, pinned = true).copy(pinned = false)))
    }

    @Test fun futureAndMalformedOldTimestampsAreHidden() {
        assertNull(select(entry(-1)))
        assertNull(select(entry().copy(timestamp = Long.MIN_VALUE)))
    }

    @Test fun filteringPreservesHistoryAndPinnedItems() {
        val history = listOf(entry(pinned = true), entry(600_000, id = 2))
        val original = history.toList()
        assertNull(ClipboardSuggestionPolicy.select(history, 3, now))
        assertEquals(original, history)
    }
}
