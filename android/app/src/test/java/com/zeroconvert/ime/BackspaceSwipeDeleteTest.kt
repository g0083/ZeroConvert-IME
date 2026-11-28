package com.zeroconvert.ime

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class BackspaceSwipeDeleteTest {
    @Test fun trackerActivatesLeftwardStepsAndCanCancelByReturningRight() {
        val tracker = BackspaceSwipeDeleteTracker(originX = 200f, activationThresholdPx = 24f, stepPx = 18f)

        // Small jitter below activation threshold does not activate.
        assertNull(tracker.move(185f))
        assertFalse(tracker.isActive)
        assertEquals(0, tracker.steps)

        // Dragging 24px left activates step 1.
        assertEquals(1, tracker.move(176f))
        assertTrue(tracker.isActive)
        assertEquals(1, tracker.steps)

        // Further 18px left increments to step 2, another 18px to step 3.
        assertEquals(2, tracker.move(158f))
        assertEquals(3, tracker.move(140f))

        // Dragging back right decrements back to 1 and then 0 (cancel).
        assertEquals(1, tracker.move(170f))
        assertEquals(0, tracker.move(195f))
        assertEquals(0, tracker.steps)
    }

    @Test fun wordSegmenterHandlesJapaneseAndEnglishChunks() {
        assertEquals(0, WordDeleteSegmenter.deletionLengthForSteps("こんにちは", 0))
        assertEquals(0, WordDeleteSegmenter.deletionLengthForSteps("", 1))

        // English words with trailing space: step 1 removes "world ", step 2 removes "hello "
        val en = "hello world "
        assertEquals(6, WordDeleteSegmenter.deletionLengthForSteps(en, 1))
        assertEquals(en.length, WordDeleteSegmenter.deletionLengthForSteps(en, 2))

        // Japanese mixed script: "今日はテストです" -> "です"(2), "テスト"(3), "は"(1), "今日"(2)
        val jp = "今日はテストです"
        assertEquals(2, WordDeleteSegmenter.deletionLengthForSteps(jp, 1))
        assertEquals(5, WordDeleteSegmenter.deletionLengthForSteps(jp, 2))
        assertEquals(6, WordDeleteSegmenter.deletionLengthForSteps(jp, 3))
        assertEquals(8, WordDeleteSegmenter.deletionLengthForSteps(jp, 4))
        assertEquals(8, WordDeleteSegmenter.deletionLengthForSteps(jp, 10))
    }

    @Test fun wordSegmenterHandlesNewlineAsOwnStep() {
        val text = "abc\n"
        assertEquals(1, WordDeleteSegmenter.deletionLengthForSteps(text, 1))
        assertEquals(4, WordDeleteSegmenter.deletionLengthForSteps(text, 2))
    }
}
