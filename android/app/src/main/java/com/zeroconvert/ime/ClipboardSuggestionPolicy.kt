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

    fun select(history: List<ClipboardEntry>, minutes: Int, now: Long): ClipboardEntry? =
        history.asSequence()
            .filter { remainingMillis(it, minutes, now) > 0 }
            .maxByOrNull { it.timestamp }
}
