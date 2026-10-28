package com.zeroconvert.ime

import com.zeroconvert.ime.model.ConversionModelSelection as Selection
import org.junit.Assert.*
import org.junit.Test

class ConversionModelSelectionTest {
    @Test fun migratesBundledSelectionsWithoutTreatingImportedOrAssistModelsAsConverters() {
        assertEquals(Selection.IQ4, Selection.replacement("zenz.gguf"))
        assertEquals(Selection.IQ4, Selection.replacement("zenz-v3.2-xsmall-q4km.gguf"))
        assertEquals(Selection.Q8, Selection.replacement("zenz-v3.2-small-q4km.gguf"))
        assertEquals(Selection.Q8, Selection.replacement(Selection.Q8))
        assertEquals(Selection.IQ4, Selection.replacement(Selection.IQ4))
        assertNull(Selection.replacement("personal.gguf"))
        assertNull(Selection.replacement("zerocon-model-corrector.gguf"))
    }
}
