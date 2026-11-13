package com.zeroconvert.ime

import android.content.Context
import android.util.Log
import com.zeroconvert.ime.azookey.input.ComposingText
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject

data class UserDictEntry(
    val reading: String,
    val word: String
)

/**
 * Android-side adapter for the shared conversion engine.
 *
 * Learning history is owned by the shared Swift engine, so this class keeps only
 * the user dictionary and the request adaptation. A second Kotlin learning store
 * would diverge from the engine's candidate learning and from its reset.
 */
class KanaKanjiConverter {

    companion object {
        private const val TAG = "KanaKanjiConverter"
        private const val PREFS_USER_DATA = "zerocon_user_data"
        private const val KEY_USER_DICT = "user_dict_json"

        @Volatile
        private var isUserDataLoaded = false
        val isInitialized: Boolean get() = isUserDataLoaded
        @Volatile
        internal var userDictRevision: Int = 0
            private set
        private val userDictEntries = mutableListOf<UserDictEntry>()

        /** Bumped by a history reset so commits queued before it are dropped. */
        private val learningHistoryEpoch = java.util.concurrent.atomic.AtomicLong()
        internal val learningHistoryRevision: Long get() = learningHistoryEpoch.get()
        internal fun bumpLearningHistory(): Long = learningHistoryEpoch.incrementAndGet()

        fun toKatakana(hiragana: String): String {
            val sb = StringBuilder(hiragana.length)
            for (ch in hiragana) {
                if (ch in '\u3041'..'\u3096') {
                    sb.append((ch.code + 96).toChar())
                } else {
                    sb.append(ch)
                }
            }
            return sb.toString()
        }

        fun toHiragana(katakana: String): String {
            val sb = StringBuilder(katakana.length)
            for (ch in katakana) {
                if (ch in '\u30A1'..'\u30F6') {
                    sb.append((ch.code - 96).toChar())
                } else {
                    sb.append(ch)
                }
            }
            return sb.toString()
        }

        @Synchronized
        fun ensureInitialized(context: Context) {
            LearningPolicy.mode = LearningMode.from(
                context.getSharedPreferences("ime_settings", Context.MODE_PRIVATE).getString(LearningPolicy.KEY, null)
            )
            if (!isUserDataLoaded) {
                loadUserData(context)
                isUserDataLoaded = true
            }
        }

        private fun loadUserData(context: Context) {
            val prefs = context.getSharedPreferences(PREFS_USER_DATA, Context.MODE_PRIVATE)
            userDictEntries.clear()
            val dictJson = prefs.getString(KEY_USER_DICT, null)
            if (!dictJson.isNullOrEmpty()) {
                try {
                    val arr = JSONArray(dictJson)
                    for (i in 0 until arr.length()) {
                        val obj = arr.getJSONObject(i)
                        val r = toHiragana(obj.optString("reading", "").trim())
                        val w = obj.optString("word", "").trim()
                        if (r.isNotEmpty() && w.isNotEmpty()) {
                            userDictEntries.add(UserDictEntry(r, w))
                        }
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "Failed to parse user_dict_json", e)
                }
            }

            userDictRevision++
            publishAssistVocabulary()
        }

        private fun saveUserDict(context: Context) {
            publishAssistVocabulary()
            val arr = JSONArray()
            for (entry in userDictEntries) {
                val obj = JSONObject()
                obj.put("reading", entry.reading)
                obj.put("word", entry.word)
                arr.put(obj)
            }
            context.getSharedPreferences(PREFS_USER_DATA, Context.MODE_PRIVATE)
                .edit()
                .putString(KEY_USER_DICT, arr.toString())
                .apply()
        }

        @Synchronized
        fun getUserDictionary(context: Context): List<UserDictEntry> {
            ensureInitialized(context)
            return userDictEntries.toList()
        }

        private fun publishAssistVocabulary() {
            com.zeroconvert.ime.assist.AssistVocabulary.replaceUserEntries(userDictEntries.map { it.reading to it.word })
        }

        @Synchronized
        fun addUserDictEntry(context: Context, reading: String, word: String): Boolean {
            ensureInitialized(context)
            val normReading = toHiragana(reading.trim())
            val normWord = word.trim()
            if (normReading.isEmpty() || normWord.isEmpty()) return false
            if (userDictEntries.any { it.reading == normReading && it.word == normWord }) return false
            userDictEntries.add(0, UserDictEntry(normReading, normWord))
            userDictRevision++
            saveUserDict(context)
            return true
        }

        @Synchronized
        fun removeUserDictEntry(context: Context, entry: UserDictEntry) {
            ensureInitialized(context)
            if (userDictEntries.removeAll { it.reading == entry.reading && it.word == entry.word }) {
                userDictRevision++
                saveUserDict(context)
            }
        }

        /** Learned readings currently held by the shared engine's selection overrides. */
        fun getLearningCount(context: Context): Int {
            ensureInitialized(context)
            return com.zeroconvert.ime.engine.WindowsEngine.learningSelectionCount()
        }

        /**
         * Deletes the shared engine's conversion learning and invalidates commits
         * queued before the reset. Returns true only when the engine confirmed it.
         */
        fun clearLearningMemory(context: Context): Boolean {
            ensureInitialized(context)
            bumpLearningHistory()
            return com.zeroconvert.ime.engine.WindowsEngine.resetLearningBlocking()
        }

        /**
         * Deletes every learning store: conversion, typo correction and English.
         *
         * Order matters only for reporting: the engine reset also clears the
         * candidate cache, so a later commit cannot resurrect a deleted entry.
         */
        fun clearAllLearning(context: Context, conversion: Boolean, typo: Boolean): Boolean {
            ensureInitialized(context)
            var ok = true
            if (typo) {
                com.zeroconvert.ime.assist.TypoLearning.clear(context)
            }
            if (conversion) {
                bumpLearningHistory()
                ok = com.zeroconvert.ime.engine.WindowsEngine.resetLearningBlocking()
            }
            return ok
        }

        /** Learned typo corrections currently held, for the settings summary. */
        fun getTypoLearningCount(context: Context): Int {
            com.zeroconvert.ime.assist.TypoLearning.ensureLoaded(context)
            return com.zeroconvert.ime.engine.WindowsEngine.learningTypoCount()
        }

        @Synchronized
        internal fun reloadUserDataForTest(context: Context) {
            loadUserData(context)
        }
    }

