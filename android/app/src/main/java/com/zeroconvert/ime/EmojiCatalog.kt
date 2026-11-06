package com.zeroconvert.ime

/**
 * One palette cell. [skinTones] holds the five fully-qualified skin-tone variants
 * (light to dark) when the emoji supports them, so a long press can offer them
 * without inflating the grid.
 */
data class EmojiEntry(
    val emoji: String,
    val keywords: List<String>,
    val skinTones: List<String> = emptyList()
) {
    val hasSkinTones: Boolean get() = skinTones.isNotEmpty()
}

data class EmojiCategory(
    val id: String,
    val label: String,
    val icon: String,
    val entries: List<EmojiEntry>
)

/**
 * Parses the generated palette catalog (`assets/emoji_catalog.tsv`) and answers
 * category and search queries. The catalog is generated from the pinned Unicode
 * emoji-test data by `scripts/generate-android-emoji-catalog.mjs`.
 */
object EmojiCatalog {
    const val RECENT_CATEGORY_ID = "recent"
    const val RECENT_LIMIT = 32
    private const val MAX_KEYWORDS = 6

    private val categoryOrder = listOf(
        "smileys", "people", "nature", "food", "travel", "activities", "objects", "symbols", "flags"
    )

    fun parse(lines: Sequence<String>): List<EmojiCategory> {
        val byCategory = LinkedHashMap<String, MutableList<EmojiEntry>>()
        for (line in lines) {
            if (line.isBlank() || line.startsWith("#")) continue
            val parts = line.split('\t')
            if (parts.size < 2) continue
            val categoryId = parts[0].trim()
            val emoji = parts[1].trim()
            if (categoryId.isEmpty() || emoji.isEmpty()) continue
            val keywords = parts.getOrNull(2).orEmpty()
                .split(',')
                .map { it.trim() }
                .filter { it.isNotEmpty() }
                .take(MAX_KEYWORDS)
            val skinTones = parts.getOrNull(3).orEmpty()
                .split('|')
                .map { it.trim() }
                .filter { it.isNotEmpty() }
            byCategory.getOrPut(categoryId) { mutableListOf() }.add(EmojiEntry(emoji, keywords, skinTones))
        }
        val ordered = categoryOrder.filter { byCategory.containsKey(it) } +
            byCategory.keys.filter { it !in categoryOrder }
        return ordered.map { id ->
            EmojiCategory(id, labelFor(id), iconFor(id), byCategory.getValue(id))
        }
    }

    fun labelFor(categoryId: String): String = when (categoryId) {
        "smileys" -> "笑顔"
        "people" -> "人・体"
        "nature" -> "動物・自然"
        "food" -> "食べ物"
        "travel" -> "旅行"
        "activities" -> "スポーツ"
        "objects" -> "物"
        "symbols" -> "記号"
        "flags" -> "旗"
        RECENT_CATEGORY_ID -> "最近"
        else -> categoryId
    }

    fun iconFor(categoryId: String): String = when (categoryId) {
        "smileys" -> "😀"
        "people" -> "👋"
        "nature" -> "🐻"
        "food" -> "🍔"
        "travel" -> "✈️"
        "activities" -> "⚽"
        "objects" -> "💡"
        "symbols" -> "❤️"
        "flags" -> "🏳️"
        RECENT_CATEGORY_ID -> "🕘"
        else -> "•"
    }

    /**
     * Case-insensitive substring search over the Japanese keywords and the emoji
     * itself. An empty query returns an empty list so the caller keeps the
     * category grid instead of rendering every emoji at once.
     */
    fun search(categories: List<EmojiCategory>, query: String): List<EmojiEntry> {
        val needle = query.trim().lowercase()
        if (needle.isEmpty()) return emptyList()
        val results = LinkedHashMap<String, EmojiEntry>()
        for (category in categories) {
            for (entry in category.entries) {
                if (entry.emoji == needle) return listOf(entry)
                val matches = entry.keywords.any { it.lowercase().contains(needle) }
                if (matches) results.getOrPut(entry.emoji) { entry }
            }
        }
        return results.values.toList()
    }

    /** Most recent first, de-duplicated, capped at [RECENT_LIMIT]. */
    fun recentEntries(recent: List<String>, categories: List<EmojiCategory>): List<EmojiEntry> {
        if (recent.isEmpty()) return emptyList()
        val index = HashMap<String, EmojiEntry>()
        for (category in categories) {
            for (entry in category.entries) {
                index[entry.emoji] = entry
                for (tone in entry.skinTones) index[tone] = entry
            }
        }
        val seen = LinkedHashSet<String>()
        val result = ArrayList<EmojiEntry>(minOf(recent.size, RECENT_LIMIT))
        for (emoji in recent) {
            if (!seen.add(emoji)) continue
            // Keep the tone variant the user actually picked, but reuse the base
            // entry so the cell still offers the tone picker.
            val entry = index[emoji]
            result.add(if (entry == null) EmojiEntry(emoji, emptyList()) else entry.copy(emoji = emoji))
            if (result.size >= RECENT_LIMIT) break
        }
        return result
    }

    /** Records [emoji] as most recent, keeping the list bounded and unique. */
    fun pushRecent(recent: List<String>, emoji: String): List<String> {
        if (emoji.isEmpty()) return recent
        val updated = ArrayList<String>(minOf(recent.size + 1, RECENT_LIMIT))
        updated.add(emoji)
        for (existing in recent) {
            if (existing == emoji || existing in updated) continue
            updated.add(existing)
            if (updated.size >= RECENT_LIMIT) break
        }
        return updated
    }
}
