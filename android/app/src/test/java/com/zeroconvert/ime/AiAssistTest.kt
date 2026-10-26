package com.zeroconvert.ime

import com.zeroconvert.ime.assist.AiAssistManager
import com.zeroconvert.ime.assist.AiAssistType
import com.zeroconvert.ime.assist.PredictiveAssistEngine
import com.zeroconvert.ime.assist.TypoCorrectionEngine
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class AiAssistTest {

    @Test
    fun cleanKnownReadingsNeverTurnIntoNeighbouringVocabulary() {
        for (reading in TypoCorrectionEngine.STANDARD_VOCAB.keys) {
            assertNull("Clean reading must remain unchanged: $reading", TypoCorrectionEngine.findCorrection(reading))
        }
    }

    @Test
    fun testDamerauLevenshteinDistance() {
        assertEquals(0, TypoCorrectionEngine.damerauLevenshtein("こんにちは", "こんにちは"))
        assertEquals(1, TypoCorrectionEngine.damerauLevenshtein("きんにちは", "こんにちは")) // substitution
        assertEquals(1, TypoCorrectionEngine.damerauLevenshtein("てそと", "てすと")) // transposition
        assertEquals(1, TypoCorrectionEngine.damerauLevenshtein("けか", "けっか")) // insertion
        assertEquals(1, TypoCorrectionEngine.damerauLevenshtein("かたん", "かんたん")) // insertion
    }

    @Test
    fun testTypoCorrectionRules() {
        // 1. Adjacent key (きんにちは -> こんにちは)
        assertEquals("こんにちは", TypoCorrectionEngine.findCorrection("きんにちは"))

        // 2. Missing vowel (おつくれさま -> お疲れ様)
        assertEquals("お疲れ様", TypoCorrectionEngine.findCorrection("おつくれさま"))

        // 3. Double consonant / sokuon (けか -> 結果)
        assertEquals("結果", TypoCorrectionEngine.findCorrection("けか"))

        // 4. Missing sokuon (がこう -> 学校)
        assertEquals("学校", TypoCorrectionEngine.findCorrection("がこう"))

        // 5. Syllabic nasal / hatsuon (かたん -> 簡単)
        assertEquals("簡単", TypoCorrectionEngine.findCorrection("かたん"))

        // 6. Transposition (てそと -> テスト)
        assertEquals("テスト", TypoCorrectionEngine.findCorrection("てそと"))

        // 7. Prolonged sound mark (さびす -> サービス)
        assertEquals("サービス", TypoCorrectionEngine.findCorrection("さびす"))

        // 8. QWERTY neighbor (kuraudo / くらおど -> クラウド)
        assertEquals("クラウド", TypoCorrectionEngine.findCorrection("くらおど"))
    }

    @Test
    fun modelUnavailableDoesNotReturnCannedPhrases() {
        assertNull(PredictiveAssistEngine.findPrediction("いつもお世話に", "いつもおせわに", ""))
        assertNull(PredictiveAssistEngine.findPrediction("よろしくお願い", "よろしくおねがい", ""))
    }

    @Test
    fun offlineCorrectionStillWorksWithoutPredictionModel() = runBlocking {
        val assist = AiAssistManager.requestAiAssist("おつくれさま", "おつくれさま", "おつくれ様", "")
        assertEquals(AiAssistType.TYPO_CORRECTION, assist?.type)
        assertEquals("お疲れ様", assist?.text)
    }

    /**
     * The typo correction result must be stable across repeated invocations.
     *
     * The candidate readings are generated into a Set, so an unsorted iteration
     * order would make the winning correction depend on hash ordering. The
     * engine sorts the candidates before testing them, which must keep the
     * outcome deterministic.
     */
    @Test
    fun testTypoCorrectionIsDeterministic() {
        val inputs = listOf(
            "きんにちは",
            "おつくれさま",
            "けか",
            "がこう",
            "かたん",
            "てそと",
            "さびす",
            "くらおど",
            "じかんわ",
            "かさおさす"
        )
        for (input in inputs) {
            val first = TypoCorrectionEngine.findCorrection(input)
            repeat(20) {
                assertEquals(
                    "Correction for '$input' must be deterministic",
                    first,
                    TypoCorrectionEngine.findCorrection(input)
                )
            }
        }
    }

    /**
     * Readings that cannot possibly exist in the LOUDS dictionary (because they
     * contain characters outside the kana set) must still be corrected through
     * the vocabulary based path.
     *
     * This guards the dictionary-lookup fast path: filtering out non-kana
     * candidates may not change the outcome of a correction.
     */
    @Test
    fun testTypoCorrectionIgnoresNonKanaCandidatesOnly() {
        // Romanised input must still be corrected via the generated kana candidates.
        assertEquals("こんにちは", TypoCorrectionEngine.findCorrection("kinnnichiwa"))
        // Pure kana input must still be corrected.
        assertEquals("お疲れ様", TypoCorrectionEngine.findCorrection("おつくれさま"))
        // A reading with no correction available must return null (no false positive).
        assertNull(TypoCorrectionEngine.findCorrection("ぞぎぞぎ"))
    }
}
