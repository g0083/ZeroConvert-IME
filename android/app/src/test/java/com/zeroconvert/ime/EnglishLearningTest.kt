package com.zeroconvert.ime

import android.content.Context
import android.content.ContextWrapper
import com.zeroconvert.ime.engine.WindowsEngine
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * JVM unit tests covering Android's English learning dictionary integration,
 * candidate IDs, LearningPolicy gating, commit recording rules, forget/clear,
 * and legacy zerocon_english_data JSON migration.
 */
class EnglishLearningTest {
    private data class RecordedCommit(
        val prefix: String,
        val word: String,
        val explicitSelection: Boolean,
        val inDictionary: Boolean,
        val expectedRevision: Long
    )

    private class FakeEnglishBackend : EnglishLearningBackend {
        var ready = true
        var suggestResponse = WindowsEngine.EnglishSuggestResponse()
        val suggestCalls = mutableListOf<Triple<String, Int, Boolean>>()
        val recordedCommits = mutableListOf<RecordedCommit>()
        val forgotten = mutableListOf<Pair<String, String>>()
        var promoted = mutableListOf<String>()
        var forgetResult = true
        var clearResult = true
        var clearedCount = 0

        override fun isReady(): Boolean = ready

        override fun suggest(
            prefix: String,
            limit: Int,
            readLearning: Boolean
        ): WindowsEngine.EnglishSuggestResponse {
            suggestCalls.add(Triple(prefix, limit, readLearning))
            return suggestResponse
        }

        override fun record(
            prefix: String,
            word: String,
            explicitSelection: Boolean,
            inDictionary: Boolean,
            expectedRevision: Long
        ) {
            recordedCommits.add(
                RecordedCommit(prefix, word, explicitSelection, inDictionary, expectedRevision)
            )
        }

        override fun forget(word: String, prefix: String): Boolean {
            forgotten.add(word to prefix)
            if (forgetResult) {
                promoted.removeAll { it.equals(word, ignoreCase = true) }
            }
            return forgetResult
        }

        override fun clear(): Boolean {
            clearedCount += 1
            promoted.clear()
            return clearResult
        }

        override fun promotedWords(readLearning: Boolean): List<String> =
            if (readLearning) promoted.toList() else emptyList()
    }

    private lateinit var backend: FakeEnglishBackend
    private lateinit var previousPolicyMode: LearningMode
    private val context: Context = object : ContextWrapper(null) {}

    @Before
    fun setUp() {
        previousPolicyMode = LearningPolicy.mode
        LearningPolicy.mode = LearningMode.READ_WRITE
        backend = FakeEnglishBackend()
        EnglishWordDictionary.setBackendForTest(backend)
        EnglishWordDictionary.seedWordsForTest(listOf("the", "hello", "help", "world", "github"))
    }

    @After
    fun tearDown() {
        EnglishWordDictionary.setBackendForTest(null)
        LearningPolicy.mode = previousPolicyMode
    }

    @Test
    fun englishCandidateIdsFormatAndParseOnlyLearnedPrefix() {
        assertEquals("en:zeroconvert", EnglishWordDictionary.englishCandidateId(" ZeroConvert "))
        assertEquals("", EnglishWordDictionary.englishCandidateId("   "))
        assertEquals("zeroconvert", EnglishWordDictionary.parseEnglishCandidateId("en:zeroconvert"))
        assertNull(EnglishWordDictionary.parseEnglishCandidateId("123"))
        assertNull(EnglishWordDictionary.parseEnglishCandidateId("en:"))
        assertNull(EnglishWordDictionary.parseEnglishCandidateId(""))
    }

    @Test
    fun isDictionaryWordChecksSeededDictionaryAndInputShape() {
        assertTrue(EnglishWordDictionary.isDictionaryWord("Hello"))
        assertTrue(EnglishWordDictionary.isDictionaryWord("  world  "))
        assertFalse(EnglishWordDictionary.isDictionaryWord("zeroconvert"))
        assertFalse(EnglishWordDictionary.isDictionaryWord("a"))
        assertFalse(EnglishWordDictionary.isDictionaryWord("hi2"))
    }

