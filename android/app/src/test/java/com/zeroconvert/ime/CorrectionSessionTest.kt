package com.zeroconvert.ime

import android.content.SharedPreferences
import com.zeroconvert.ime.assist.AssistRequestGate
import com.zeroconvert.ime.assist.AutoTypoCorrectionStrength
import com.zeroconvert.ime.assist.CorrectionSession
import com.zeroconvert.ime.assist.InputPrivacy
import com.zeroconvert.ime.assist.RomajiDecoder
import com.zeroconvert.ime.assist.TypoLearningHistory
import com.zeroconvert.ime.azookey.input.ComposingText
import com.zeroconvert.ime.azookey.input.InputStyle
import org.junit.Assert.*
import org.junit.Test
import kotlinx.coroutines.runBlocking
import java.lang.reflect.Proxy

class CorrectionSessionTest {
    @Test fun correctionIsEditableAndUndoRestoresRawKeysAndConvertedPreview() {
        val input = ComposingText().apply { insertAtCursorPosition("otukuresama", InputStyle.ROMAN2KANA) }
        val original = input.clone()
        val correction = CorrectionSession(RustAssistPolicy)
        assertTrue(correction.apply(input, "お疲れ様", "おつくれ様"))
        assertEquals("お疲れ様", input.convertTarget)
        assertEquals("お疲れ様", ComposingText.getConvertTarget(input.input))
        assertTrue(correction.holdsAutoCommit)
        assertEquals("おつくれ様", correction.revert(input))
        assertEquals(original.input, input.input)
        assertEquals(original.convertTarget, input.convertTarget)
        assertEquals(original.convertTargetCursorPosition, input.convertTargetCursorPosition)
        assertEquals("お疲れ様", correction.rejectedSurface)
        assertTrue(correction.committed("お疲れ様").isEmpty())
    }

    @Test fun correctionBeforeCursorPreservesSuffixAndCursorOnUndo() {
        val input = ComposingText().apply { insertAtCursorPosition("おつくれさまです", InputStyle.DIRECT) }
        input.moveCursorFromCursorPosition(-2)
        val original = input.clone()
        val correction = CorrectionSession(RustAssistPolicy)
        assertTrue(correction.apply(input, "お疲れ様", "おつくれ様です"))
        assertEquals("お疲れ様です", input.convertTarget)
        assertEquals("お疲れ様", input.convertTargetBeforeCursor)
        correction.revert(input)
        assertEquals(original.convertTarget, input.convertTarget)
        assertEquals(original.input, input.input)
        assertEquals(original.convertTargetCursorPosition, input.convertTargetCursorPosition)
    }

    @Test fun typingAfterAdoptionKeepsCorrectionAndLearnsOnlyCommittedTextOnce() {
        val input = ComposingText().apply { insertAtCursorPosition("おつくれさま", InputStyle.DIRECT) }
        val correction = CorrectionSession(RustAssistPolicy)
        correction.apply(input, "お疲れ様", "おつくれ様")
        correction.edited()
        assertTrue(correction.holdsAutoCommit)
        input.insertAtCursorPosition("desu", InputStyle.ROMAN2KANA)
        assertEquals("お疲れ様です", input.convertTarget)
        assertEquals("お疲れ様", correction.fixedPrefix(input.convertTarget))
        assertFalse(correction.isApplied)
        assertTrue(correction.canRevert)
        correction.edited()
        assertFalse(correction.holdsAutoCommit)
        assertTrue(correction.committed("別の候補").isEmpty())
        assertEquals(listOf(CorrectionSession.Acceptance("おつくれさま", "お疲れ様")),
            correction.committed(input.convertTarget))
        assertTrue(correction.committed(input.convertTarget).isEmpty())
        correction.clear()
        assertFalse(correction.holdsAutoCommit)
    }

    @Test fun acceptedSurfaceIsNotReconvertedAndSuffixStaysOutsideCandidate() = runBlocking {
        val input = ComposingText().apply { insertAtCursorPosition("お疲れ様です", InputStyle.DIRECT) }
        input.moveCursorFromCursorPosition(-2)
        val candidates = KanaKanjiConverter().requestCandidates(input, "", fixedSurfacePrefix = "お疲れ様")
        assertEquals(listOf("お疲れ様"), candidates.map { it.text })
        assertEquals("お疲れ様です", input.convertTarget)
        assertEquals("お疲れ様", input.convertTargetBeforeCursor)
    }

