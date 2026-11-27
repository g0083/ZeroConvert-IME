package com.zeroconvert.ime

/**
 * What a tap on the reading bar commits.
 *
 * The bar shows the reading as typed, so the tap accepts that text instead of converting
 * it: a Japanese reading stays hiragana and an English buffer is committed as spelled.
 * A correction applied automatically must stay unconfirmed composition input: the user
 * never chose it, so an incidental tap must not turn model output into committed text.
 * Confirmation and translation own the editor while they run, so a tap must not race them.
 */
enum class ReadingTapCommit { Hiragana, English, Ignored }

object ReadingTap {
    fun decide(
        confirming: Boolean,
        translationMode: Boolean,
        automaticCorrection: Boolean,
        japaneseReading: String,
        englishBuffer: String
    ): ReadingTapCommit = when {
        confirming || translationMode || automaticCorrection -> ReadingTapCommit.Ignored
        englishBuffer.isNotEmpty() -> ReadingTapCommit.English
        japaneseReading.isNotEmpty() -> ReadingTapCommit.Hiragana
        else -> ReadingTapCommit.Ignored
    }
}
