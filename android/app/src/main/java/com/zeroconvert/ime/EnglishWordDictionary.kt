package com.zeroconvert.ime

import android.content.Context
import android.util.Log
import com.zeroconvert.ime.assist.TouchBiasTypoResolver
import com.zeroconvert.ime.azookey.input.ComposingText
import org.json.JSONObject
import java.io.BufferedReader
import java.io.InputStreamReader

object EnglishWordDictionary {
    private const val TAG = "EnglishWordDictionary"
    private const val PREFS = "zerocon_english_data"
    private const val KEY_LEARNING = "english_learning_memory_json"
    private const val ASSET = "english_words.txt"

    @Volatile
    private var loaded = false
    @Volatile
    private var cachedNextWeights: Pair<String, Map<Char, Float>>? = null
    private val words = mutableListOf<String>()
    private val learning = LinkedHashMap<String, String>()

    @Synchronized
    fun ensureLoaded(context: Context) {
        if (loaded) return
        try {
            context.assets.open(ASSET).use { stream ->
                BufferedReader(InputStreamReader(stream, Charsets.UTF_8)).use { r ->
                    var line = r.readLine()
                    while (line != null) {
                        val w = line.trim()
                        if (w.isNotEmpty() && w.all { it.isLetter() }) {
                            words.add(w)
                        }
                        line = r.readLine()
                    }
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to load $ASSET", e)
        }
        // load learning
        try {
            val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            val json = prefs.getString(KEY_LEARNING, null)
            if (!json.isNullOrEmpty()) {
                val obj = JSONObject(json)
                val keys = obj.keys()
                while (keys.hasNext()) {
                    val k = keys.next()
                    val v = obj.optString(k, "")
                    if (k.isNotEmpty() && v.isNotEmpty()) learning[k] = v
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to load english learning memory", e)
        }
        loaded = true
        cachedNextWeights = null
        Log.i(TAG, "Loaded ${words.size} english words, ${learning.size} learned")
    }

    @Synchronized
    fun recordSelection(context: Context, prefix: String, word: String) {
        ensureLoaded(context)
        val key = prefix.lowercase()
        if (key.isEmpty() || word.isEmpty()) return
        learning.remove(key)
        learning[key] = word
        while (learning.size > 500) {
            val eldest = learning.keys.firstOrNull() ?: break
            learning.remove(eldest)
        }
        try {
            val obj = JSONObject()
            for ((k, v) in learning) obj.put(k, v)
            context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .edit().putString(KEY_LEARNING, obj.toString()).apply()
        } catch (e: Exception) {
            Log.e(TAG, "Failed to save english learning memory", e)
        }
    }

    fun suggest(context: Context, prefix: String, limit: Int = 8): List<Candidate> {
        ensureLoaded(context)
        if (prefix.isEmpty()) return emptyList()
        val p = prefix.lowercase()
        val capitalize = prefix.isNotEmpty() && prefix[0].isUpperCase()
        val result = mutableListOf<Candidate>()
        // learned override first
        val learned = synchronized(this) { learning[p] }
        if (!learned.isNullOrEmpty() && learned.lowercase() != p) {
            result.add(Candidate(text = adjustCase(learned, capitalize), reading = prefix, cost = 2000f, isAI = false))
        }
        // frequency-ranked prefix matches
        synchronized(this) {
            for (w in words) {
                if (result.size >= limit) break
                val wl = w.lowercase()
                if (wl.startsWith(p) && wl != p) {
                    val cand = adjustCase(w, capitalize)
                    if (result.none { it.text == cand }) {
                        result.add(Candidate(text = cand, reading = prefix, cost = 1000f - result.size.toFloat(), isAI = false))
                    }
                }
            }
        }
        // fallback: the typed text itself
        if (result.isEmpty()) {
            result.add(Candidate(text = prefix, reading = prefix, cost = -100f, isAI = false))
        }
        return result
    }

    /**
     * Non-blocking next-character continuation weights for active English prefix typing.
     * Returns `emptyMap()` until the dictionary has been loaded on a background thread.
     */
    fun nextCharWeights(prefix: String): Map<Char, Float> {
        if (!loaded || prefix.isBlank()) return emptyMap()
        val key = prefix.lowercase()
        cachedNextWeights?.let { (cachedPrefix, cachedMap) ->
            if (cachedPrefix == key) return cachedMap
        }
        val snapshot = synchronized(this) { words.toList() }
        val weights = NextKeyPredictor.computeEnglishNextCharWeights(key, snapshot)
        cachedNextWeights = key to weights
        return weights
    }

    /**
     * Suggests English candidates with spatial 1-edit fallback recovery when [prefix]
     * produces no exact learned or prefix matches and [touchBiases] has edge proximity data.
     */
    fun suggestWithTouchBias(
        context: Context,
        prefix: String,
        touchBiases: List<TouchKeyBias>,
        limit: Int = 8
    ): List<Candidate> {
        val base = suggest(context, prefix, limit)
        if (touchBiases.isEmpty() || prefix.length < 2) return base
        val hasDictionaryHit = base.any { it.cost > 0f }
        if (hasDictionaryHit) return base

        val snapshot = synchronized(this) { words.toList() }
        val recovered = TouchBiasTypoResolver.rankEnglishCorrections(
            prefix = prefix,
            touchBiases = touchBiases,
            words = snapshot,
            limit = (limit - 1).coerceAtLeast(1)
        )
        if (recovered.isEmpty()) return base

        val merged = mutableListOf<Candidate>()
        for ((index, word) in recovered.withIndex()) {
            merged.add(Candidate(text = word, reading = prefix, cost = 900f - index, isAI = true))
        }
        for (fallback in base) {
            if (merged.size >= limit) break
            if (merged.none { it.text.equals(fallback.text, ignoreCase = false) }) {
                merged.add(fallback)
            }
        }
        return merged.take(limit)
    }

    private fun adjustCase(word: String, capitalize: Boolean): String {
        if (!capitalize || word.isEmpty()) return word
        return word[0].uppercaseChar() + word.substring(1)
    }
}
