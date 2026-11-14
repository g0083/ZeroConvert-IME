package com.zeroconvert.ime

import com.zeroconvert.ime.assist.*
import com.zeroconvert.ime.azookey.input.ComposingText
import com.zeroconvert.ime.azookey.input.InputStyle
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

class AutomaticAssistTest {
    @Test fun unifiedCorrectionAndPredictionUseIndependentSharedSettings() {
        for (correction in listOf(false, true)) for (prediction in listOf(false, true)) {
            val uncorrected = SharedAssistPolicy.requestPlan(correction, prediction, false,
                "しますた", policy = RustAssistPolicy)
            assertEquals(correction, uncorrected.correction)
            assertEquals(prediction, uncorrected.prediction)
            val corrected = SharedAssistPolicy.requestPlan(correction, prediction, false,
                "しますた", "しました", RustAssistPolicy)
            assertFalse(corrected.correction)
            assertEquals(prediction, corrected.prediction)
            val applied = SharedAssistPolicy.requestPlan(correction, prediction, true,
                "しますた", policy = RustAssistPolicy)
            assertFalse(applied.correction)
            assertEquals(prediction, applied.prediction)
        }
    }

    @Test fun disabledFeaturesNeverStartModelOrNativeAssistance() = runBlocking {
        val inference = object : AssistInference {
            override val revision = 1L
            override fun complete(model: AssistModel, prompt: String, maxTokens: Int, timeoutMs: Int): ModelCompletion =
                error("disabled assistance must not infer")
        }
        AiAssistManager.streamAiAssist("しますた", "しますた", "", false, inference,
            policy = RustAssistPolicy, allowPrediction = false) { error("disabled assistance must not publish") }
        assertNull(AiAssistManager.requestAiAssist("shimasuta", "しますた", "しますた", "",
            allowCorrection = false, inference = inference, allowPrediction = false, policy = RustAssistPolicy))
    }

    private fun correction(auto: Boolean = false, relevance: Int = if (auto) 100 else 30) =
        AiAssistResult(AiAssistType.TYPO_CORRECTION, "お疲れ様", "補正", relevance, "おつくれさま", autoApply = auto)
    private fun prediction() = AiAssistResult(AiAssistType.PREDICTION, "おつくれ様でした。", "先読み", 60)

    @Test fun threeExplicitCommitsMustMatchBothInputAndSurface() {
        assertTrue(AutoCorrectionEvidence.permits("がこう", "学校", LearnedMatch("がこう", "学校", 3)) { null })
        assertFalse(AutoCorrectionEvidence.permits("がこう", "学校", LearnedMatch("がこう", "学校", 2)) { null })
        assertFalse(AutoCorrectionEvidence.permits("がこう", "学校", LearnedMatch("がこう", "別表記", 30)) { null })
        assertFalse(AutoCorrectionEvidence.permits("がこう", "学校", LearnedMatch("別の読み", "学校", 30)) { null })
        assertFalse(AutoCorrectionEvidence.permits("がこう", "学校", null) { null })
        assertFalse(AutoCorrectionEvidence.permits("がこう", "学校", LearnedMatch("がこう", "学校", 30, 0)) { null })
        assertFalse(AutoCorrectionEvidence.permits("がこう", "学\n校", LearnedMatch("がこう", "学\n校", 3)) { null })
    }

    @Test fun legacySuggestionsNeedThreeNewConfirmedCommitsBeforeAutomaticApplication() {
        val history = TypoLearningHistory()
        history.restore(LearnedMatch("がこう", "学校", 30, 0))
        repeat(2) { history.record("がこう", "学校") }
        assertFalse(AutoCorrectionEvidence.permits("がこう", "学校", history.lookup("がこう")) { null })
        history.record("がこう", "学校")
        assertEquals(33, history.lookup("がこう")!!.count)
        assertEquals(3, history.lookup("がこう")!!.confirmedCount)
        assertTrue(AutoCorrectionEvidence.permits("がこう", "学校", history.lookup("がこう")) { null })
        history.record("がこう", "別表記")
        assertEquals(1, history.lookup("がこう")!!.confirmedCount)
    }

