package com.zeroconvert.ime.assist

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

data class LearnedMatch(val reading: String, val surface: String, val count: Int, val confirmedCount: Int = count, val updatedAtMs: Long = 0)

internal class TypoLearningHistory(private val capacity: Int = 500, private val now: () -> Long = System::currentTimeMillis) {
    private val entries = linkedMapOf<String, LearnedMatch>()
    fun lookup(reading: String): LearnedMatch? = entries[reading]
    fun lookupRule(reading: String): LearnedMatch? {
        entries[reading]?.let { return it }
        var start = 0
        while (start < reading.length) {
            start = reading.offsetByCodePoints(start, 1)
            if (reading.codePointCount(start, reading.length) < 3) break
            entries[reading.substring(start)]?.let { return it }
        }
        return null
    }
    fun snapshot(): List<LearnedMatch> = entries.values.toList()
    fun restore(entry: LearnedMatch) {
        if (entry.reading.isBlank() || entry.surface.isBlank() || entry.count < 1) return
        entries.remove(entry.reading)
        entries[entry.reading] = entry
        while (entries.size > capacity) entries.values.minByOrNull { it.updatedAtMs }?.let { entries.remove(it.reading) }
    }
    fun record(reading: String, surface: String) {
        if (reading.isBlank() || surface.isBlank()) return
        val previous = entries[reading]
        val count = if (previous?.surface == surface) {
            (previous.count.toLong() + 1).coerceAtMost(Int.MAX_VALUE.toLong()).toInt()
        } else 1
        val confirmed = if (previous?.surface == surface) {
            (previous.confirmedCount.toLong() + 1).coerceAtMost(Int.MAX_VALUE.toLong()).toInt()
        } else 1
        val updatedAt = maxOf(now().coerceAtLeast(0), previous?.updatedAtMs?.let { if (it == Long.MAX_VALUE) it else it + 1 } ?: 0)
        restore(LearnedMatch(reading, surface, count, confirmed, updatedAt))
    }
}

object TypoLearning {
    private const val PREFS = "ai_assist_learning"
    private const val KEY = "entries"
    private const val MAX = 500

    private val history = TypoLearningHistory(MAX)
    private var loaded = false

    @Synchronized
    fun ensureLoaded(context: Context) {
        if (loaded) return
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val json = prefs.getString(KEY, null)
        loaded = true
        if (json == null) return
        try {
            val arr = JSONArray(json)
            for (i in 0 until arr.length()) {
                val o = arr.getJSONObject(i)
                val count = o.getInt("c")
                // Legacy counts could include adoption followed by cancellation.
                // Preserve suggestions, but require new confirmed commits for automatic application.
                val confirmed = o.optInt("confirmed", 0).coerceIn(0, count.coerceAtLeast(0))
                history.restore(LearnedMatch(o.getString("r"), o.getString("s"), count, confirmed, o.optLong("updated_at_ms", 0).coerceAtLeast(0)))
            }
        } catch (_: Exception) {}
    }

    @Synchronized
    fun lookup(reading: String): LearnedMatch? = if (com.zeroconvert.ime.LearningPolicy.canRead) history.lookup(reading) else null

    @Synchronized
    fun lookupRule(reading: String): LearnedMatch? = if (com.zeroconvert.ime.LearningPolicy.canRead) history.lookupRule(reading) else null

    @Synchronized
    fun record(context: Context, reading: String, surface: String) {
        if (!com.zeroconvert.ime.LearningPolicy.canWrite) return
        ensureLoaded(context)
        history.record(reading, surface)
        save(context)
        if (com.zeroconvert.ime.BuildConfig.WINDOWS_ENGINE) {
            com.zeroconvert.ime.engine.WindowsEngine.recordCorrection(reading, surface)
        }
    }

    @Synchronized
    fun shouldAutoApply(reading: String): Boolean {
        return com.zeroconvert.ime.LearningPolicy.canRead && (history.lookup(reading)?.confirmedCount ?: 0) >= 3
    }

    private fun save(context: Context) {
        val arr = JSONArray()
        for (entry in history.snapshot()) {
            val o = JSONObject()
            o.put("r", entry.reading); o.put("s", entry.surface); o.put("c", entry.count)
            o.put("confirmed", entry.confirmedCount)
            o.put("updated_at_ms", entry.updatedAtMs)
            arr.put(o)
        }
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putString(KEY, arr.toString()).apply()
    }
}
