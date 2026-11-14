package com.zeroconvert.ime

import com.zeroconvert.ime.assist.AssistRequestGate
import com.zeroconvert.ime.assist.CorrectionSession
import com.zeroconvert.ime.assist.InputPrivacy
import com.zeroconvert.ime.assist.RomajiDecoder
import com.zeroconvert.ime.assist.TypoLearningHistory
import com.zeroconvert.ime.azookey.input.ComposingText
import com.zeroconvert.ime.azookey.input.InputStyle
import org.junit.Assert.*
import org.junit.Test
import kotlinx.coroutines.runBlocking

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
        assertNull(correction.revert(input))
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
        assertFalse(InputPrivacy.isSensitive(1, 0))
    }
}
