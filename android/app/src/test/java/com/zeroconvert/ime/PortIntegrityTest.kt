package com.zeroconvert.ime

import com.zeroconvert.ime.azookey.input.ComposingText
import com.zeroconvert.ime.azookey.input.InputStyle
import com.zeroconvert.ime.model.VerifiedModelFile
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.security.MessageDigest

class PortIntegrityTest {
    @get:Rule val directory = TemporaryFolder()
    private fun checksum(bytes: ByteArray) = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }

    @Test fun sameSizeModelChangesAreDetectedAndVerifiedBeforeReplacement() {
        val file = directory.newFile("model.gguf").apply { writeText("old!") }
        val updated = "new!".toByteArray()
        VerifiedModelFile.ensure(file, 4, checksum(updated)) { updated.inputStream() }
        assertArrayEquals(updated, file.readBytes())
        VerifiedModelFile.ensure(file, 4, checksum(updated)) { error("Verified model must not be extracted again") }
    }

    @Test fun failedAssetVerificationKeepsExistingModelBytes() {
        val file = directory.newFile("model.gguf").apply { writeText("old!") }
        try {
            VerifiedModelFile.ensure(file, 4, checksum("new!".toByteArray())) { "bad!".byteInputStream() }
            fail("Checksum must be checked before replacing")
        } catch (_: IllegalStateException) { }
        assertEquals("old!", file.readText())
        assertEquals(listOf(file.name), directory.root.listFiles()!!.map { it.name })
    }

    @Test fun automaticBoundariesNeverFreezeHalfOfARomajiUnit() {
        val input = ComposingText().apply { insertAtCursorPosition("kyakuni", InputStyle.ROMAN2KANA) }
        val before = input.input.map { it.copy() }
        assertFalse(input.isIndependentInputBoundary(1))
        assertTrue(input.isIndependentInputBoundary(2))
        assertTrue(input.isIndependentInputBoundary(3))
        assertEquals(before, input.input)
        val emoji = ComposingText().apply { insertAtCursorPosition("あ😀い", InputStyle.DIRECT) }
        assertFalse(emoji.isIndependentInputBoundary(2))
    }

    @Test fun inferenceSnapshotsReplaceMatchingRawLengthsAndRemainBounded() {
        val history = mutableListOf<InferenceSnapshot>()
        ZeroConvertInputMethodService.recordInferenceSnapshot(history, InferenceSnapshot("きょ", "巨", 3))
        ZeroConvertInputMethodService.recordInferenceSnapshot(history, InferenceSnapshot("きょう", "今日", 3))
        assertEquals(listOf(InferenceSnapshot("きょう", "今日", 3)), history)
        for (n in 4..180) ZeroConvertInputMethodService.recordInferenceSnapshot(history, InferenceSnapshot("あ".repeat(n), "亜".repeat(n), n))
        assertEquals(128, history.size)
    }

    private class Editor(prefix: String, tail: String) : AutoCommitRewind.Editor {
        var committed = prefix
        var composing = tail
        var failDelete = false
        val calls = mutableListOf<String>()
        override fun beforeCursor(length: Int) = (committed + composing).takeLast(length)
        override fun composing(text: String): Boolean { calls.add("compose:$text"); composing = text; return true }
        override fun deleteBefore(length: Int): Boolean {
            calls.add("delete:$length")
            if (failDelete) return false
            committed = committed.dropLast(length)
            return true
        }
        override fun commit(text: String): Boolean { committed += text; composing = ""; return true }
    }

    @Test fun rewindDeletesTheCommittedPrefixAfterRemovingTheComposingTail() {
        val original = ComposingText().apply { insertAtCursorPosition("toukyouniiku", InputStyle.ROMAN2KANA) }
        val record = AutoCommitRewindRecord("東京に", "とうきょうに", "前文", original.clone())
        val editor = Editor("前文東京に", "行く")
        val result = AutoCommitRewind.restore(record, "いく", "行く", editor)
        assertEquals(AutoCommitRewind.Result.RESTORED, result)
        assertEquals("前文", editor.committed)
        assertEquals(original.convertTarget, editor.composing)
        assertEquals(listOf("compose:", "delete:3", "compose:とうきょうにいく"), editor.calls)
    }

    @Test fun rewindRefusesChangedEditorTextAndRestoresTailOnDeleteFailure() {
        val original = ComposingText().apply { insertAtCursorPosition("toukyouniiku", InputStyle.ROMAN2KANA) }
        val record = AutoCommitRewindRecord("東京に", "とうきょうに", "", original.clone())
        val changed = Editor("別の文字", "行く")
        assertEquals(AutoCommitRewind.Result.UNCHANGED, AutoCommitRewind.restore(record, "いく", "行く", changed))
        assertTrue(changed.calls.isEmpty())
        val failed = Editor("東京に", "行く").apply { failDelete = true }
        assertEquals(AutoCommitRewind.Result.UNCHANGED, AutoCommitRewind.restore(record, "いく", "行く", failed))
        assertEquals("東京に行く", failed.committed + failed.composing)
    }
}
