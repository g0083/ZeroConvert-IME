package com.zeroconvert.ime

import com.zeroconvert.ime.model.ModelManager
import org.junit.Assert.*
import org.junit.Test
import java.io.File

class ModelManagerCatalogTest {
    @Test fun catalogHasNanoAndStandardPairsWithMandatoryNanoIq4() {
        val specs = ModelManager.BUILT_IN_MODELS
        assertEquals(4, specs.size)
        assertEquals(4, specs.map { it.fileName }.distinct().size)
        assertEquals(4, specs.map { it.sha256 }.distinct().size)
        val mandatory = specs.filter { it.mandatory }
        assertEquals(1, mandatory.size)
        assertEquals(ModelManager.DEFAULT_MODEL_SPEC, mandatory.single())
        assertEquals("zerocon-nano-v4.2b-iq4nl-emb8.gguf", ModelManager.DEFAULT_MODEL_SPEC.fileName)
        assertTrue(specs.all { it.expectedSize > 0 && it.sha256.matches(Regex("[0-9a-f]{64}")) })
        assertTrue(specs.all { it.downloadUrl.startsWith("https://github.com/g0083/ZeroConvert-IME/releases/download/models-v4.2b/") })
        assertTrue(specs.all { it.downloadUrl.endsWith(it.fileName) })
    }

    @Test fun resolveEffectiveSelectedPathFallsBackToMandatoryWhenMigratedModelMissing() {
        val modelsDir = File("C:/fake/files/models")
        val stdSpec = ModelManager.BUILT_IN_MODELS.first { it.fileName.contains("standard") && it.fileName.contains("iq4") }
        val expectedDefault = File(modelsDir, ModelManager.DEFAULT_MODEL_SPEC.fileName).absolutePath
        val expectedStd = File(modelsDir, stdSpec.fileName).absolutePath

        // Migrated non-default model not downloaded yet, mandatory Nano cached -> fall back to Nano
        assertEquals(
            expectedDefault,
            ModelManager.resolveEffectiveSelectedPath(stdSpec, selectedCached = false, defaultCached = true, modelsDir = modelsDir, savedPath = null)
        )
        // Migrated non-default model IS cached -> keep it selected
        assertEquals(
            expectedStd,
            ModelManager.resolveEffectiveSelectedPath(stdSpec, selectedCached = true, defaultCached = true, modelsDir = modelsDir, savedPath = null)
        )
        // Neither cached -> keep migrated path so UI shows its pending state
        assertEquals(
            expectedStd,
            ModelManager.resolveEffectiveSelectedPath(stdSpec, selectedCached = false, defaultCached = false, modelsDir = modelsDir, savedPath = null)
        )
        // User-imported path -> preserve savedPath
        assertEquals(
            "C:/custom/user.gguf",
            ModelManager.resolveEffectiveSelectedPath(null, selectedCached = false, defaultCached = true, modelsDir = modelsDir, savedPath = "C:/custom/user.gguf")
        )
    }
}
