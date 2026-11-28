package com.zeroconvert.ime

/**
 * Bounded, de-duplicated MRU history for the Symbols panel, mirroring EmojiCatalog's recent list.
 */
object RecentSymbolHistory {
    const val RECENT_CATEGORY_TITLE = "履歴"
    const val RECENT_LIMIT = 30

    /** Records [symbol] as most recent, ignoring blank/whitespace-only entries. */
    fun pushRecent(recent: List<String>, symbol: String): List<String> {
        if (symbol.isBlank()) return recent
        val updated = ArrayList<String>(minOf(recent.size + 1, RECENT_LIMIT))
        updated.add(symbol)
        for (existing in recent) {
            if (existing != symbol && existing.isNotBlank()) {
                updated.add(existing)
                if (updated.size >= RECENT_LIMIT) break
            }
        }
        return updated
    }

    fun parseStored(raw: String?): List<String> {
        if (raw.isNullOrEmpty()) return emptyList()
        val result = ArrayList<String>()
        for (line in raw.split('\n')) {
            if (line.isNotBlank() && line !in result) {
                result.add(line)
                if (result.size >= RECENT_LIMIT) break
            }
        }
        return result
    }

    fun serialize(recent: List<String>): String =
        recent.filter { it.isNotBlank() }.take(RECENT_LIMIT).joinToString("\n")
}
