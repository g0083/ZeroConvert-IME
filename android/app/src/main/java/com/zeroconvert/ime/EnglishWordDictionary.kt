package com.zeroconvert.ime

import android.content.Context
import android.util.Log
import com.zeroconvert.ime.assist.TouchBiasTypoResolver
import com.zeroconvert.ime.engine.WindowsEngine
import java.util.Locale

internal interface EnglishLearningBackend {
    fun isReady(): Boolean
    fun suggest(prefix: String, limit: Int, readLearning: Boolean): WindowsEngine.EnglishSuggestResponse
    fun record(prefix: String, word: String, explicitSelection: Boolean, inDictionary: Boolean, expectedRevision: Long)
    fun forget(word: String, prefix: String): Boolean
    fun clear(): Boolean
    fun promotedWords(readLearning: Boolean): List<String>
}

object EnglishWordDictionary {
    private const val TAG = "EnglishWordDictionary"
    const val ENGLISH_CANDIDATE_ID_PREFIX = "en:"

    private object WindowsEngineBackend : EnglishLearningBackend {
        override fun isReady(): Boolean = WindowsEngine.isReady()
        override fun suggest(prefix: String, limit: Int, readLearning: Boolean) =
            WindowsEngine.englishSuggest(prefix, limit, readLearning)
        override fun record(
            prefix: String,
            word: String,
            explicitSelection: Boolean,
            inDictionary: Boolean,
            expectedRevision: Long
        ) = WindowsEngine.recordEnglishWord(prefix, word, explicitSelection, inDictionary, expectedRevision)
        override fun forget(word: String, prefix: String) =
            WindowsEngine.forgetEnglishWordBlocking(word, prefix)
        override fun clear() = WindowsEngine.clearEnglishLearningBlocking()
        override fun promotedWords(readLearning: Boolean) = WindowsEngine.englishPromotedWords(readLearning)
    }

    @Volatile
    private var backend: EnglishLearningBackend = WindowsEngineBackend

    @Volatile
    private var loaded = false
    @Volatile
    private var cachedNextWeights: Pair<String, Map<Char, Float>>? = null
    @Volatile
    private var cachedPromotedWords: List<String> = emptyList()
    @Volatile
    private var promotedRevision: Long = 0L

    private val words = mutableListOf<String>()
    private val wordSet = HashSet<String>()
    /** 辞書順のインデックス。前方一致範囲を二分探索で絞るために使う。 */
    @Volatile
    private var sortedIndex: IntArray? = null

    /**
     * Common email domains suggested in English mode when typing `@`.
     * Must stay aligned with `windows/crates/shared/src/english_learning.rs`.
     */
    val DEFAULT_EMAIL_DOMAINS: List<String> = listOf(
        "@gmail.com",
        "@icloud.com",
        "@yahoo.co.jp",
        "@outlook.jp",
        "@outlook.com",
        "@docomo.ne.jp",
        "@ezweb.ne.jp",
        "@softbank.ne.jp",
        "@au.com",
        "@hotmail.com",
        "@i.softbank.jp",
        "@ymobile.ne.jp",
        "@uqmobile.jp",
        "@rakuten.jp",
        "@live.jp",
        "@me.com",
        "@mac.com",
        "@proton.me",
        "@protonmail.com",
        "@yahoo.com",
        "@nifty.com",
        "@biglobe.ne.jp",
        "@so-net.ne.jp",
        "@ocn.ne.jp"
    )
    private val defaultEmailDomainSet: Set<String> = DEFAULT_EMAIL_DOMAINS.toHashSet()

    fun isEmailDomainContinuationChar(ch: Char): Boolean =
        ch in 'a'..'z' || ch in 'A'..'Z' || ch in '0'..'9' || ch == '.' || ch == '-' || ch == '_'

    fun isEmailDomainPrefix(prefix: String): Boolean =
        prefix.startsWith("@") &&
            prefix.length <= 64 &&
            prefix.drop(1).all { isEmailDomainContinuationChar(it) }

    fun isDefaultEmailDomain(word: String): Boolean {
        val lower = word.trim().lowercase(Locale.ROOT)
        return lower.startsWith("@") && defaultEmailDomainSet.contains(lower)
    }

    fun englishCandidateId(wordLower: String): String {
        val clean = wordLower.trim().lowercase(Locale.ROOT)
        return if (clean.isEmpty()) "" else "$ENGLISH_CANDIDATE_ID_PREFIX$clean"
    }

    fun parseEnglishCandidateId(id: String): String? {
        if (!id.startsWith(ENGLISH_CANDIDATE_ID_PREFIX)) return null
        return id.removePrefix(ENGLISH_CANDIDATE_ID_PREFIX).takeIf { it.isNotEmpty() }
    }

    private fun updateCachedPromotedWords(wordsList: List<String>) {
        cachedPromotedWords = wordsList
        promotedRevision += 1L
        cachedNextWeights = null
    }

