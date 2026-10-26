package com.zeroconvert.ime

import org.junit.Assert.*
import org.junit.Test

class LearningPolicyTest {
    @Test fun windowsModeNamesAndExistingAndroidPreferencesRemainCompatible() {
        assertEquals(LearningMode.READ_WRITE, LearningMode.from("enabled"))
        assertEquals(LearningMode.READ_ONLY, LearningMode.from("read_only"))
        assertEquals(LearningMode.READ_ONLY, LearningMode.from("READ_ONLY"))
        assertEquals(LearningMode.READ_ONLY, LearningMode.from(" read-only "))
        assertEquals(LearningMode.READ_ONLY, LearningMode.from("readonly"))
        assertEquals(LearningMode.READ_ONLY, LearningMode.from("NO_NEW_LEARNING"))
        assertEquals(LearningMode.DISABLED, LearningMode.from("disabled"))
        assertEquals(LearningMode.DISABLED, LearningMode.from(" DISABLED "))
        assertEquals(LearningMode.READ_WRITE, LearningMode.from(null))
        assertEquals(LearningMode.READ_WRITE, LearningMode.from("unknown"))
    }

    @Test fun deviceTestWriteSuppressionSurvivesPreferenceReloadAndRestoresAfterFailure() {
        val previous = LearningPolicy.mode
        try {
            LearningPolicy.mode = LearningMode.READ_WRITE
            try {
                LearningPolicy.withoutWrites {
                    LearningPolicy.mode = LearningMode.from("enabled")
                    assertTrue(LearningPolicy.canRead)
                    assertFalse(LearningPolicy.canWrite)
                    LearningPolicy.withoutWrites { assertFalse(LearningPolicy.canWrite) }
                    assertFalse(LearningPolicy.canWrite)
                    throw IllegalStateException("test failure")
                }
            } catch (_: IllegalStateException) { }
            assertTrue(LearningPolicy.canWrite)
            LearningPolicy.mode = LearningMode.READ_ONLY
            assertTrue(LearningPolicy.canRead)
            assertFalse(LearningPolicy.canWrite)
            LearningPolicy.mode = LearningMode.DISABLED
            assertFalse(LearningPolicy.canRead)
            assertFalse(LearningPolicy.canWrite)
        } finally { LearningPolicy.mode = previous }
    }
}