    @Test
    fun suggestAssignsEnCandidateIdOnlyToLearnedEntriesAndPromotedPrefixFallback() {
        backend.suggestResponse = WindowsEngine.EnglishSuggestResponse(
            suggestions = listOf(
                WindowsEngine.EnglishLearnedSuggestion(text = "helped", learned = true, key = "helped"),
                WindowsEngine.EnglishLearnedSuggestion(text = "helmet", learned = false, key = "")
            ),
            promotedPrefix = false
        )

        val candidates = EnglishWordDictionary.suggest(context, "he", limit = 8)
        assertEquals("helped", candidates[0].text)
        assertEquals("en:helped", candidates[0].engineCandidateId)
        assertEquals("helmet", candidates[1].text)
        assertEquals("", candidates[1].engineCandidateId)
        val staticHello = candidates.first { it.text == "hello" }
        assertEquals("", staticHello.engineCandidateId)

        // Exact promoted OOV word fallback receives an "en:" ID when promotedPrefix=true,
        // while an unpromoted fallback keeps an empty engineCandidateId.
        backend.suggestResponse = WindowsEngine.EnglishSuggestResponse(
            suggestions = emptyList(),
            promotedPrefix = true
        )
        val promotedFallback = EnglishWordDictionary.suggest(context, "ZeroConvert", limit = 8)
        assertEquals(1, promotedFallback.size)
        assertEquals("ZeroConvert", promotedFallback[0].text)
        assertEquals(-100f, promotedFallback[0].cost)
        assertEquals("en:zeroconvert", promotedFallback[0].engineCandidateId)

        backend.suggestResponse = WindowsEngine.EnglishSuggestResponse(
            suggestions = emptyList(),
            promotedPrefix = false
        )
        val unpromotedFallback = EnglishWordDictionary.suggest(context, "XyzOov", limit = 8)
        assertEquals(1, unpromotedFallback.size)
        assertEquals(-100f, unpromotedFallback[0].cost)
        assertEquals("", unpromotedFallback[0].engineCandidateId)
    }

    @Test
    fun learningPolicyEnforcesReadOnlyAndDisabledModes() {
        backend.suggestResponse = WindowsEngine.EnglishSuggestResponse(
            suggestions = listOf(
                WindowsEngine.EnglishLearnedSuggestion(text = "ZeroConvert", learned = true, key = "zeroconvert")
            ),
            promotedPrefix = true
        )

        LearningPolicy.mode = LearningMode.READ_ONLY
        val readOnlyCandidates = EnglishWordDictionary.suggest(context, "ze", limit = 8)
        assertEquals(1, backend.suggestCalls.size)
        assertEquals("ZeroConvert", readOnlyCandidates[0].text)
        assertEquals("en:zeroconvert", readOnlyCandidates[0].engineCandidateId)
        EnglishWordDictionary.recordCommit(context, "ze", "ZeroConvert", explicitSelection = true)
        assertTrue(backend.recordedCommits.isEmpty())

        LearningPolicy.mode = LearningMode.DISABLED
        val disabledCandidates = EnglishWordDictionary.suggest(context, "ze", limit = 8)
        assertEquals(1, backend.suggestCalls.size)
        assertEquals("ze", disabledCandidates[0].text)
        assertEquals("", disabledCandidates[0].engineCandidateId)
        EnglishWordDictionary.recordCommit(context, "ze", "ZeroConvert", explicitSelection = true)
        assertTrue(backend.recordedCommits.isEmpty())
    }

    @Test
    fun recordCommitForwardsInDictionaryAndExplicitSelectionIncludingSpaceAdoptRule() {
        val rev = KanaKanjiConverter.bumpLearningHistory()
        EnglishWordDictionary.recordCommit(context, "hello", "hello", explicitSelection = false, expectedRevision = rev)
        EnglishWordDictionary.recordCommit(context, "ZeroConvert", "ZeroConvert", explicitSelection = false, expectedRevision = rev)

        // Simulate Space-adopt guard in ZeroConvertInputMethodService.commitEnglishBuffer:
        // a real suggestion (cost >= 0 or text != prefix) counts as explicitSelection=true,
        // while an unpromoted fallback matching the raw buffer keeps explicitSelection=false.
        val realSuggestion = EnglishWordDictionary.suggest(context, "he").first()
        val realExplicit = realSuggestion.cost >= 0f || realSuggestion.text != "he"
        EnglishWordDictionary.recordCommit(context, "he", realSuggestion.text, explicitSelection = realExplicit, expectedRevision = rev)

        val fallbackOnly = EnglishWordDictionary.suggest(context, "newoov").first()
        val fallbackExplicit = fallbackOnly.cost >= 0f || fallbackOnly.text != "newoov"
        EnglishWordDictionary.recordCommit(context, "newoov", fallbackOnly.text, explicitSelection = fallbackExplicit, expectedRevision = rev)

        assertEquals(
            listOf(
                RecordedCommit("hello", "hello", explicitSelection = false, inDictionary = true, expectedRevision = rev),
                RecordedCommit("ZeroConvert", "ZeroConvert", explicitSelection = false, inDictionary = false, expectedRevision = rev),
                RecordedCommit("he", "hello", explicitSelection = true, inDictionary = true, expectedRevision = rev),
                RecordedCommit("newoov", "newoov", explicitSelection = false, inDictionary = false, expectedRevision = rev)
            ),
            backend.recordedCommits
        )
    }

    @Test
    fun forgetWordAndClearLearningUpdateBackendAndPromotedCache() {
        backend.promoted = mutableListOf("zeroconvert", "zulu")
        // Refresh cachedPromotedWords via forgetWord return path, then verify exact forget + clear
        assertTrue(EnglishWordDictionary.forgetWord("warmup", ""))
        backend.forgotten.clear()
        val initialWeights = EnglishWordDictionary.nextCharWeights("z")
        assertTrue(initialWeights.containsKey('e'))
        assertTrue(initialWeights.containsKey('u'))

        assertTrue(EnglishWordDictionary.forgetWord("zeroconvert", "ze"))
        assertEquals(listOf("zeroconvert" to "ze"), backend.forgotten)
        val weightsAfterForget = EnglishWordDictionary.nextCharWeights("z")
        assertFalse(weightsAfterForget.containsKey('e'))
        assertTrue(weightsAfterForget.containsKey('u'))

        assertTrue(EnglishWordDictionary.clearLearning())
        assertEquals(1, backend.clearedCount)
        assertTrue(EnglishWordDictionary.nextCharWeights("z").isEmpty())
    }