    @Test fun adjacentRepairMustBeUniqueAndCannotBeAnIdentifierOrUnfinishedConsonant() {
        val one = setOf("ああい")
        val ambiguous = setOf("ああい", "あえい")
        assertEquals("ああい", RomajiDecoder.uniqueAdjacentCorrection("あｓい", one::contains))
        assertNull(RomajiDecoder.uniqueAdjacentCorrection("あｓい", ambiguous::contains))
        assertNull(RomajiDecoder.uniqueAdjacentCorrection("identifier", one::contains))
        assertNull(RomajiDecoder.uniqueAdjacentCorrection("あｋ", one::contains))
        assertTrue(AutoCorrectionEvidence.permits("おｋせり", "おかえり", null) { "おかえり" })
        assertFalse(AutoCorrectionEvidence.permits("おｋせり", "別の表記", null) { "おかえり" })
    }

    @Test fun automaticCorrectionStaysEditableAndNeverTrainsItself() {
        val input = ComposingText().apply { insertAtCursorPosition("gakou", InputStyle.ROMAN2KANA) }
        val original = input.clone()
        val session = CorrectionSession(RustAssistPolicy)
        assertTrue(session.apply(input, "学校", "がこう", explicit = false))
        assertEquals("学校", session.fixedPrefix(input.convertTarget))
        assertTrue(session.acceptancesForCommit("学校").isEmpty())
        assertEquals("がこう", session.revert(input))
        assertEquals(original.input, input.input)
        assertEquals("学校", session.rejectedSurface)
        session.edited()
        assertTrue(session.apply(input, "学校", "がこう", explicit = false))
        session.edited()
        input.insertAtCursorPosition("he", InputStyle.ROMAN2KANA)
        assertEquals("学校へ", input.convertTarget)
        assertEquals("学校", session.fixedPrefix(input.convertTarget))
        assertTrue(session.committed("学校へ").isEmpty())
        assertEquals("", session.fixedPrefix("学校へ"))
    }

    @Test fun undoingAutomaticCorrectionKeepsEarlierExplicitAcceptance() {
        val input = ComposingText().apply { insertAtCursorPosition("がこう", InputStyle.DIRECT) }
        val session = CorrectionSession(RustAssistPolicy)
        session.apply(input, "学校", "がこう")
        session.edited()
        input.insertAtCursorPosition("にいきます", InputStyle.DIRECT)
        session.apply(input, "学校へ行きます", "学校に行きます", explicit = false)
        session.revert(input)
        assertEquals(listOf(CorrectionSession.Acceptance("がこう", "学校")), session.committed("学校に行きます"))
    }

    @Test fun weakCorrectionWaitsForPredictionAndDoesNotReplaceDisplayedTarget() {
        val state = AssistPresentation(RustAssistPolicy)
        assertNull(state.complete(AiAssistType.TYPO_CORRECTION, correction()).selected)
        val shown = state.complete(AiAssistType.PREDICTION, prediction())
        assertEquals(prediction(), shown.selected)
        assertEquals(2, shown.suggestions.size)
        assertEquals(prediction(), state.complete(AiAssistType.TYPO_CORRECTION, correction(relevance = 70)).selected)
    }

    @Test fun absentPredictionReleasesManualCorrectionAndAutomaticEvidenceDoesNotWait() {
        val manual = AssistPresentation(RustAssistPolicy)
        manual.complete(AiAssistType.TYPO_CORRECTION, correction())
        assertEquals(correction(), manual.complete(AiAssistType.PREDICTION, null).selected)
        val automatic = AssistPresentation(RustAssistPolicy)
        val first = automatic.complete(AiAssistType.TYPO_CORRECTION, correction(auto = true))
        assertNotNull(first.automaticCorrection)
        assertEquals(correction(auto = true), first.selected)
        assertNull(automatic.complete(AiAssistType.PREDICTION, prediction()).automaticCorrection)
    }

    @Test fun learnedCorrectionWinsWhenAvailableBeforeTheFirstPresentation() {
        val state = AssistPresentation(RustAssistPolicy)
        val learned = correction(relevance = 70)
        state.complete(AiAssistType.TYPO_CORRECTION, learned)
        assertEquals(learned, state.complete(AiAssistType.PREDICTION, prediction()).selected)
    }

