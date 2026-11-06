package com.zeroconvert.ime

import com.zeroconvert.ime.azookey.input.ComposingText
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

class KanaKanjiConverterLearningTest {

    @Before
    fun setUp() {
        KanaKanjiConverter.clearLearningMemoryForTest()
    }

    @Test
    fun multipleSelectionsForSameReadingArePreservedInLRUOrder() {
        // 過去に「効果」を確定し、直近で「降下」を確定
        KanaKanjiConverter.recordSelectionForTest("こうか", "効果")
        KanaKanjiConverter.recordSelectionForTest("こうか", "降下")

        val surfaces = KanaKanjiConverter.getLearnedSurfaces("こうか")
        assertEquals(listOf("降下", "効果"), surfaces)
        assertEquals("降下", KanaKanjiConverter.getLearnedSurface("こうか"))
    }

    @Test
    fun reselectingExistingEntryBumpsItToTheTop() {
        KanaKanjiConverter.recordSelectionForTest("こうか", "効果")
        KanaKanjiConverter.recordSelectionForTest("こうか", "降下")
        KanaKanjiConverter.recordSelectionForTest("こうか", "硬化")
        assertEquals(listOf("硬化", "降下", "効果"), KanaKanjiConverter.getLearnedSurfaces("こうか"))

        // 再度「効果」を確定すると先頭へ昇格
        KanaKanjiConverter.recordSelectionForTest("こうか", "効果")
        assertEquals(listOf("効果", "硬化", "降下"), KanaKanjiConverter.getLearnedSurfaces("こうか"))
    }

    @Test
    fun maxOutputsPerRubyIsCappedAtFive() {
        for (i in 1..7) {
            KanaKanjiConverter.recordSelectionForTest("てすと", "候補$i")
        }
        val surfaces = KanaKanjiConverter.getLearnedSurfaces("てすと")
        assertEquals(5, surfaces.size)
        assertEquals(listOf("候補7", "候補6", "候補5", "候補4", "候補3"), surfaces)
    }

    @Test
    fun prefixMatchesAreSuggestedForTwoOrMoreChars() = runBlocking {
        KanaKanjiConverter.recordSelectionForTest("ぷろぐらみんぐ", "プログラミング")

        val converter = KanaKanjiConverter()
        val text = ComposingText()
        text.insertAtCursorPosition("ぷろ", inputStyle = com.zeroconvert.ime.azookey.input.InputStyle.DIRECT)

        val candidates = converter.requestCandidates(
            composingText = text,
            leftContext = "",
            enableAI = false,
            personalize = true
        )

        val hasSuggested = candidates.any { it.text == "プログラミング" }
        assertTrue("Expected 'プログラミング' to be suggested from learning memory prefix match", hasSuggested)

        val disabledCandidates = converter.requestCandidates(
            composingText = text,
            leftContext = "",
            enableAI = false,
            personalize = true,
            predictiveConversion = false
        )
        val hasSuggestedDisabled = disabledCandidates.any { it.text == "プログラミング" }
        assertFalse("Expected 'プログラミング' to be suppressed when predictive conversion is disabled", hasSuggestedDisabled)
    }

    @Test
    fun prefixPredictionUsesLearnedPredictionSurfaceOnly() = runBlocking {
        // 学習時に分節から先頭分節だけを prediction として保存し、prefix 予測ではそれを使う
        val segments = listOf(
            DictionarySegment("キョウ", "今日", 100, 101, 5, 0f),
            DictionarySegment("ハ", "は", 102, 103, 6, 0f)
        )
        KanaKanjiConverter.recordSelectionForTest("きょうは", "今日は", segments)

        val converter = KanaKanjiConverter()
        val text = ComposingText()
        text.insertAtCursorPosition("きょ", inputStyle = com.zeroconvert.ime.azookey.input.InputStyle.DIRECT)

        val candidates = converter.requestCandidates(text, "", enableAI = false, personalize = true)
        val prediction = candidates.filter { it.isLearningPrediction }
        assertEquals(listOf("今日"), prediction.map { it.text })

        // 完全一致の読みでは学習した表記全文が従来どおり使える
        val fullText = ComposingText()
        fullText.insertAtCursorPosition("きょうは", inputStyle = com.zeroconvert.ime.azookey.input.InputStyle.DIRECT)
        val fullCandidates = converter.requestCandidates(fullText, "", enableAI = false, personalize = true)
        assertTrue(fullCandidates.any { it.text == "今日は" && !it.isLearningPrediction })
    }