    @Test fun newEditorDiscardsPendingAcceptanceAndUndo() {
        val input = ComposingText().apply { insertAtCursorPosition("がこう", InputStyle.DIRECT) }
        val correction = CorrectionSession(RustAssistPolicy)
        correction.apply(input, "学校", "がこう")
        correction.clear()
        assertNull(correction.revert(input))
        assertTrue(correction.committed("学校").isEmpty())
    }

    @Test fun sameReadingAfterDeletionCannotReuseAnOldResult() {
        val gate = AssistRequestGate()
        val key = AssistRequestGate.Key("おつくれさま", 6, "文脈", "", 1, true)
        val ticket = gate.capture(key)
        assertTrue(gate.isCurrent(ticket, key))
        gate.invalidate() // delete
        gate.invalidate() // retype the same input
        assertFalse(gate.isCurrent(ticket, key))
        val fresh = gate.capture(key)
        assertFalse(gate.isCurrent(fresh, key.copy(modelRevision = 2)))
        assertFalse(gate.isCurrent(fresh, key.copy(context = "変更された文脈")))
        assertFalse(gate.isCurrent(fresh, key.copy(assistEnabled = false)))
        assertFalse(gate.isCurrent(fresh, key.copy(cursor = 3)))
        assertFalse(gate.isCurrent(fresh, key.copy(profile = "別の設定")))
        assertFalse(gate.isCurrent(fresh, key.copy(inputType = 0x81)))
        assertFalse(gate.isCurrent(fresh, key.copy(touchCorrectionEnabled = false)))
        assertTrue(gate.isGenerationCurrent(fresh))
        gate.invalidate()
        assertFalse(gate.isGenerationCurrent(fresh))
    }

    @Test fun learningRequiresTheSameSurfaceAndEvictsLeastRecentlyAccepted() {
        val history = TypoLearningHistory(2)
        repeat(3) { history.record("がこう", "学校") }
        assertEquals(3, history.lookup("がこう")!!.count)
        history.record("がこう", "別表記")
        assertEquals(1, history.lookup("がこう")!!.count)
        history.record("けか", "結果")
        history.record("がこう", "別表記")
        history.record("かたん", "簡単")
        assertNull(history.lookup("けか"))
        assertEquals(2, history.lookup("がこう")!!.count)
    }

    @Test fun floatingRomajiActuallyConvertsBackToKana() {
        assertEquals("おかえり", RomajiDecoder.decodeFloatingConsonantTypo("おｋせり"))
        assertEquals("おつかれ", RomajiDecoder.decodeFloatingConsonantTypo("おｔかれ"))
        assertEquals("ありがとう", RomajiDecoder.decodeFloatingConsonantTypo("あｒがとう"))
        assertEquals("こんにちは", RomajiDecoder.decodeFloatingConsonantTypo("こｎにちは"))
        assertNull(RomajiDecoder.decodeFloatingConsonantTypo("a".repeat(129)))
    }

    @Test fun passwordAndNoLearningEditorsDoNotAllowAssistanceOrLearning() {
        assertTrue(InputPrivacy.isSensitive(0x81, 0)) // text password
        assertTrue(InputPrivacy.isSensitive(0x91, 0)) // visible password
        assertTrue(InputPrivacy.isSensitive(0xe1, 0)) // web password
        assertTrue(InputPrivacy.isSensitive(0x12, 0)) // numeric password
        assertTrue(InputPrivacy.isSensitive(1, 0x1000000)) // NO_PERSONALIZED_LEARNING
        assertTrue(InputPrivacy.isSensitive(0, 0)) // TYPE_NULL skips learning
        assertFalse(InputPrivacy.isSensitive(1, 0))
    }

    @Test fun nonInputTypeNullDoesNotTriggerPrivateModeOrBlockExternalClipboard() {
        assertFalse(InputPrivacy.isPrivateInput(0, 0)) // TYPE_NULL (outside input fields)
        assertTrue(InputPrivacy.isPrivateInput(0, 0x1000000)) // TYPE_NULL + NO_PERSONALIZED_LEARNING
        assertTrue(InputPrivacy.isPrivateInput(0x81, 0))
        assertTrue(InputPrivacy.isPrivateInput(0x91, 0))
        assertTrue(InputPrivacy.isPrivateInput(0xe1, 0))
        assertTrue(InputPrivacy.isPrivateInput(0x12, 0))
        assertFalse(InputPrivacy.isPrivateInput(1, 0))

        assertTrue(InputPrivacy.shouldAcceptClipboardEvent(privateMode = false, clipTimestamp = 5_000L, lastPrivateInputEndMillis = 0L))
        assertFalse(InputPrivacy.shouldAcceptClipboardEvent(privateMode = true, clipTimestamp = 5_000L, lastPrivateInputEndMillis = 0L))
        assertFalse(InputPrivacy.shouldAcceptClipboardEvent(privateMode = false, clipTimestamp = 5_000L, lastPrivateInputEndMillis = 5_000L))
        assertTrue(InputPrivacy.shouldAcceptClipboardEvent(privateMode = false, clipTimestamp = 5_001L, lastPrivateInputEndMillis = 5_000L))
        assertTrue(InputPrivacy.shouldAcceptClipboardEvent(privateMode = false, clipTimestamp = 0L, lastPrivateInputEndMillis = 5_000L))
    }

