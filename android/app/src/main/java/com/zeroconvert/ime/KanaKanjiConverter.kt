package com.zeroconvert.ime

import android.content.Context
import android.util.Log
import com.zeroconvert.ime.azookey.input.ComposingText
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedReader
import java.io.InputStreamReader

data class UserDictEntry(
    val reading: String,
    val word: String
)

class KanaKanjiConverter {

    companion object {
        private const val TAG = "KanaKanjiConverter"
        private const val PREFS_USER_DATA = "zerocon_user_data"
        private const val KEY_USER_DICT = "user_dict_json"
        private const val KEY_LEARNING_MEM = "learning_memory_json"
        private const val MAX_LEARNING_ENTRIES = 500

        @Volatile
        private var isEmojiLoaded = false
        private val emojiDict = HashMap<String, MutableList<String>>()

        @Volatile
        private var isUserDataLoaded = false
        private val userDictEntries = mutableListOf<UserDictEntry>()
        // LinkedHashMap in access/insertion order: readingHiragana -> selectedSurface
        private val learningMemory = LinkedHashMap<String, String>()
        private var morphologyLearning: MorphologyLearningStore? = null
        private val learningHistoryEpoch = java.util.concurrent.atomic.AtomicLong()
        internal val learningHistoryRevision: Long get() = learningHistoryEpoch.get()

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
            LearningPolicy.mode = LearningMode.from(context.getSharedPreferences("ime_settings", Context.MODE_PRIVATE).getString(LearningPolicy.KEY, null))
            if (!isEmojiLoaded) {
                loadEmojiDictionary(context)
                isEmojiLoaded = true
            }
            if (!isUserDataLoaded) {
                loadUserData(context)
                isUserDataLoaded = true
            }
        }

        private fun loadEmojiDictionary(context: Context) {
            try {
                context.assets.open("emoji_all_E15.1.txt").use { stream ->
                    BufferedReader(InputStreamReader(stream, Charsets.UTF_8)).use { reader ->
                        var line: String? = reader.readLine()
                        while (line != null) {
                            val parts = line.split('\t')
                            if (parts.size >= 2) {
                                val emoji = parts[0].trim()
                                val keywords = parts[1].split(',')
                                if (emoji.isNotEmpty()) {
                                    for (kw in keywords) {
                                        val cleanKw = toHiragana(kw.trim())
                                        if (cleanKw.isNotEmpty()) {
                                            val list = emojiDict.getOrPut(cleanKw) { mutableListOf() }
                                            if (list.size < 12 && !list.contains(emoji)) {
                                                list.add(emoji)
                                            }
                                        }
                                    }
                                }
                            }
                            line = reader.readLine()
                        }
                    }
                }
                Log.i(TAG, "Loaded Emoji Dictionary: ${emojiDict.size} readings")
            } catch (e: Exception) {
                Log.e(TAG, "Failed to load emoji_all_E15.1.txt", e)
            }
        }