    @Test
    fun prefixPredictionsComeFromTheMostRecentLearningsFirst() = runBlocking {
        KanaKanjiConverter.recordSelectionForTest("こんや", "今夜")
        KanaKanjiConverter.recordSelectionForTest("こんばん", "今晩")

        val converter = KanaKanjiConverter()
        val text = ComposingText()
        text.insertAtCursorPosition("こん", inputStyle = com.zeroconvert.ime.azookey.input.InputStyle.DIRECT)

        val candidates = converter.requestCandidates(text, "", enableAI = false, personalize = true)
        val predictions = candidates.filter { it.isLearningPrediction }.map { it.text }
        assertEquals(listOf("今晩", "今夜"), predictions)
    }

    @Test
    fun longLearnedSurfacesWithoutSegmentsAreNotPredicted() = runBlocking {
        KanaKanjiConverter.recordSelectionForTest("まいにちまいにち", "毎日毎日毎日毎日毎日")

        val converter = KanaKanjiConverter()
        val text = ComposingText()
        text.insertAtCursorPosition("まい", inputStyle = com.zeroconvert.ime.azookey.input.InputStyle.DIRECT)

        val candidates = converter.requestCandidates(text, "", enableAI = false, personalize = true)
        assertFalse(candidates.any { it.isLearningPrediction })
    }

    @Test
    fun committedSegmentsDriveGboardStyleContinuation() = runBlocking {
        val segments = listOf(
            DictionarySegment("キョウ", "今日", 100, 101, 5, 0f),
            DictionarySegment("ハ", "は", 600, 601, 5, 0f),
            DictionarySegment("テンキガ", "天気が", 700, 701, 5, 0f),
            DictionarySegment("イイ", "いい", 562, 563, 5, 0f)
        )
        KanaKanjiConverter.recordSelectionForTest("きょうはてんきがいい", "今日は天気がいい", segments)

        val converter = KanaKanjiConverter()
        suspend fun predictionsFor(reading: String): List<Candidate> {
            val text = ComposingText()
            text.insertAtCursorPosition(reading, inputStyle = com.zeroconvert.ime.azookey.input.InputStyle.DIRECT)
            return converter.requestCandidates(text, "", enableAI = false, personalize = true)
                .filter { it.isLearningPrediction }
        }

        // は → は天気が（入力部分＋次の1分節）
        val fromHa = predictionsFor("は")
        assertTrue(fromHa.any { it.text == "は天気が" && it.reading == "ハテンキガ" })

        // てんきが → 天気がいい（続きの分節）
        assertTrue(predictionsFor("てんきが").any { it.text == "天気がいい" })

        // きょう → 先頭分節の予測「今日」と、続き込みの「今日は」の両方
        val fromKyo = predictionsFor("きょう")
        assertTrue(fromKyo.any { it.text == "今日" })
        assertTrue(fromKyo.any { it.text == "今日は" })

        // 辞書が「今日は」を1分節として扱う場合でも、今日の確定後の は で続きが出る
        KanaKanjiConverter.recordSelectionForTest("きょうはとてもいいてんきです", "今日はとてもいい天気です", listOf(
            DictionarySegment("キョウハ", "今日は", 100, 101, 5, 0f),
            DictionarySegment("トテモ", "とても", 700, 701, 5, 0f),
            DictionarySegment("イイ", "いい", 562, 563, 5, 0f),
            DictionarySegment("テンキデス", "天気です", 900, 901, 5, 0f)
        ))
        KanaKanjiConverter.recordSelectionForTest("きょう", "今日",
            listOf(DictionarySegment("キョウ", "今日", 100, 101, 5, 0f)))
        assertTrue(predictionsFor("は").any { it.text == "はとても" && it.reading == "ハトテモ" })
    }

    @Test
    fun postCommitContinuationsOfferTheNextChunkOnly() {
        KanaKanjiConverter.recordSelectionForTest("きょうはとてもいいてんきです", "今日はとてもいい天気です", listOf(
            DictionarySegment("キョウハ", "今日は", 100, 101, 5, 0f),
            DictionarySegment("トテモ", "とても", 700, 701, 5, 0f),
            DictionarySegment("イイ", "いい", 562, 563, 5, 0f),
            DictionarySegment("テンキデス", "天気です", 900, 901, 5, 0f)
        ))

        assertEquals(listOf("は"), KanaKanjiConverter.postCommitContinuations("今日").map { it.text })
        assertEquals(listOf("とても"), KanaKanjiConverter.postCommitContinuations("今日は").map { it.text })
        assertEquals(listOf("いい"), KanaKanjiConverter.postCommitContinuations("今日はとても").map { it.text })
        assertEquals(listOf("天気です"), KanaKanjiConverter.postCommitContinuations("今日はとてもいい").map { it.text })
        assertTrue(KanaKanjiConverter.postCommitContinuations("今日はとてもいい天気です").isEmpty())
        assertTrue(KanaKanjiConverter.postCommitContinuations("無関係").isEmpty())
        assertTrue(KanaKanjiConverter.postCommitContinuations("").isEmpty())
    }
}