    @Synchronized
    internal fun setBackendForTest(testBackend: EnglishLearningBackend?) {
        backend = testBackend ?: WindowsEngineBackend
        cachedPromotedWords = emptyList()
        promotedRevision += 1L
        cachedNextWeights = null
    }

    @Synchronized
    internal fun seedWordsForTest(testWords: List<String>) {
        words.clear()
        wordSet.clear()
        for (raw in testWords) {
            val w = raw.trim()
            if (w.isNotEmpty() && w.all { it.isLetter() }) {
                words.add(w)
                wordSet.add(w.lowercase(Locale.ROOT))
            }
        }
        loaded = true
        sortedIndex = null
        cachedNextWeights = null
    }

    @Synchronized
    fun ensureLoaded(context: Context) {
        // 読み込めて入っている間はContextに触らない（JVM単体テストのため）。
        if (loaded && words.isNotEmpty()) return
        val file = EnglishDictionaryManager.cacheFile(context)
        if (loaded) {
            // サービスはダウンロードより前に起動していることがある。その間は空のまま
            // ロード済みになるので、ファイルが現れたら空→実の遷移で読み直す。
            if (!file.isFile) return
            loaded = false
        }
        if (file.isFile) {
            try {
                file.useLines(Charsets.UTF_8) { lines ->
                    for (line in lines) {
                        if (line.startsWith("#")) continue
                        val w = line.trim()
                        if (w.length >= 2 && w.all { it in 'a'..'z' } && wordSet.add(w)) {
                            words.add(w)
                        }
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "Failed to load ${file.path}", e)
            }
        }
        loaded = true
        sortedIndex = null
        cachedNextWeights = null
        if (backend.isReady() && LearningPolicy.canRead) {
            updateCachedPromotedWords(backend.promotedWords(true))
        }
        Log.i(TAG, "Loaded ${words.size} english words")
    }

    /** 辞書順インデックスを必要になった時点で作る。 */
    private fun prefixIndex(): IntArray {
        sortedIndex?.let { return it }
        synchronized(this) {
            sortedIndex?.let { return it }
            val built = (0 until words.size).sortedBy { words[it] }.toIntArray()
            sortedIndex = built
            return built
        }
    }

    /**
     * 頻度順（キャッシュの行順）で上位 [limit] 件の前方一致候補を返す。
     * 単語列はアルファベット順ではないため、インデックスを二分探索して一致範囲を求め、
     * その中から順位の小さいものを選ぶ。
     */
    private fun prefixMatches(p: String, limit: Int): List<String> {
        if (limit <= 0) return emptyList()
        val index = prefixIndex()
        var low = 0
        var high = index.size
        while (low < high) {
            val mid = (low + high) ushr 1
            if (words[index[mid]] < p) low = mid + 1 else high = mid
        }
        val hits = ArrayList<Int>(limit * 2)
        var position = low
        while (position < index.size) {
            val word = words[index[position]]
            if (!word.startsWith(p)) break
            if (word.length > p.length) hits.add(index[position])
            position++
        }
        hits.sort()
        while (hits.size > limit) hits.removeAt(hits.size - 1)
        return hits.map { words[it] }
    }

    /** ダウンロード完了直後に呼び、再起動なしで次回の補完から使えるようにする。 */
    @Synchronized
    fun reload(context: Context) {
        loaded = false
        ensureLoaded(context)
    }

    fun isDictionaryWord(word: String): Boolean {
        val lower = word.trim().lowercase(Locale.ROOT)
        if (lower.startsWith("@")) return defaultEmailDomainSet.contains(lower)
        if (lower.length < 2 || !lower.all { it in 'a'..'z' }) return false
        return synchronized(this) { wordSet.contains(lower) }
    }

    fun recordCommit(
        context: Context,
        prefix: String,
        word: String,
        explicitSelection: Boolean,
        expectedRevision: Long = KanaKanjiConverter.learningHistoryRevision
    ) {
        ensureLoaded(context)
        if (!LearningPolicy.canWrite) return
        val inDictionary = isDictionaryWord(word)
        backend.record(prefix, word, explicitSelection, inDictionary, expectedRevision)
    }

    @Deprecated("Use recordCommit", ReplaceWith("recordCommit(context, prefix, word, true)"))
    fun recordSelection(context: Context, prefix: String, word: String) {
        recordCommit(context, prefix, word, explicitSelection = true)
    }

    fun forgetWord(word: String, prefix: String = ""): Boolean {
        val forgot = backend.forget(word, prefix)
        if (forgot) {
            updateCachedPromotedWords(backend.promotedWords(LearningPolicy.canRead))
        }
        return forgot
    }

    fun clearLearning(): Boolean {
        val cleared = backend.clear()
        updateCachedPromotedWords(emptyList())
        return cleared
    }

    fun suggest(context: Context, prefix: String, limit: Int = 8): List<Candidate> {
        val isEmailDomain = prefix.startsWith("@")
        if (!isEmailDomain) {
            ensureLoaded(context)
        }
        if (prefix.isEmpty() || limit <= 0) return emptyList()
        if (isEmailDomain && !isEmailDomainPrefix(prefix)) return emptyList()
        val p = prefix.lowercase(Locale.ROOT)
        val capitalize = !isEmailDomain && prefix.isNotEmpty() && prefix[0].isUpperCase()
        val allCaps = !isEmailDomain && prefix.length >= 2 && prefix.all { !it.isLetter() || it.isUpperCase() }
        val result = mutableListOf<Candidate>()

        val readLearning = LearningPolicy.canRead
        val learned = if (readLearning) {
            backend.suggest(prefix, limit, true)
        } else {
            WindowsEngine.EnglishSuggestResponse()
        }
        for ((index, entry) in learned.suggestions.withIndex()) {
            if (result.size >= limit) break
            val candidateText = if (isEmailDomain) entry.text.lowercase(Locale.ROOT) else entry.text
            if (candidateText == prefix) continue
            if (result.none { it.text == candidateText }) {
                result.add(
                    Candidate(
                        text = candidateText,
                        reading = prefix,
                        cost = 2000f - index,
                        isAI = false,
                        engineCandidateId = if (entry.learned) {
                            englishCandidateId(entry.key.ifEmpty { candidateText })
                        } else {
                            ""
                        }
                    )
                )
            }
        }

        if (isEmailDomain) {
            for (domain in DEFAULT_EMAIL_DOMAINS) {
                if (result.size >= limit) break
                if (domain.startsWith(p) && domain != prefix && result.none { it.text == domain }) {
                    result.add(
                        Candidate(
                            text = domain,
                            reading = prefix,
                            cost = 1000f - result.size.toFloat(),
                            isAI = false,
                            engineCandidateId = ""
                        )
                    )
                }
            }
        } else {
            // frequency-ranked prefix matches
            for (w in prefixMatches(p, limit)) {
                if (result.size >= limit) break
                val cand = adjustCase(w, prefix, capitalize, allCaps)
                if (cand != prefix && result.none { it.text == cand }) {
                    result.add(
                        Candidate(
                            text = cand,
                            reading = prefix,
                            cost = 1000f - result.size.toFloat(),
                            isAI = false,
                            engineCandidateId = ""
                        )
                    )
                }
            }
        }

        // fallback: the typed text itself
        if (result.isEmpty()) {
            result.add(
                Candidate(
                    text = prefix,
                    reading = prefix,
                    cost = -100f,
                    isAI = false,
                    engineCandidateId = if (readLearning && learned.promotedPrefix) {
                        englishCandidateId(p)
                    } else {
                        ""
                    }
                )
            )
        }
        return result
    }

    /**
     * Non-blocking next-character continuation weights for active English prefix typing.
     * Returns `emptyMap()` until the dictionary has been loaded on a background thread.
     */
    fun nextCharWeights(prefix: String): Map<Char, Float> {
        val isEmailDomain = prefix.startsWith("@")
        if ((words.isEmpty() && !isEmailDomain) || prefix.isBlank()) return emptyMap()
        val key = prefix.lowercase(Locale.ROOT)
        val includeLearned = LearningPolicy.canRead
        val promoted = if (includeLearned) cachedPromotedWords else emptyList()
        val cacheKey = "$includeLearned:$promotedRevision:$key"
        cachedNextWeights?.let { (cachedPrefix, cachedMap) ->
            if (cachedPrefix == cacheKey) return cachedMap
        }
        val snapshot = if (isEmailDomain) {
            val learnedDomains = promoted.filter { it.startsWith("@") }
            if (learnedDomains.isEmpty()) DEFAULT_EMAIL_DOMAINS else learnedDomains + DEFAULT_EMAIL_DOMAINS
        } else {
            synchronized(this) {
                if (promoted.isEmpty()) words.toList() else promoted + words
            }
        }
        val weights = NextKeyPredictor.computeEnglishNextCharWeights(key, snapshot)
        cachedNextWeights = cacheKey to weights
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
        if (prefix.startsWith("@") || touchBiases.isEmpty() || prefix.length < 2) return base
        val hasDictionaryHit = base.any { it.cost > 0f }
        if (hasDictionaryHit) return base

        val promoted = if (LearningPolicy.canRead) {
            backend.promotedWords(true).also { updateCachedPromotedWords(it) }
        } else {
            emptyList()
        }
        val snapshot = synchronized(this) {
            if (promoted.isEmpty()) words.toList() else promoted + words
        }
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

    private fun adjustCase(word: String, prefix: String, capitalize: Boolean, allCaps: Boolean): String {
        if (word.isEmpty()) return word
        if (allCaps) return word.uppercase(Locale.ROOT)
        if (!capitalize) return word
        if (prefix.length <= word.length && word.lowercase(Locale.ROOT).startsWith(prefix.lowercase(Locale.ROOT))) {
            return prefix + word.substring(prefix.length)
        }
        return word[0].uppercaseChar() + word.substring(1)
    }
}