        private fun loadUserData(context: Context) {
            if (morphologyLearning == null) {
                try {
                    val alphabet = context.assets.open("dictionary/louds/charID.chid").bufferedReader(Charsets.UTF_8)
                        .use { it.readText() }.codePoints().toArray().toSet()
                    morphologyLearning = MorphologyLearningStore(java.io.File(context.filesDir, "conversion-learning-v1.bin"), alphabet)
                } catch (failure: Exception) { Log.e(TAG, "Could not load morphology memory", failure) }
            }
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

            publishAssistVocabulary()
            learningMemory.clear()
            val learnJson = prefs.getString(KEY_LEARNING_MEM, null)
            if (!learnJson.isNullOrEmpty()) {
                try {
                    val obj = JSONObject(learnJson)
                    val keys = obj.keys()
                    while (keys.hasNext()) {
                        val k = keys.next()
                        val v = obj.optString(k, "")
                        if (k.isNotEmpty() && v.isNotEmpty()) {
                            learningMemory[k] = v
                        }
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "Failed to parse learning_memory_json", e)
                }
            }
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

        private fun saveLearningMemory(context: Context) {
            val obj = JSONObject()
            for ((k, v) in learningMemory) {
                obj.put(k, v)
            }
            context.getSharedPreferences(PREFS_USER_DATA, Context.MODE_PRIVATE)
                .edit()
                .putString(KEY_LEARNING_MEM, obj.toString())
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
            saveUserDict(context)
            return true
        }

        @Synchronized
        fun removeUserDictEntry(context: Context, entry: UserDictEntry) {
            ensureInitialized(context)
            userDictEntries.removeAll { it.reading == entry.reading && it.word == entry.word }
            saveUserDict(context)
        }

        @Synchronized
        fun recordSelection(context: Context, readingHiragana: String, surface: String) {
            ensureInitialized(context)
            if (!LearningPolicy.canWrite) return
            val normReading = toHiragana(readingHiragana.trim())
            val normSurface = surface.trim()
            if (normReading.isEmpty() || normSurface.isEmpty()) return
            learningMemory.remove(normReading)
            learningMemory[normReading] = normSurface
            while (learningMemory.size > MAX_LEARNING_ENTRIES) {
                val eldestKey = learningMemory.keys.firstOrNull() ?: break
                learningMemory.remove(eldestKey)
            }
            saveLearningMemory(context)
        }

        @Synchronized
        internal fun recordCommittedSelection(context: Context, selection: ConversionCommitLearning,
                                             previous: DictionarySegment? = null,
                                             expectedRevision: Long = learningHistoryRevision) {
            ensureInitialized(context)
            if (!LearningPolicy.canWrite || expectedRevision != learningHistoryRevision) return
            if (BuildConfig.WINDOWS_ENGINE && selection.engineCandidateId.isNotEmpty()) {
                com.zeroconvert.ime.engine.WindowsEngine.commit(selection.engineCandidateId)
            }
            recordSelection(context, selection.reading, selection.surface)
            morphologyLearning?.takeIf { it.available && selection.segments.isNotEmpty() }?.let { store ->
                store.memory.record(selection.segments, previous)
                store.save()
            }
        }

        @Synchronized
        private fun learnedEntries(reading: String): List<MorphologyLearningMemory.Entry> =
            if (LearningPolicy.canRead) morphologyLearning?.takeIf { it.available }?.memory?.matching(reading).orEmpty() else emptyList()

        @Synchronized
        fun getLearnedSurface(readingHiragana: String): String? {
            if (!LearningPolicy.canRead) return null
            return learningMemory[toHiragana(readingHiragana)]
        }

        @Synchronized
        fun getLearningCount(context: Context): Int {
            ensureInitialized(context)
            return learningMemory.size
        }

        @Synchronized
        fun clearLearningMemory(context: Context) {
            ensureInitialized(context)
            learningHistoryEpoch.incrementAndGet()
            if (BuildConfig.WINDOWS_ENGINE) com.zeroconvert.ime.engine.WindowsEngine.resetLearning()
            learningMemory.clear()
            saveLearningMemory(context)
            morphologyLearning?.clear()
        }

        @Synchronized
        fun lookupEmojis(readingHiragana: String): List<String> {
            return emojiDict[readingHiragana]?.toList() ?: emptyList()
        }

        @Synchronized
        fun buildEffectiveProfile(readingHiragana: String, userProfile: String): String {
            // Match any user dict entries whose reading appears in current readingHiragana
            val matchedHints = userDictEntries
                .filter { readingHiragana.contains(it.reading) }
                .take(2)
                .joinToString(",") { "${it.reading}:${it.word}" }

            val trimmedProfile = userProfile.trim().take(25)
            return when {
                matchedHints.isNotEmpty() && trimmedProfile.isNotEmpty() -> "$trimmedProfile $matchedHints".take(40)
                matchedHints.isNotEmpty() -> matchedHints.take(40)
                else -> trimmedProfile
            }
        }
    }

