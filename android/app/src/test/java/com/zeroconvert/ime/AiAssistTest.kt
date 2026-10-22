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
    fun testPredictiveAssistEngine() {
        // 1. Business greeting prefix
        val p1 = PredictiveAssistEngine.findPrediction("いつもお世話に", "いつもおせわに", "")
        assertNotNull(p1)
        assertEquals("いつもお世話になっております。", p1)

        // 2. Business request
        val p2 = PredictiveAssistEngine.findPrediction("よろしくお願い", "よろしくおねがい", "")
        assertNotNull(p2)
        assertEquals("よろしくお願いいたします。", p2)

        // 3. Status reporting
        val p3 = PredictiveAssistEngine.findPrediction("進捗状況をご報告", "しんちょくじょうきょうをごほうこく", "")
        assertNotNull(p3)
        assertEquals("進捗状況をご報告いたします。", p3)

        // 4. Tech / engineering
        val p4 = PredictiveAssistEngine.findPrediction("プルリクエストを", "ぷるりくえすとを", "")
        assertNotNull(p4)
        assertEquals("プルリクエストを作成しました。", p4)
    }

    @Test
    fun testAiAssistManagerPriority() = runBlocking {
        // Typo correction must have priority over prediction when typo is detected
        val assistTypo = AiAssistManager.requestAiAssist(
            rawInput = "おつくれさま",
            hiragana = "おつくれさま",
            firstSurface = "おつくれ様",
            leftContext = ""
        )
        assertNotNull(assistTypo)
        assertEquals(AiAssistType.TYPO_CORRECTION, assistTypo?.type)
        assertEquals("お疲れ様", assistTypo?.text)
        assertTrue(assistTypo?.label?.startsWith("💡") == true)

        // Clean input without typo gets predictive continuation
        val assistPred = AiAssistManager.requestAiAssist(
            rawInput = "いつもお世話に",
            hiragana = "いつもおせわに",
            firstSurface = "いつもお世話に",
            leftContext = ""
        )
        assertNotNull(assistPred)
        assertEquals(AiAssistType.PREDICTION, assistPred?.type)
        assertEquals("いつもお世話になっております。", assistPred?.text)
        assertTrue(assistPred?.label?.startsWith("🔮") == true)
    }
}
