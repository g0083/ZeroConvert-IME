package com.zeroconvert.ime

import com.zeroconvert.ime.azookey.input.ComposingText
import com.zeroconvert.ime.azookey.input.InputStyle
import com.zeroconvert.ime.engine.engineTouchEvidence
import org.junit.Assert.*
import org.junit.Test

class EngineTouchEvidenceTest {
    private fun input(raw: String) = ComposingText().apply {
        insertAtCursorPosition(raw, InputStyle.ROMAN2KANA)
    }

    @Test fun evidenceUsesOriginalInputOffsetsWithAnUntrackedPrefix() {
        val original = input("watadi")
        val evidence = engineTouchEvidence(original, listOf(TouchKeyBias('d', listOf('s' to 0.9f)), TouchKeyBias('i')))
        assertEquals(1, evidence.length())
        assertEquals(4, evidence.getJSONObject(0).getInt("inputIndex"))
        assertEquals("d", evidence.getJSONObject(0).getString("typed"))
        assertEquals("s", evidence.getJSONObject(0).getJSONArray("alternatives").getJSONObject(0).getString("key"))
        assertEquals("わたぢ", original.convertTarget)
        assertEquals(6, original.input.size)
    }

    @Test fun staleKeysAndUnboundedSnapshotsCannotSupplyTouchWeights() {
        assertEquals(0, engineTouchEvidence(input("watadi"), listOf(TouchKeyBias('x', listOf('s' to 0.9f)))).length())
        assertEquals(0, engineTouchEvidence(input("watadi"), emptyList()).length())
        assertEquals(0, engineTouchEvidence(input("a".repeat(129)), listOf(TouchKeyBias('a', listOf('s' to 0.9f)))).length())
    }

    @Test fun malformedGeometricWeightsAreOmitted() {
        val evidence = engineTouchEvidence(input("watadi"), listOf(TouchKeyBias('i', listOf(
            'o' to Float.NaN, 'u' to Float.POSITIVE_INFINITY, 'j' to -0.1f, 'k' to 1.1f))))
        assertEquals(0, evidence.length())
    }
}