    @Test
    fun buildMigratedEnglishLearningJsonConvertsLegacyMapAndSkipsInvalidEntries() {
        val legacy = JSONObject()
            .put("ze", "ZeroConvert")
            .put("gh", "GitHub")
            .put("gi", "GitHub")
            .put("short", "a")
            .put("num", "abc1")
            .toString()

        val migrated = JSONObject(
            WindowsEngine.buildMigratedEnglishLearningJson(legacy, nowMs = 1_700_000_000_000L)
        )
        assertEquals(1, migrated.getInt("version"))

        val words = migrated.getJSONArray("words")
        assertEquals(2, words.length())
        val wordsByText = (0 until words.length())
            .map { words.getJSONObject(it) }
            .associateBy { it.getString("word") }
        val zeroEntry = wordsByText.getValue("ZeroConvert")
        assertEquals(1, zeroEntry.getInt("count"))
        assertEquals(1, zeroEntry.getInt("explicit_count"))
        assertFalse(zeroEntry.getBoolean("in_dictionary"))
        assertTrue(zeroEntry.getLong("updated_at_ms") > 1_700_000_000_000L)

        val ghEntry = wordsByText.getValue("GitHub")
        assertEquals(2, ghEntry.getInt("count"))
        assertEquals(2, ghEntry.getInt("explicit_count"))

        val overrides = migrated.getJSONArray("prefix_overrides")
        assertEquals(3, overrides.length())
        val overridesByPrefix = (0 until overrides.length())
            .map { overrides.getJSONObject(it) }
            .associate { it.getString("prefix") to it.getString("word") }
        assertEquals(
            mapOf("ze" to "ZeroConvert", "gh" to "GitHub", "gi" to "GitHub"),
            overridesByPrefix
        )

        val empty = JSONObject(WindowsEngine.buildMigratedEnglishLearningJson("not-json"))
        assertEquals(1, empty.getInt("version"))
        assertEquals(0, empty.getJSONArray("words").length())
        assertEquals(0, empty.getJSONArray("prefix_overrides").length())
    }

    @Test
    fun suggestEmailDomainsFiltersBuiltInAndLearnedEntriesWithoutTouchBiasMixing() {
        assertTrue(EnglishWordDictionary.isDictionaryWord("@gmail.com"))
        assertTrue(EnglishWordDictionary.isDictionaryWord("  @GMAIL.COM  "))
        assertFalse(EnglishWordDictionary.isDictionaryWord("@example.co.jp"))
        assertFalse(EnglishWordDictionary.isDictionaryWord("@"))

        val initial = EnglishWordDictionary.suggest(context, "@", limit = 8)
        assertEquals(8, initial.size)
        assertEquals("@gmail.com", initial[0].text)
        assertTrue(initial.all { it.engineCandidateId.isEmpty() })

        val filtered = EnglishWordDictionary.suggest(context, "@Y", limit = 8)
        assertEquals(
            listOf("@yahoo.co.jp", "@ymobile.ne.jp", "@yahoo.com"),
            filtered.map { it.text }
        )

        backend.suggestResponse = WindowsEngine.EnglishSuggestResponse(
            suggestions = listOf(
                WindowsEngine.EnglishLearnedSuggestion("@icloud.com", learned = true, key = "@icloud.com"),
                WindowsEngine.EnglishLearnedSuggestion("@example.co.jp", learned = true, key = "@example.co.jp")
            ),
            promotedPrefix = false
        )
        val merged = EnglishWordDictionary.suggest(context, "@", limit = 8)
        assertEquals("@icloud.com", merged[0].text)
        assertEquals("en:@icloud.com", merged[0].engineCandidateId)
        assertEquals("@example.co.jp", merged[1].text)
        assertEquals("en:@example.co.jp", merged[1].engineCandidateId)
        assertEquals("@gmail.com", merged[2].text)
        assertEquals("", merged[2].engineCandidateId)
        assertEquals(8, merged.size)

        // Unknown domain with touch biases must not fall back to English word typo suggestions.
        backend.suggestResponse = WindowsEngine.EnglishSuggestResponse()
        val biases = listOf(
            TouchKeyBias('x'),
            TouchKeyBias('z', listOf('e' to 0.9f, 'a' to 0.8f))
        )
        val unknown = EnglishWordDictionary.suggestWithTouchBias(context, "@xz", biases, limit = 8)
        assertEquals(listOf("@xz"), unknown.map { it.text })

        val weights = EnglishWordDictionary.nextCharWeights("@")
        assertTrue(weights.containsKey('g'))
        assertTrue(weights.containsKey('i'))
    }
}