    @Test fun readingOnlyCorrectionDoesNotLockFixedPrefixAndCommitsConvertedSurface() = runBlocking {
        assertTrue(CorrectionSession.isReadingOnlyCorrection("おせわになっております"))
        assertTrue(CorrectionSession.isReadingOnlyCorrection("おかえり！"))
        assertFalse(CorrectionSession.isReadingOnlyCorrection(""))
        assertFalse(CorrectionSession.isReadingOnlyCorrection("お疲れ様"))
        assertFalse(CorrectionSession.isReadingOnlyCorrection("カタカナ"))
        assertFalse(CorrectionSession.isReadingOnlyCorrection("hello"))

        val input = ComposingText().apply { insertAtCursorPosition("おせわになております", InputStyle.DIRECT) }
        val correction = CorrectionSession(RustAssistPolicy)
        assertTrue(correction.apply(input, "おせわになっております", "お世話になております", explicit = true))
        val protectedPrefix = correction.fixedPrefix(input.convertTarget)
        assertEquals("おせわになっております", protectedPrefix)
        assertEquals("", CorrectionSession.fixedSurfacePrefixForConversion(protectedPrefix))

        // Appending more text still avoids locking the repaired reading so full-sentence conversion can run.
        correction.edited()
        input.insertAtCursorPosition("desu", InputStyle.ROMAN2KANA)
        val extendedProtected = correction.fixedPrefix(input.convertTarget)
        assertEquals("おせわになっております", extendedProtected)
        assertEquals("", CorrectionSession.fixedSurfacePrefixForConversion(extendedProtected))

        // Non-reading surface corrections still preserve their fixed surface prefix.
        assertEquals("学校", CorrectionSession.fixedSurfacePrefixForConversion("学校"))

        // Committing the converted kanji surface still records the accepted reading repair.
        val matchTarget = CorrectionSession.committedMatchTarget("お世話になっておりますです", "おせわになっておりますです")
        assertEquals(
            listOf(CorrectionSession.Acceptance("おせわになております", "おせわになっております")),
            correction.committed(matchTarget)
        )
        assertEquals("", correction.fixedPrefix("お世話になっておりますです"))
    }

    @Test fun explicitRevertAfterFurtherTypingPreservesTrailingInputAndRejectsReapplication() {
        val input = ComposingText().apply { insertAtCursorPosition("kyooha", InputStyle.ROMAN2KANA) }
        assertEquals("きょおは", input.convertTarget)
        val correction = CorrectionSession(RustAssistPolicy)
        assertTrue(correction.apply(input, "きょうは", "きょおは", explicit = false))
        assertTrue(correction.isApplied)
        assertTrue(correction.canRevert)

        // Next keystrokes drop Backspace-immediate isApplied while retaining differential revert.
        correction.edited()
        assertFalse(correction.isApplied)
        assertTrue(correction.canRevert)
        input.insertAtCursorPosition("iitenki", InputStyle.ROMAN2KANA)
        assertEquals("きょうはいいてんき", input.convertTarget)
        assertTrue(correction.reconcileWithInput(input))
        assertEquals("きょおはいいてんき", correction.previewRevertedTarget(input))

        // Explicit revert restores only the corrected prefix and leaves trailing text intact.
        assertEquals("きょおはいいてんき", correction.revert(input))
        assertEquals("きょおはいいてんき", input.convertTarget)
        assertFalse(correction.isApplied)
        assertFalse(correction.canRevert)
        assertTrue(correction.isRejected("きょおはいいてんき", "きょうはいいてんき"))
        assertFalse(correction.isRejected("きょおはいいてんき", "きょおはいいてんきです"))
    }

