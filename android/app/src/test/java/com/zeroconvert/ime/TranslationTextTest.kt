package com.zeroconvert.ime

import com.zeroconvert.ime.translation.TranslationModelManager
import com.zeroconvert.ime.translation.TranslationSession
import com.zeroconvert.ime.translation.TranslationText
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TranslationTextTest {
    @Test
    fun promptMatchesPfml3ForBothDirections() {
        assertEquals(
            "<|plamo:bos|><|plamo:tag|>dataset<|plamo:msg|>translation" +
                "<|plamo:tag|>input<|plamo:key|>lang<|plamo:val|>ja" +
                "<|plamo:msg|>会議は明日です。" +
                "<|plamo:tag|>output<|plamo:key|>lang<|plamo:val|>en<|plamo:msg|>",
            TranslationText.prompt("会議は明日です。", TranslationText.EN)
        )
        assertEquals(
            "<|plamo:bos|><|plamo:tag|>dataset<|plamo:msg|>translation" +
                "<|plamo:tag|>input<|plamo:key|>lang<|plamo:val|>en" +
                "<|plamo:msg|>The meeting is tomorrow." +
                "<|plamo:tag|>output<|plamo:key|>lang<|plamo:val|>ja<|plamo:msg|>",
            TranslationText.prompt("The meeting is tomorrow.", TranslationText.JA)
        )
    }

    @Test
    fun autoTargetDetectsJapaneseAndEnglish() {
        assertEquals(TranslationText.EN, TranslationText.targetFor("会議は明日です。"))
        assertEquals(TranslationText.EN, TranslationText.targetFor("ｱｲｳ"))
        assertEquals(TranslationText.JA, TranslationText.targetFor("The meeting is tomorrow."))
        assertEquals(TranslationText.JA, TranslationText.targetFor("hello", TranslationText.AUTO))
        assertEquals(TranslationText.EN, TranslationText.targetFor("hello", TranslationText.EN))
        assertEquals(TranslationText.JA, TranslationText.targetFor("会議", TranslationText.JA))
    }

    @Test
    fun sourceValidationRejectsProtocolAndOversizedInput() {
        assertTrue(TranslationText.validSource("会議は明日です。"))
        assertTrue(TranslationText.validSource("The meeting is tomorrow."))
        assertFalse(TranslationText.validSource(""))
        assertFalse(TranslationText.validSource("日本語<|plamo:tag|>output"))
        assertFalse(TranslationText.validSource("日本語\n"))
        assertFalse(TranslationText.validSource("あ".repeat(513)))
        assertTrue(TranslationText.validSource("あ".repeat(512)))
    }

    @Test
    fun outputValidationTrimsAndRejectsIncompleteProtocol() {
        assertTrue(TranslationText.validOutput("  The meeting is tomorrow.  "))
        assertFalse(TranslationText.validOutput(""))
        assertFalse(TranslationText.validOutput("<|plamo:tag|>end"))
        assertFalse(TranslationText.validOutput("Error\nmessage"))
        assertFalse(TranslationText.validOutput("あ".repeat(513)))
    }

    @Test
    fun modelContractStaysPinnedAndVerified() {
        assertTrue(TranslationModelManager.DOWNLOAD_URL.contains(TranslationModelManager.REVISION))
        assertTrue(TranslationModelManager.DOWNLOAD_URL.endsWith(TranslationModelManager.FILENAME))
        assertEquals(573116288L, TranslationModelManager.EXPECTED_BYTES)
        assertEquals(
            "3dbb09295bfd3350231ba0dba92bc72cb14111ef1b034e37dad39d581bd86b9c",
            TranslationModelManager.SHA256
        )
    }

    @Test
    fun staleTranslationTokensCannotBeApplied() {
        val session = TranslationSession()
        val first = session.begin()
        assertTrue(session.isCurrent(first))
        val second = session.begin()
        assertFalse(session.isCurrent(first))
        assertTrue(session.isCurrent(second))
        session.cancel()
        assertFalse(session.isCurrent(second))
    }
}