    internal suspend fun requestCandidates(
        composingText: ComposingText,
        leftContext: String,
        enableAI: Boolean = true,
        profile: String = "",
        personalize: Boolean = true,
        fixedSurfacePrefix: String = "",
        experimentalKeyboardCorrection: Boolean = false,
        predictiveConversion: Boolean = true,
        continuation: ConversionContinuation? = null
    ): List<Candidate> = withContext(Dispatchers.Default) {
        val hiragana = composingText.convertTargetBeforeCursor
        if (hiragana.isEmpty()) return@withContext emptyList()

        // A manually accepted correction is already a surface, not a new reading.
        // Convert only new input after it, keeping the exact chosen spelling.
        if (fixedSurfacePrefix.isNotEmpty() && hiragana.startsWith(fixedSurfacePrefix)) {
            val tail = composingText.clone().prefixToCursorPosition()
            tail.prefixComplete(fixedSurfacePrefix.length)
            if (tail.isEmpty) return@withContext listOf(Candidate(fixedSurfacePrefix, toKatakana(hiragana)))
            return@withContext requestCandidates(
                tail, leftContext + fixedSurfacePrefix, enableAI, profile, personalize,
                experimentalKeyboardCorrection = experimentalKeyboardCorrection,
                predictiveConversion = predictiveConversion,
                continuation = null
            ).map {
                it.copy(
                    text = fixedSurfacePrefix + it.text,
                    reading = toKatakana(fixedSurfacePrefix + toHiragana(it.reading)),
                    segments = emptyList(), learningAllowed = it.isLearningTarget,
                    engineConsumedReading = it.engineConsumedReading?.let { consumed -> fixedSurfacePrefix.length + consumed }
                )
            }
        }

        com.zeroconvert.ime.engine.WindowsEngine.candidates(
            composingText, leftContext, enableAI, profile, personalize, experimentalKeyboardCorrection,
            predictiveConversion = predictiveConversion, continuation = continuation
        )
    }
}