    @Test fun deletingIntoCorrectedPrefixOrAutoCommitClearsDeferredUndo() {
        val input = ComposingText().apply { insertAtCursorPosition("おつくれさま", InputStyle.DIRECT) }
        val correction = CorrectionSession(RustAssistPolicy)
        assertTrue(correction.apply(input, "おつかれさま", "おつくれさま", explicit = false))
        correction.edited()
        input.insertAtCursorPosition("です", InputStyle.DIRECT)
        assertTrue(correction.reconcileWithInput(input))

        // Deleting within the trailing part keeps differential undo available.
        input.deleteBackwardFromCursorPosition(1)
        assertEquals("おつかれさまで", input.convertTarget)
        assertTrue(correction.reconcileWithInput(input))
        assertTrue(correction.canRevert)

        // Deleting into the corrected prefix discards deferred undo.
        input.deleteBackwardFromCursorPosition(2)
        assertEquals("おつかれさ", input.convertTarget)
        assertFalse(correction.reconcileWithInput(input))
        assertFalse(correction.canRevert)

        // Auto-commit / prefixComplete also clears deferred undo.
        val longInput = ComposingText().apply { insertAtCursorPosition("きょおは", InputStyle.DIRECT) }
        assertTrue(correction.apply(longInput, "きょうは", "きょおは", explicit = false))
        correction.edited()
        longInput.insertAtCursorPosition("いいてんき", InputStyle.DIRECT)
        assertTrue(correction.reconcileWithInput(longInput))
        longInput.prefixComplete(4)
        assertEquals("いいてんき", longInput.convertTarget)
        assertFalse(correction.reconcileWithInput(longInput))
        assertFalse(correction.canRevert)
    }

    @Test fun autoTypoCorrectionStrengthReadsLegacyBoolAndNewStringKey() {
        fun stubPrefs(values: Map<String, Any>): SharedPreferences =
            Proxy.newProxyInstance(
                SharedPreferences::class.java.classLoader,
                arrayOf(SharedPreferences::class.java)
            ) { _, method, args ->
                when (method.name) {
                    "contains" -> values.containsKey(args!![0] as String)
                    "getBoolean" -> values[args!![0] as String] as? Boolean ?: (args[1] as Boolean)
                    "getString" -> values[args!![0] as String] as? String ?: (args[1] as? String)
                    else -> error("Unexpected SharedPreferences call: ${method.name}")
                }
            } as SharedPreferences

        assertEquals(
            AutoTypoCorrectionStrength.STANDARD,
            AutoTypoCorrectionStrength.readFrom(stubPrefs(emptyMap()), "strength", "legacy")
        )
        assertEquals(
            AutoTypoCorrectionStrength.OFF,
            AutoTypoCorrectionStrength.readFrom(stubPrefs(mapOf("legacy" to false)), "strength", "legacy")
        )
        assertEquals(
            AutoTypoCorrectionStrength.STANDARD,
            AutoTypoCorrectionStrength.readFrom(stubPrefs(mapOf("legacy" to true)), "strength", "legacy")
        )
        assertEquals(
            AutoTypoCorrectionStrength.CONSERVATIVE,
            AutoTypoCorrectionStrength.readFrom(
                stubPrefs(mapOf("legacy" to false, "strength" to "conservative")),
                "strength",
                "legacy"
            )
        )
        assertEquals(
            AutoTypoCorrectionStrength.AGGRESSIVE,
            AutoTypoCorrectionStrength.readFrom(
                stubPrefs(mapOf("strength" to "plan-a")),
                "strength",
                "legacy"
            )
        )
    }

