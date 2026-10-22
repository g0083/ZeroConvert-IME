package com.zeroconvert.ime

import org.junit.Assert.*
import org.junit.Test

class AutoCommitAndPipelineTest {

    @Test
    fun testIsNaturalAutoCommitPrefix() {
        // Natural clause boundary ending in Hiragana particle, followed by next Kanji
        assertTrue(
            ZeroConvertInputMethodService.isNaturalAutoCommitPrefix(
                "私は学校に",
                "私は学校に行って勉強する"
            )
        )

        // Ending in Japanese punctuation
        assertTrue(
            ZeroConvertInputMethodService.isNaturalAutoCommitPrefix(
                "今日はいい天気ですね。",
                "今日はいい天気ですね。傘を持ってきていません"
            )
        )

        // Cutting inside an inflected verb like 変えられる -> 変 or 変え must be rejected!
        assertFalse(
            ZeroConvertInputMethodService.isNaturalAutoCommitPrefix(
                "変",
                "変えられるので大丈夫です"
            )
        )
        assertFalse(
            ZeroConvertInputMethodService.isNaturalAutoCommitPrefix(
                "変え",
                "変えられるので大丈夫です"
            )
        )

        // Natural boundary at clause particle followed by next Kanji
        assertTrue(
            ZeroConvertInputMethodService.isNaturalAutoCommitPrefix(
                "変えられるので",
                "変えられるので大丈夫です"
            )
        )

        // Pure hiragana without kanji/katakana must be rejected
        assertFalse(
            ZeroConvertInputMethodService.isNaturalAutoCommitPrefix(
                "とても",
                "とてもいい天気です"
            )
        )
    }

    @Test
    fun testSlidingChunkAutoCommitFindsLongestStablePrefix() {
        // Simulate typing: "わたしはがっこうにいってべんきょうしています" (len = 21, maxCommit = 14)
        val history = listOf(
            InferenceSnapshot("わたしは", "私は"),
            InferenceSnapshot("わたしはがっこう", "私は学校"),
            InferenceSnapshot("わたしはがっこうに", "私は学校に"),
            InferenceSnapshot("わたしはがっこうにいって", "私は学校に行って"),
            InferenceSnapshot("わたしはがっこうにいってべん", "私は学校に行って勉"),
            InferenceSnapshot("わたしはがっこうにいってべんきょう", "私は学校に行って勉強"),
            InferenceSnapshot("わたしはがっこうにいってべんきょうし", "私は学校に行って勉強し"),
            InferenceSnapshot("わたしはがっこうにいってべんきょうして", "私は学校に行って勉強して"),
            InferenceSnapshot("わたしはがっこうにいってべんきょうしています", "私は学校に行って勉強しています")
        )

        val currentReading = "わたしはがっこうにいってべんきょうしています"
        val candidate = ZeroConvertInputMethodService.findStableAutoCommitPrefix(currentReading, history)
        assertNotNull(candidate)
        assertEquals(12, candidate!!.readingLength)
        assertEquals("わたしはがっこうにいって", candidate.committedReading)
        assertEquals("私は学校に行って", candidate.surfacePrefix)
    }

    @Test
    fun testSlidingChunkAutoCommitSkipsWhenTrailingRomajiOrTooShort() {
        val history = listOf(
            InferenceSnapshot("きょうは", "今日は"),
            InferenceSnapshot("きょうはとても", "今日はとても"),
            InferenceSnapshot("きょうはとてもいい", "今日はとてもいい"),
            InferenceSnapshot("きょうはとてもいいてんき", "今日はとてもいい天気"),
            InferenceSnapshot("きょうはとてもいいてんきで", "今日はとてもいい天気で")
        )
        // Too short (< 16)
        assertNull(ZeroConvertInputMethodService.findStableAutoCommitPrefix("きょうはとてもいいてんきで", history))

        // Trailing romaji consonant 'k'
        assertNull(ZeroConvertInputMethodService.findStableAutoCommitPrefix("きょうはとてもいいてんきですねk", history))
    }
}
