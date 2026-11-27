package com.zeroconvert.ime

import org.junit.Assert.assertEquals
import org.junit.Test

class ReadingTapTest {
    private fun decide(
        confirming: Boolean = false,
        translationMode: Boolean = false,
        automaticCorrection: Boolean = false,
        japaneseReading: String = "",
        englishBuffer: String = ""
    ) = ReadingTap.decide(confirming, translationMode, automaticCorrection, japaneseReading, englishBuffer)

    @Test fun japaneseReadingStaysHiragana() {
        assertEquals(ReadingTapCommit.Hiragana, decide(japaneseReading = "きょうは"))
    }

    @Test fun englishBufferCommitsAsSpelled() {
        assertEquals(ReadingTapCommit.English, decide(englishBuffer = "he"))
    }

    @Test fun emptyBarIgnoresTheTap() {
        assertEquals(ReadingTapCommit.Ignored, decide())
    }

    @Test fun confirmationOwnsTheCommit() {
        assertEquals(ReadingTapCommit.Ignored, decide(confirming = true, japaneseReading = "きょう"))
    }

    @Test fun translationModeIgnoresTheTap() {
        assertEquals(ReadingTapCommit.Ignored, decide(translationMode = true, japaneseReading = "きょう"))
    }

    @Test fun automaticCorrectionKeepsTheReadingUnconfirmed() {
        assertEquals(
            ReadingTapCommit.Ignored,
            decide(automaticCorrection = true, japaneseReading = "おかせり")
        )
    }
}
