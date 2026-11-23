package com.zeroconvert.ime

import com.zeroconvert.ime.model.ModelManager
import org.junit.Assert.*
import org.junit.Test

class ModelManagerCatalogTest {
    @Test fun catalogHasNanoAndStandardPairsWithMandatoryNanoIq4() {
        val specs = ModelManager.BUILT_IN_MODELS
        assertEquals(4, specs.size)
        assertEquals(4, specs.map { it.fileName }.distinct().size)
        assertEquals(4, specs.map { it.sha256 }.distinct().size)
        val mandatory = specs.filter { it.mandatory }
        assertEquals(1, mandatory.size)
        assertEquals(ModelManager.DEFAULT_MODEL_SPEC, mandatory.single())
        assertEquals("zerocon-nano-v4.2-iq4nl-emb8.gguf", ModelManager.DEFAULT_MODEL_SPEC.fileName)
        assertTrue(specs.all { it.expectedSize > 0 && it.sha256.matches(Regex("[0-9a-f]{64}")) })
        assertTrue(specs.all { it.downloadUrl.startsWith("https://github.com/g0083/ZeroConvert-IME/releases/download/models-v4.2/") })
        assertTrue(specs.all { it.downloadUrl.endsWith(it.fileName) })
    }
}