    @Test fun multiStepAutoRefinementPreservesTrueUnalteredOriginAndTouchBiases() {
        val input = ComposingText().apply { insertAtCursorPosition("shute", InputStyle.ROMAN2KANA) }
        val initialBiases = listOf(
            TouchKeyBias('s'),
            TouchKeyBias('h'),
            TouchKeyBias('u'),
            TouchKeyBias('t'),
            TouchKeyBias('e', listOf('i' to 0.8f)),
        )
        val session = CorrectionSession(RustAssistPolicy)
        assertTrue(session.apply(input, "しゅうて", "しゅて", explicit = false, currentBiases = initialBiases))
        assertTrue(session.isApplied)
        assertFalse(session.isRefined)
        assertTrue(session.isImmediateBackspaceUndo)

        // User continues typing after auto-apply.
        session.edited()
        input.insertAtCursorPosition("nshima", InputStyle.ROMAN2KANA)
        assertEquals("しゅうてんしま", input.convertTarget)
        assertFalse(session.isImmediateBackspaceUndo)

        val refinedBiases = List(4) { i ->
            TouchKeyBias((input.input[i].piece as com.zeroconvert.ime.azookey.input.InputPiece.CharPiece).char)
        } + listOf(
            TouchKeyBias('n'),
            TouchKeyBias('s'),
            TouchKeyBias('h'),
            TouchKeyBias('i'),
            TouchKeyBias('m'),
            TouchKeyBias('a', listOf('s' to 0.7f)),
        )
        val ctx1 = session.buildAutoRefinementContext(input, refinedBiases)!!
        assertEquals("しゅてんしま", ctx1.originReading)
        assertEquals("しゅうて", ctx1.appliedPrefix)
        assertEquals("shutenshima", com.zeroconvert.ime.engine.engineRawRomaji(ctx1.originInput))
        assertEquals("4:i=0.80;10:s=0.70", com.zeroconvert.ime.engine.engineTouchHints(ctx1.originInput, ctx1.originBiases))

        // First refinement: Backspace stays normal delete, 「戻す」 reverts to true unaltered origin.
        assertTrue(session.apply(input, "しゅていしま", "しゅうてんしま", explicit = false, currentBiases = refinedBiases))
        assertTrue(session.isApplied)
        assertTrue(session.isRefined)
        assertFalse(session.isImmediateBackspaceUndo)
        assertTrue(session.canRevert)

        // Second keystroke + second refinement still keeps true unaltered origin ("shute...").
        session.edited()
        input.insertAtCursorPosition("su", InputStyle.ROMAN2KANA)
        val afterFirstRefineBiases = input.input.mapIndexed { idx, el ->
            val ch = (el.piece as com.zeroconvert.ime.azookey.input.InputPiece.CharPiece).char
            if (idx == input.input.lastIndex) TouchKeyBias(ch, listOf('a' to 0.5f)) else TouchKeyBias(ch)
        }
        val ctx2 = session.buildAutoRefinementContext(input, afterFirstRefineBiases)!!
        assertEquals("しゅてんします", ctx2.originReading)
        assertEquals("shutenshimasu", com.zeroconvert.ime.engine.engineRawRomaji(ctx2.originInput))
        assertEquals("4:i=0.80;10:s=0.70;12:a=0.50", com.zeroconvert.ime.engine.engineTouchHints(ctx2.originInput, ctx2.originBiases))

        assertTrue(session.apply(input, "しゅってんします", "しゅていします", explicit = false, currentBiases = afterFirstRefineBiases))
        assertEquals("しゅてんします", session.revert(input))
        assertEquals("しゅてんします", input.convertTarget)
        assertFalse(session.canRevert)
    }

    @Test fun explicitAdoptionNeverRefinesAndSecondTypoPreservesFirstCorrection() {
        val input = ComposingText().apply { insertAtCursorPosition("しゅて", InputStyle.DIRECT) }
        val session = CorrectionSession(RustAssistPolicy)
        assertTrue(session.apply(input, "しゅうて", "しゅて", explicit = true))
        session.edited()
        input.insertAtCursorPosition("んします", InputStyle.DIRECT)
        assertNull(session.buildAutoRefinementContext(input))

        // Auto-apply #1 ("きょうは"), then trailing 2nd typo ("きょうはあした") preserves "きょうは".
        val secondInput = ComposingText().apply { insertAtCursorPosition("きょおは", InputStyle.DIRECT) }
        val autoSession = CorrectionSession(RustAssistPolicy)
        assertTrue(autoSession.apply(secondInput, "きょうは", "きょおは", explicit = false))
        autoSession.edited()
        secondInput.insertAtCursorPosition("あしだ", InputStyle.DIRECT)
        val ctx = autoSession.buildAutoRefinementContext(secondInput)!!
        assertEquals("きょおはあしだ", ctx.originReading)
        assertTrue(autoSession.apply(secondInput, "きょうはあした", "きょうはあしだ", explicit = false))
        assertFalse(autoSession.isRefined)
        assertEquals("きょうはあしだ", autoSession.revert(secondInput))
    }

    @Test fun autoRevertRestoresTrueOriginClearsRevertStateAndBlocksImmediateBoomerang() {
        val input = ComposingText().apply { insertAtCursorPosition("shute", InputStyle.ROMAN2KANA) }
        val session = CorrectionSession(RustAssistPolicy)
        assertTrue(session.apply(input, "しゅうて", "しゅて", explicit = false))
        session.edited()
        input.insertAtCursorPosition("ndouji", InputStyle.ROMAN2KANA)

        val reverted = session.autoRevert(input)!!
        assertEquals("しゅてんどうじ", reverted.first)
        assertEquals("しゅてんどうじ", input.convertTarget)
        assertFalse(session.canRevert)
        assertFalse(session.holdsAutoCommit)
        assertTrue(session.isRejected("しゅてんどうじ", "しゅうてんどうじ"))
        session.edited()
        assertFalse(session.isRejected("しゅてんどうじ", "しゅうてんどうじ"))
    }
}