    suspend fun requestCandidates(
        composingText: ComposingText,
        leftContext: String,
        enableAI: Boolean = true,
        profile: String = "",
        personalize: Boolean = true,
        fixedSurfacePrefix: String = "",
        experimentalKeyboardCorrection: Boolean = false
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
                experimentalKeyboardCorrection = experimentalKeyboardCorrection
            ).map { it.copy(text = fixedSurfacePrefix + it.text,
                reading = if (BuildConfig.WINDOWS_ENGINE) toKatakana(fixedSurfacePrefix + toHiragana(it.reading)) else toKatakana(hiragana),
                segments = emptyList(), learningAllowed = it.isLearningTarget,
                engineConsumedReading = it.engineConsumedReading?.let { consumed -> fixedSurfacePrefix.length + consumed }) }
        }

        if (BuildConfig.WINDOWS_ENGINE) {
            return@withContext com.zeroconvert.ime.engine.WindowsEngine.candidates(composingText, leftContext,
                enableAI, profile, personalize, experimentalKeyboardCorrection)
        }
        val katakana = toKatakana(hiragana)
        val candidates = mutableListOf<Candidate>()

        // 1. Learning Memory Override (#1 priority when user previously selected a candidate for this exact reading)
        val learned = if (personalize) getLearnedSurface(hiragana) else null
        if (!learned.isNullOrEmpty()) {
            candidates.add(Candidate(text = learned, reading = katakana, cost = 2000f, isAI = false))
        }

        // 2. User Dictionary exact & prefix matches
        val userEntriesSnapshot = synchronized(Companion) {
            if (personalize) userDictEntries.toList() else emptyList()
        }
        val exactUserWords = userEntriesSnapshot.filter { it.reading == hiragana }.map { it.word }
        for (w in exactUserWords) {
            if (candidates.none { it.text == w }) {
                candidates.add(Candidate(text = w, reading = katakana, cost = 1500f, isAI = false))
            }
        }

        // 3. ZeroCon AI neural conversion (Runs on llama.cpp with \uEE03 profile / user-dict hints)
        var modelCandidate: Candidate? = null
        if (enableAI) {
            try {
                val effectiveProfile = buildEffectiveProfile(hiragana, profile)
                val aiResult = ZenzNative.convert(leftContext, katakana, effectiveProfile)

                if (ConversionCandidateMerge.validSurface(aiResult, hiragana) && aiResult != katakana && aiResult != hiragana) {
                    if (candidates.none { it.text == aiResult }) {
                        modelCandidate = Candidate(text = aiResult, reading = katakana, cost = 1000f, isAI = true)
                    }
                }
            } catch (t: Throwable) {
                Log.e(TAG, "AI neural conversion error for '$hiragana'", t)
            }
        }

        // 4. LOUDS System Dictionary Lookup (Exact, Multi-Morpheme Viterbi & Prefix matches)
        try {
            val keyboardEnds = if (experimentalKeyboardCorrection) composingText.keyboardTypoSurfaceEnds() else null
            val dictResults = ZenzNative.lookupDict(katakana, prefixSearch = true, maxCount = 30,
                keyboardEnds = keyboardEnds, userEntries = userEntriesSnapshot,
                learnedEntries = if (personalize) learnedEntries(katakana) else emptyList())
            val exactDictResults = dictResults.filter { it.reading == katakana }
            val prefixDictResults = dictResults.filter { it.reading != katakana }
            for (index in candidates.indices) {
                if (candidates[index].segments.isEmpty()) {
                    exactDictResults.firstOrNull { it.text == candidates[index].text }?.let { lexical ->
                        candidates[index] = candidates[index].copy(segments = lexical.segments,
                            learningAllowed = candidates[index].isLearningTarget && lexical.isLearningTarget)
                    }
                }
            }

            ConversionCandidateMerge.appendDistinct(candidates,
                ConversionCandidateMerge.mergeModelAndDictionary(modelCandidate, exactDictResults, hiragana))

            // 5. Emoji Dictionary candidates right after top AI / exact dictionary candidates
            val emojis = lookupEmojis(hiragana)
            for (emoji in emojis) {
                if (candidates.none { it.text == emoji }) {
                    candidates.add(Candidate(text = emoji, reading = katakana, cost = 500f, isAI = false))
                }
            }

            // 6. Remaining prefix dictionary matches
            ConversionCandidateMerge.appendDistinct(candidates, prefixDictResults)
        } catch (t: Throwable) {
            Log.e(TAG, "Dictionary lookup error for '$katakana'", t)
            modelCandidate?.let { ConversionCandidateMerge.appendDistinct(candidates, listOf(it)) }
        }

        // 7. Fallback standard candidates (Hiragana & Katakana)
        if (candidates.none { it.text == hiragana }) {
            candidates.add(Candidate(text = hiragana, reading = katakana, cost = -100f, isAI = false))
        }
        if (candidates.none { it.text == katakana }) {
            candidates.add(Candidate(text = katakana, reading = katakana, cost = -101f, isAI = false))
        }

        candidates
    }
}