    @Test fun firstPredictionWaitsForCorrectionAndKeepsItsTapTargetAfterDeadline() {
        val state = AssistPresentation(RustAssistPolicy)
        assertNull(state.complete(AiAssistType.PREDICTION, prediction(), elapsedMs = 10).selected)
        assertNull(state.update(199).selected)
        assertEquals(prediction(), state.update(200).selected)
        val late = state.complete(AiAssistType.TYPO_CORRECTION, correction(relevance = 80), elapsedMs = 250)
        assertEquals(prediction(), late.selected)
        assertEquals(2, late.suggestions.size)
        assertEquals("ignore", SharedAssistPolicy.adoptionAction(prediction(), false, true, false, RustAssistPolicy))
        assertEquals("commitText", SharedAssistPolicy.adoptionAction(prediction(), true, false, true, RustAssistPolicy))
        assertEquals("replaceComposition", SharedAssistPolicy.adoptionAction(correction(), true, false, false, RustAssistPolicy))
        assertEquals("ignore", SharedAssistPolicy.adoptionAction(correction(), true, false, true, RustAssistPolicy))
    }

    @Test fun fastPredictionIsPublishedWhileCorrectorIsStillWorking() = runBlocking {
        val release = CountDownLatch(1)
        val inference = object : AssistInference {
            override val revision = 1L
            override fun complete(model: AssistModel, prompt: String, maxTokens: Int, timeoutMs: Int): ModelCompletion {
                if (model == AssistModel.CORRECTOR) {
                    assertTrue(release.await(5, TimeUnit.SECONDS))
                    return ModelCompletion("お疲れ様", "eos")
                }
                return if (prompt.endsWith('\uEE01')) ModelCompletion("おつくれ様でした。", "eos") else ModelCompletion("デシタ。", "eos")
            }
        }
        val updates = mutableListOf<AssistUpdate>()
        try {
            AiAssistManager.streamAiAssist("おつくれさま", "おつくれ様", "", true, inference) { update ->
                updates.add(update)
                if (update.selected?.type == AiAssistType.PREDICTION) release.countDown()
            }
        } finally { release.countDown() }
        assertEquals(2, updates.size)
        assertTrue(updates.all { it.selected?.type == AiAssistType.PREDICTION })
        assertEquals(2, updates.last().suggestions.size)
    }

    @Test fun correctionInsertionPreservesNormalOrderAndIdentityEvenWithSameSurface() {
        val first = Candidate("通常先頭", "ヨミ", isAI = true)
        val second = Candidate("お疲れ様", "ヨミ")
        val third = Candidate("別候補", "ヨミ")
        val normal = listOf(first, second, third)
        val merged = ConversionCandidateMerge.withCorrection(normal, correction())
        assertSame(first, merged[0])
        assertSame(second, merged[2])
        assertSame(third, merged[3])
        assertNotEquals(second.key, merged[1].key)
        assertEquals(normal.map { it.key }, merged.filter { it.assist == null }.map { it.key })
        assertEquals(4, ConversionCandidateMerge.withCorrection(merged, correction()).size)
        assertEquals(normal, ConversionCandidateMerge.withCorrection(merged, null))
    }

    @Test fun normalConversionKeepsLegitimateRepetitionAndLongerSurface() {
        assertTrue(ConversionCandidateMerge.validSurface("人人", "ひとびと"))
        assertTrue(ConversionCandidateMerge.validSurface("コンピューター", "ぴーしー"))
        assertFalse(ConversionCandidateMerge.validSurface("途中\uEE01", "とちゅう"))
        assertFalse(ConversionCandidateMerge.validSurface("暴走".repeat(100), "ぼうそう"))
        val ai = Candidate("人人", "ヒトビト", isAI = true)
        val list = mutableListOf(ai)
        ConversionCandidateMerge.appendDistinct(list, listOf(Candidate("人人", "ヒトビト"), Candidate("人々", "ヒトビト")))
        assertEquals(listOf("人人", "人々"), list.map { it.text })
        assertSame(ai, list.first())
    }

    @Test fun reconversionKeepsOriginalFirstAndRequestsOnlyTheRestoredReading() = runBlocking {
        var requested = ""
        val plan = ReconversionPlan.create("今日", restore = { "きょう" }) { input ->
            requested = input.convertTarget
            listOf(Candidate("今日", "キョウ"), Candidate("京", "キョウ"))
        }!!
        assertEquals("きょう", requested)
        assertEquals("今日", plan.original)
        assertEquals(listOf("今日", "京"), plan.candidates.map { it.text })
    }

    @Test fun unknownOrUnsafeReconversionLeavesSelectionUntouched() = runBlocking {
        assertNull(ReconversionPlan.create("未知", restore = { null }) { error("must not convert") })
        assertNull(ReconversionPlan.create("改\n行", restore = { error("must not restore") }) { error("must not convert") })
    }
}
