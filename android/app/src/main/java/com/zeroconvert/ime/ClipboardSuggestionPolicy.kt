package com.zeroconvert.ime

/** Candidate-bar visibility is independent of clipboard history retention. */
object ClipboardSuggestionPolicy {
    const val DEFAULT_MINUTES = 3
    const val MAX_MINUTES = 30
    val minuteOptions = listOf(0, 1, 2, 3, 5, 10, 15, 30)

    fun normalizeMinutes(minutes: Int): Int = minutes.coerceIn(0, MAX_MINUTES)

    fun remainingMillis(entry: ClipboardEntry, minutes: Int, now: Long): Long {
        if (entry.pinned || entry.timestamp > now) return 0
        val duration = normalizeMinutes(minutes) * 60_000L
        // Avoid subtraction overflow for malformed timestamps.
        if (duration == 0L || entry.timestamp <= now - duration) return 0
        return entry.timestamp - (now - duration)
    }

    fun selectAll(
        history: List<ClipboardEntry>,
        minutes: Int,
        now: Long,
        dismissedIds: Set<Long> = emptySet()
    ): List<ClipboardEntry> =
        history.asSequence()
            .filter { it.id !in dismissedIds && remainingMillis(it, minutes, now) > 0 }
            .sortedByDescending { it.timestamp }
            .toList()

    fun select(
        history: List<ClipboardEntry>,
        minutes: Int,
        now: Long,
        dismissedIds: Set<Long> = emptySet()
    ): ClipboardEntry? =
        selectAll(history, minutes, now, dismissedIds).firstOrNull()
}

/** Keeps newest copies at the front of clipboard history, promoting duplicates on re-copy. */
object ClipboardHistoryPolicy {
    private val EMAIL_REGEX = Regex("[A-Za-z0-9._%+-]+@[A-Za-z0-9.-]+\\.[A-Za-z]{2,}")
    private val URL_REGEX = Regex("https?://[^\\s]+")
    private val PHONE_REGEX = Regex("(?:(?:\\+?81[- ]?)?0\\d{1,4}[- ]?\\d{1,4}[- ]?\\d{3,4})")

    /**
     * Builds clipboard entries for copied [text] with the original text always first (`entityType = null`),
     * followed by any extracted sub-entities (`email`, `url`, `phone`) sharing [now].
     */
    fun buildTextEntries(text: String, now: Long = System.currentTimeMillis()): List<ClipboardEntry> {
        if (text.isBlank()) return emptyList()
        val entities = linkedMapOf<String, String>()
        EMAIL_REGEX.findAll(text).forEach { entities.putIfAbsent(it.value, "email") }
        URL_REGEX.findAll(text).forEach { entities.putIfAbsent(it.value, "url") }
        PHONE_REGEX.findAll(text).forEach { entities.putIfAbsent(it.value, "phone") }

        // If the entire copied text equals an entity, treat it as ordinary clipboard content rather
        // than labeling a standalone number/URL as extracted.
        entities.remove(text)
        val result = ArrayList<ClipboardEntry>(1 + entities.size)
        result += ClipboardEntry(text = text, entityType = null, timestamp = now)
        for ((entity, type) in entities) {
            result += ClipboardEntry(text = entity, entityType = type, timestamp = now)
        }
        return result
    }

    fun entityBadgeLabel(entityType: String?): String? = when (entityType) {
        null -> null
        "phone" -> "電話"
        "email" -> "メール"
        "url" -> "URL"
        else -> "抽出"
    }

    fun upsertEntries(history: MutableList<ClipboardEntry>, entries: List<ClipboardEntry>): Boolean {
        if (entries.isEmpty()) return false
        var changed = false
        for (entry in entries.asReversed()) {
            val existingIdx = history.indexOfFirst {
                it.text == entry.text && it.imageUri == entry.imageUri
            }
            if (existingIdx >= 0) {
                val existing = history.removeAt(existingIdx)
                history.add(
                    0,
                    existing.copy(
                        timestamp = maxOf(existing.timestamp, entry.timestamp),
                        entityType = entry.entityType
                    )
                )
            } else {
                history.add(0, entry)
            }
            changed = true
        }
        return changed
    }

    fun clearDismissedForEntries(
        history: List<ClipboardEntry>,
        upsertedEntries: List<ClipboardEntry>,
        dismissedIds: Set<Long>
    ): Set<Long> {
        if (dismissedIds.isEmpty()) return dismissedIds
        val activeIds = history.mapTo(HashSet(history.size)) { it.id }
        val reactivatedIds = upsertedEntries.mapNotNullTo(HashSet()) { entry ->
            history.firstOrNull { it.text == entry.text && it.imageUri == entry.imageUri }?.id
        }
        return dismissedIds.filterTo(LinkedHashSet()) { it in activeIds && it !in reactivatedIds }
    }
}
