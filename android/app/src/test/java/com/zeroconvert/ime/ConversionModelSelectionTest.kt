package com.zeroconvert.ime

import com.zeroconvert.ime.model.ConversionModelSelection as Selection
import org.junit.Assert.*
import org.junit.Test

class ConversionModelSelectionTest {
    @Test fun migratesBundledSelectionsWithoutTreatingImportedOrAssistModelsAsConverters() {
        assertEquals(Selection.IQ4, Selection.replacement("zenz.gguf"))
        assertEquals(Selection.IQ4, Selection.replacement("zenz-v3.2-xsmall-q4km.gguf"))
        assertEquals(Selection.Q8, Selection.replacement("zenz-v3.2-small-q4km.gguf"))
        assertEquals(Selection.IQ4, Selection.replacement("zerocon-nano-v4-iq4nl-emb8.gguf"))
        assertEquals(Selection.Q8, Selection.replacement("zerocon-nano-v4-q8_0.gguf"))
        assertEquals(Selection.IQ4, Selection.replacement("zerocon-nano-v4.1-iq4nl-emb8.gguf"))
        assertEquals(Selection.Q8, Selection.replacement("zerocon-nano-v4.1-q8_0.gguf"))
        assertEquals(Selection.IQ4, Selection.replacement("zerocon-nano-v4.2-iq4nl-emb8.gguf"))
        assertEquals(Selection.Q8, Selection.replacement("zerocon-nano-v4.2-q8_0.gguf"))
        assertEquals(Selection.IQ4, Selection.replacement("nano-v4-cap-nam-r4-iq4nl-emb8.gguf"))
        assertEquals(Selection.Q8, Selection.replacement("nano-v4-cap-nam-r4-q8_0.gguf"))
        assertEquals(Selection.IQ4, Selection.replacement("nano-v4-cap-nam-r6-iq4nl-emb8.gguf"))
        assertEquals(Selection.Q8, Selection.replacement("nano-v4-cap-nam-r6-q8_0.gguf"))
        assertEquals(Selection.Q8, Selection.replacement(Selection.Q8))
        assertEquals(Selection.IQ4, Selection.replacement(Selection.IQ4))
        assertEquals(Selection.STANDARD_IQ4, Selection.replacement("standard-v4-cap-nam-r3-iq4nl-emb8.gguf"))
        assertEquals(Selection.STANDARD_Q8, Selection.replacement("standard-v4-cap-nam-r3-q8_0.gguf"))
        assertEquals(Selection.STANDARD_IQ4, Selection.replacement("zerocon-standard-v4.1-iq4nl-emb8.gguf"))
        assertEquals(Selection.STANDARD_Q8, Selection.replacement("zerocon-standard-v4.1-q8_0.gguf"))
        assertEquals(Selection.STANDARD_IQ4, Selection.replacement("zerocon-standard-v4.2-iq4nl-emb8.gguf"))
        assertEquals(Selection.STANDARD_Q8, Selection.replacement("zerocon-standard-v4.2-q8_0.gguf"))
        assertEquals(Selection.STANDARD_IQ4, Selection.replacement("standard-v4-cap-nam-r4-iq4nl-emb8.gguf"))
        assertEquals(Selection.STANDARD_Q8, Selection.replacement("standard-v4-cap-nam-r4-q8_0.gguf"))
        assertEquals(Selection.STANDARD_IQ4, Selection.replacement("standard-v4-cap-nam-r6-iq4nl-emb8.gguf"))
        assertEquals(Selection.STANDARD_Q8, Selection.replacement("standard-v4-cap-nam-r6-q8_0.gguf"))
        assertEquals(Selection.STANDARD_IQ4, Selection.replacement(Selection.STANDARD_IQ4))
        assertEquals(Selection.STANDARD_Q8, Selection.replacement(Selection.STANDARD_Q8))
        assertNull(Selection.replacement("personal.gguf"))
        assertNull(Selection.replacement("zerocon-model-corrector.gguf"))
    }
}
