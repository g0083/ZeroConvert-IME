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
        private const val KEY_LEARNING_MEM_V2 = "learning_memory_v2_json"
        private const val MAX_LEARNING_ENTRIES = 500
        private const val MAX_OUTPUTS_PER_RUBY = 5
        private const val MAX_PREDICTION_CHARS = 8

        /**
         * One committed surface, its short leading prediction, and the committed segment chain
         * (katakana reading to surface) that drives Gboard-style continuation candidates.
         */
        data class LearnedOutput(val surface: String, val predict: String,
                                 val segments: List<Pair<String, String>> = emptyList())

        @Volatile
        private var isEmojiLoaded = false
        private val emojiDict = HashMap<String, MutableList<String>>()

        @Volatile
        private var isUserDataLoaded = false
        private val userDictEntries = mutableListOf<UserDictEntry>()
        // LinkedHashMap in access/insertion order: readingHiragana -> outputs (most recent surface at 0)
        private val learningMemory = LinkedHashMap<String, MutableList<LearnedOutput>>()
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
            val learnJsonV2 = prefs.getString(KEY_LEARNING_MEM_V2, null)
            if (!learnJsonV2.isNullOrEmpty()) {
                try {
                    val obj = JSONObject(learnJsonV2)
                    val keys = obj.keys()
                    while (keys.hasNext()) {
                        val k = keys.next()
                        val v = obj.opt(k)
                        if (v !is JSONArray) continue
                        val list = mutableListOf<LearnedOutput>()
                        for (i in 0 until v.length()) {
                            val item = v.optJSONObject(i) ?: continue
                            val s = item.optString("s", "").trim()
                            if (s.isEmpty()) continue
                            val p = item.optString("p", "").trim()
                            val segs = mutableListOf<Pair<String, String>>()
                            val g = item.optJSONArray("g")
                            if (g != null) {
                                for (j in 0 until g.length()) {
                                    val pair = g.optJSONArray(j) ?: continue
                                    val r = pair.optString(0, "").trim()
                                    val w = pair.optString(1, "").trim()
                                    if (r.isNotEmpty() && w.isNotEmpty()) segs.add(r to w)
                                }
                            }
                            if (!list.any { it.surface == s }) list.add(LearnedOutput(s, p, segs))
                        }
                        if (list.isNotEmpty()) learningMemory[k] = list
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "Failed to parse learning_memory_v2_json", e)
                }
                return
            }
            val learnJson = prefs.getString(KEY_LEARNING_MEM, null)
            if (!learnJson.isNullOrEmpty()) {
                try {
                    val obj = JSONObject(learnJson)
                    val keys = obj.keys()
                    while (keys.hasNext()) {
                        val k = keys.next()
                        val v = obj.opt(k)
                        if (v is JSONArray) {
                            val list = mutableListOf<LearnedOutput>()
                            for (i in 0 until v.length()) {
                                val s = v.optString(i, "")
                                if (s.isNotEmpty() && !list.any { it.surface == s }) {
                                    // Legacy entries have no segments; keep short ones usable for prediction.
                                    list.add(LearnedOutput(s, s.takeIf { it.length <= MAX_PREDICTION_CHARS }.orEmpty()))
                                }
                            }
                            if (list.isNotEmpty()) learningMemory[k] = list
                        } else if (v is String && v.isNotEmpty()) {
                            learningMemory[k] = mutableListOf(LearnedOutput(v,
                                v.takeIf { it.length <= MAX_PREDICTION_CHARS }.orEmpty()))
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
            for ((k, list) in learningMemory) {
                val arr = JSONArray()
                for (item in list) {
                    val entry = JSONObject()
                    entry.put("s", item.surface)
                    entry.put("p", item.predict)
                    if (item.segments.isNotEmpty()) {
                        val g = JSONArray()
                        for ((r, w) in item.segments) {
                            g.put(JSONArray(listOf(r, w)))
                        }
                        entry.put("g", g)
                    }
                    arr.put(entry)
                }
                obj.put(k, arr)
            }
            context.getSharedPreferences(PREFS_USER_DATA, Context.MODE_PRIVATE)
                .edit()
                .putString(KEY_LEARNING_MEM_V2, obj.toString())
                .remove(KEY_LEARNING_MEM)
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

        /**
         * Short continuation prediction for the prefix suggestion:
         * the first dictionary segment only (word / single clause unit), when it is short enough.
         */
        private fun predictionFor(surface: String, segments: List<DictionarySegment>): String {
            if (segments.isEmpty()) {
                return surface.takeIf { it.length in 1..MAX_PREDICTION_CHARS }.orEmpty()
            }
            val first = segments.first()
            return first.surface.takeIf {
                it.isNotEmpty() && it.length <= MAX_PREDICTION_CHARS && it.length <= surface.length
            }.orEmpty()
        }

        private fun insertLearned(readingHiragana: String, output: LearnedOutput) {
            val normReading = toHiragana(readingHiragana.trim())
            if (normReading.isEmpty() || output.surface.isEmpty()) return
            val list = learningMemory.remove(normReading) ?: mutableListOf()
            list.removeAll { it.surface == output.surface }
            list.add(0, output)
            while (list.size > MAX_OUTPUTS_PER_RUBY) {
                list.removeAt(list.size - 1)
            }
            learningMemory[normReading] = list
            while (learningMemory.size > MAX_LEARNING_ENTRIES) {
                val eldestKey = learningMemory.keys.firstOrNull() ?: break
                learningMemory.remove(eldestKey)
            }
        }

        /**
         * Gboard-style continuation from a committed segment chain: the segment(s) covering the
         * input reading plus the following one (は → は天気が), capped at MAX_PREDICTION_CHARS.
         */
        private fun continuationCandidate(segs: List<Pair<String, String>>, inputKatakana: String): Candidate? {
            if (segs.isEmpty() || inputKatakana.isEmpty()) return null
            var coveredRead = ""
            var coveredSurf = ""
            var index = 0
            while (index < segs.size && coveredRead.length < inputKatakana.length) {
                coveredRead += segs[index].first
                coveredSurf += segs[index].second
                index++
            }
            if (coveredRead.length >= inputKatakana.length && coveredRead.startsWith(inputKatakana)) {
                if (coveredRead == inputKatakana) {
                    return buildContinuation(coveredRead, coveredSurf, index, segs)
                }
                // Input ends inside the first segment; the bare leading prediction already covers it.
                return null
            }
            for ((i, seg) in segs.withIndex()) {
                if (seg.first == inputKatakana) {
                    return buildContinuation(seg.first, seg.second, i + 1, segs)
                }
            }
            for (seg in segs) {
                if (seg.first.startsWith(inputKatakana)) {
                    return Candidate(text = seg.second, reading = seg.first, cost = 1150f,
                        isAI = false, isLearningPrediction = true)
                }
            }
            // Mid-segment kana tail, e.g. committing 今日 inside 今日は and then typing は.
            val hiraganaInput = toHiragana(inputKatakana)
            if (hiraganaInput.isNotEmpty() && hiraganaInput.all { it in '\u3041'..'\u3096' }) {
                for ((i, seg) in segs.withIndex()) {
                    if (i + 1 >= segs.size) break
                    if (seg.first.endsWith(inputKatakana) && seg.second.endsWith(hiraganaInput)) {
                        return buildContinuation(inputKatakana, hiraganaInput, i + 1, segs)
                    }
                }
            }
            return null
        }

        private fun buildContinuation(coveredRead: String, coveredSurf: String, nextIndex: Int,
                                      segs: List<Pair<String, String>>): Candidate {
            var text = coveredSurf
            var reading = coveredRead
            if (nextIndex in segs.indices) {
                val extra = segs[nextIndex]
                if (text.length + extra.second.length <= MAX_PREDICTION_CHARS) {
                    text += extra.second
                    reading += extra.first
                }
            }
            return Candidate(text = text, reading = reading, cost = 1150f, isAI = false, isLearningPrediction = true)
        }

        /** Returns the learned chunk that follows the committed editor text, for the post-commit bar. */
        @Synchronized
        internal fun postCommitContinuations(leftContext: String, limit: Int = 3): List<Candidate> {
            if (!LearningPolicy.canRead || leftContext.isEmpty()) return emptyList()
            val found = mutableListOf<Candidate>()
            val snapshot = ArrayList(learningMemory.entries)
            var scanned = 0
            for (index in snapshot.indices.reversed()) {
                if (scanned++ >= 200 || found.size >= limit) break
                for (output in snapshot[index].value) {
                    if (output.segments.isEmpty()) continue
                    val chunk = nextChunk(output, leftContext) ?: continue
                    if (found.none { it.text == chunk }) {
                        found.add(Candidate(text = chunk, reading = "", cost = 1150f,
                            isAI = false, isLearningPrediction = true))
                    }
                    if (found.size >= limit) break
                }
            }
            return found
        }

        /** Chunk that starts at the boundary where the already-committed text ends. */
        private fun nextChunk(output: LearnedOutput, leftContext: String): String? {
            val surface = output.surface
            if (surface.length < 2) return null
            var matched = 0
            for (length in minOf(surface.length, leftContext.length) downTo 1) {
                if (leftContext.endsWith(surface.substring(0, length))) { matched = length; break }
            }
            if (matched == 0 || matched >= surface.length) return null
            var offset = 0
            for ((index, segment) in output.segments.withIndex()) {
                val segmentSurface = segment.second
                val end = offset + segmentSurface.length
                if (matched in offset..end) {
                    val tail = segmentSurface.substring(matched - offset)
                    val chunk = tail.ifEmpty { output.segments.getOrNull(index + 1)?.second.orEmpty() }
                    return chunk.take(MAX_PREDICTION_CHARS).takeIf { it.isNotEmpty() }
                }
                offset = end
            }
            return null
        }

        private fun segmentChain(segments: List<DictionarySegment>): List<Pair<String, String>> =
            segments.map { toKatakana(it.reading) to it.surface }.take(32)

        @Synchronized
        fun recordSelection(context: Context, readingHiragana: String, surface: String,
                            segments: List<DictionarySegment> = emptyList()) {
            ensureInitialized(context)
            if (!LearningPolicy.canWrite) return
            val normReading = toHiragana(readingHiragana.trim())
            val normSurface = surface.trim()
            if (normReading.isEmpty() || normSurface.isEmpty()) return
            val usableSegments = segments.takeIf {
                it.isNotEmpty() && it.joinToString("") { span -> span.surface } == normSurface
            }.orEmpty()
            insertLearned(normReading, LearnedOutput(normSurface,
                predictionFor(normSurface, usableSegments), segmentChain(usableSegments)))
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
            recordSelection(context, selection.reading, selection.surface, selection.segments)
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
            return learningMemory[toHiragana(readingHiragana)]?.firstOrNull()?.surface
        }

        @Synchronized
        fun getLearnedSurfaces(readingHiragana: String): List<String> {
            if (!LearningPolicy.canRead) return emptyList()
            return learningMemory[toHiragana(readingHiragana)]?.map { it.surface } ?: emptyList()
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
        internal fun recordSelectionForTest(readingHiragana: String, surface: String,
                                            segments: List<DictionarySegment> = emptyList()) {
            val normReading = toHiragana(readingHiragana.trim())
            val normSurface = surface.trim()
            if (normReading.isEmpty() || normSurface.isEmpty()) return
            val usableSegments = segments.takeIf {
                it.isNotEmpty() && it.joinToString("") { span -> span.surface } == normSurface
            }.orEmpty()
            insertLearned(normReading, LearnedOutput(normSurface,
                predictionFor(normSurface, usableSegments), segmentChain(usableSegments)))
        }

        @Synchronized
        internal fun clearLearningMemoryForTest() {
            learningMemory.clear()
        }

        /** Restores the on-disk history after device tests seeded the in-memory copy. */
        @Synchronized
        internal fun reloadUserDataForTest(context: Context) {
            loadUserData(context)
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
            ).map { it.copy(text = fixedSurfacePrefix + it.text,
                reading = if (BuildConfig.WINDOWS_ENGINE) toKatakana(fixedSurfacePrefix + toHiragana(it.reading)) else toKatakana(hiragana),
                segments = emptyList(), learningAllowed = it.isLearningTarget,
                engineConsumedReading = it.engineConsumedReading?.let { consumed -> fixedSurfacePrefix.length + consumed }) }
        }

        if (BuildConfig.WINDOWS_ENGINE) {
            return@withContext com.zeroconvert.ime.engine.WindowsEngine.candidates(composingText, leftContext,
                enableAI, profile, personalize, experimentalKeyboardCorrection,
                predictiveConversion = predictiveConversion, continuation = continuation)
        }
        val katakana = toKatakana(hiragana)
        val candidates = mutableListOf<Candidate>()

        // 1. Learning Memory Override (#1 priority when user previously selected a candidate for this exact reading)
        val learnedList = if (personalize) getLearnedSurfaces(hiragana) else emptyList()
        for (learned in learnedList) {
            candidates.add(Candidate(text = learned, reading = katakana, cost = 2000f, isAI = false))
        }

        // 1.1 Prefix Suggestion from Learning Memory (proactive suggestion for 2+ char prefixes).
        // Shows only the learned short prediction (leading segment), newest learning first,
        // and is flagged so it never leaks into inline preview or auto commit.
        if (personalize && predictiveConversion && hiragana.length >= 2) {
            val prefixMatches = synchronized(Companion) {
                val snapshot = ArrayList(learningMemory.entries)
                val matches = mutableListOf<Pair<String, String>>()
                for (index in snapshot.indices.reversed()) {
                    val (r, outputs) = snapshot[index]
                    if (r.startsWith(hiragana) && r != hiragana) {
                        val predict = outputs.firstOrNull()?.predict.orEmpty()
                        if (predict.isNotEmpty()) matches.add(r to predict)
                        if (matches.size >= 3) break
                    }
                }
                matches
            }
            for ((r, word) in prefixMatches) {
                if (candidates.none { it.text == word }) {
                    candidates.add(Candidate(text = word, reading = toKatakana(r), cost = 1200f,
                        isAI = false, isLearningPrediction = true))
                }
            }
        }

        // 1.2 Learned continuation (segment chain): previously committed phrases whose reading
        // continues from the typed input, e.g. typing は after committing 今日 suggests は天気が.
        // Candidate-bar only (flagged), so it never leaks into inline preview or auto commit.
        if (personalize && predictiveConversion && hiragana.isNotEmpty()) {
            val continuations = mutableListOf<Candidate>()
            val learned = synchronized(Companion) {
                val snapshot = ArrayList(learningMemory.entries)
                val found = mutableListOf<Candidate>()
                var scanned = 0
                for (index in snapshot.indices.reversed()) {
                    if (scanned++ >= 200 || found.size >= 3) break
                    val chains = snapshot[index].value.mapNotNull { it.segments.takeIf { segs -> segs.isNotEmpty() } }
                    for (segs in chains) {
                        if (found.size >= 3) break
                        val candidate = continuationCandidate(segs, katakana) ?: continue
                        if (found.none { it.text == candidate.text }) found.add(candidate)
                    }
                }
                found
            }
            for (candidate in learned) {
                if (candidates.none { it.text == candidate.text } &&
                    continuations.none { it.text == candidate.text }) {
                    continuations.add(candidate)
                }
            }
            // Older chains recorded by morphology learning also continue from the typed prefix.
            if (continuations.size < 3) {
                val morphEntries = synchronized(Companion) {
                    if (LearningPolicy.canRead) {
                        morphologyLearning?.takeIf { it.available }?.memory?.prefixMatching(katakana).orEmpty()
                    } else emptyList()
                }
                for (entry in morphEntries) {
                    if (continuations.size >= 3) break
                    if (entry.reading != katakana && entry.surface.length in 1..MAX_PREDICTION_CHARS &&
                        candidates.none { it.text == entry.surface } &&
                        continuations.none { it.text == entry.surface }) {
                        continuations.add(Candidate(text = entry.surface, reading = entry.reading, cost = 1150f,
                            isAI = false, isLearningPrediction = true))
                    }
                }
            }
            candidates.addAll(continuations)
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
            val dictResults = ZenzNative.lookupDict(katakana, prefixSearch = predictiveConversion, maxCount = 30,
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

        candidates.distinctBy { it.text to it.reading }
    }
}
