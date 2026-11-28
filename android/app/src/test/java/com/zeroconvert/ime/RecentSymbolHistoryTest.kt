package com.zeroconvert.ime

import org.junit.Assert.assertEquals
import org.junit.Test

class RecentSymbolHistoryTest {
    @Test fun pushRecentDeduplicatesMovesToFrontAndCapsLimit() {
        var list = emptyList<String>()
        for (i in 1..35) {
            list = RecentSymbolHistory.pushRecent(list, "S$i")
        }
        assertEquals(RecentSymbolHistory.RECENT_LIMIT, list.size)
        assertEquals("S35", list.first())
        assertEquals("S6", list.last())

        val promoted = RecentSymbolHistory.pushRecent(list, "S10")
        assertEquals(RecentSymbolHistory.RECENT_LIMIT, promoted.size)
        assertEquals("S10", promoted.first())
        assertEquals(1, promoted.count { it == "S10" })
    }

    @Test fun ignoresBlankEntriesAndRoundTripsStorage() {
        val list = RecentSymbolHistory.pushRecent(listOf("→"), "   ")
        assertEquals(listOf("→"), list)

        val serialized = RecentSymbolHistory.serialize(listOf("「", "★", "→"))
        val parsed = RecentSymbolHistory.parseStored(serialized + "\n\n★\n")
        assertEquals(listOf("「", "★", "→"), parsed)
    }
}
