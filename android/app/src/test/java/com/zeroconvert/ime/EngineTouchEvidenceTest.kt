package com.zeroconvert.ime

import com.zeroconvert.ime.azookey.input.ComposingText
import com.zeroconvert.ime.azookey.input.InputPiece
import com.zeroconvert.ime.azookey.input.InputStyle
import com.zeroconvert.ime.engine.engineFlickTouchHints
import com.zeroconvert.ime.engine.engineInputMode
import com.zeroconvert.ime.engine.engineRawRomaji
import com.zeroconvert.ime.engine.engineTouchEvidence
import com.zeroconvert.ime.engine.engineTouchHints
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

    @Test fun rawRomajiAndTouchHintsAlignByRawInputIndex() {
        val composing = input("pmotteru")
        assertEquals("pmotteru", engineRawRomaji(composing))
        assertEquals(
            "0:o=0.85,l=0.30",
            engineTouchHints(
                composing,
                listOf(
                    TouchKeyBias('p', listOf('o' to 0.85f, 'l' to 0.30f)),
                    TouchKeyBias('m', emptyList()),
                    TouchKeyBias('o', emptyList()),
                    TouchKeyBias('t', emptyList()),
                    TouchKeyBias('t', emptyList()),
                    TouchKeyBias('e', emptyList()),
                    TouchKeyBias('r', emptyList()),
                    TouchKeyBias('u', emptyList())
                )
            )
        )
        // Suffix alignment (when prefix keys came without touch bias) still uses raw indices.
        assertEquals(
            "7:i=0.60",
            engineTouchHints(
                composing,
                listOf(
                    TouchKeyBias('r', emptyList()),
                    TouchKeyBias('u', listOf('i' to 0.60f, 'z' to Float.NaN))
                )
            )
        )
    }

    @Test fun mixedStyleOrMismatchedBiasesSuppressRawRomajiAndHints() {
        val mixed = ComposingText().apply {
            insertAtCursorPosition("あ", InputStyle.DIRECT)
            insertAtCursorPosition("mo", InputStyle.ROMAN2KANA)
        }
        assertEquals("", engineRawRomaji(mixed))
        assertEquals("", engineTouchHints(mixed, listOf(TouchKeyBias('o', listOf('p' to 0.9f)))))

        val romaji = input("pmotteru")
        assertEquals("", engineTouchHints(romaji, listOf(TouchKeyBias('x', listOf('z' to 0.9f)))))
    }

    @Test fun flickInputModeAndDirectTouchHintsAlignAndRejectMixedStyles() {
        val flickComposing = ComposingText().apply {
            "あきがとう".forEach { insertAtCursorPosition(it.toString(), InputStyle.DIRECT) }
        }
        assertEquals("flick", engineInputMode(flickComposing, JapaneseInputLayout.FLICK_12KEY))
        assertEquals("qwerty", engineInputMode(flickComposing, JapaneseInputLayout.QWERTY))

        val fullHints = listOf(
            TouchKeyBias('あ'),
            TouchKeyBias('き', listOf('り' to 0.82f, 'か' to 0.40f)),
            TouchKeyBias('が'),
            TouchKeyBias('と'),
            TouchKeyBias('う')
        )
        assertEquals("1:り=0.82,か=0.40", engineFlickTouchHints(flickComposing, fullHints))

        // Suffix alignment (when prefix chars had no recorded bias) preserves direct indices.
        assertEquals(
            "4:え=0.55",
            engineFlickTouchHints(
                flickComposing,
                listOf(
                    TouchKeyBias('と'),
                    TouchKeyBias('う', listOf('え' to 0.55f))
                )
            )
        )

        // Mixed ROMAN2KANA + DIRECT falls back to qwerty mode and suppresses flick hints.
        val mixed = ComposingText().apply {
            insertAtCursorPosition("あ", InputStyle.DIRECT)
            insertAtCursorPosition("ri", InputStyle.ROMAN2KANA)
        }
        assertEquals("qwerty", engineInputMode(mixed, JapaneseInputLayout.FLICK_12KEY))
        assertEquals("", engineFlickTouchHints(mixed, listOf(TouchKeyBias('り', listOf('き' to 0.8f)))))
    }

    @Test fun qwertyRawRomajiAndHintsPreserveLongVowelDashAndLBoundary() {
        val withLongVowel = input("ーasokon")
        assertEquals("-asokon", engineRawRomaji(withLongVowel))
        assertEquals(
            "0:p=0.85,l=0.42",
            engineTouchHints(
                withLongVowel,
                listOf(
                    TouchKeyBias('ー', listOf('p' to 0.85f, 'l' to 0.42f)),
                    TouchKeyBias('a'),
                    TouchKeyBias('s'),
                    TouchKeyBias('o'),
                    TouchKeyBias('k'),
                    TouchKeyBias('o'),
                    TouchKeyBias('n')
                )
            )
        )

        val lMistypedForLongVowel = input("kaldo")
        assertEquals("kaldo", engineRawRomaji(lMistypedForLongVowel))
        assertEquals(
            "2:-=0.78",
            engineTouchHints(
                lMistypedForLongVowel,
                listOf(
                    TouchKeyBias('k'),
                    TouchKeyBias('a'),
                    TouchKeyBias('l', listOf('ー' to 0.78f)),
                    TouchKeyBias('d'),
                    TouchKeyBias('o')
                )
            )
        )
        val evidence = engineTouchEvidence(
            lMistypedForLongVowel,
            listOf(TouchKeyBias('l', listOf('ー' to 0.78f)), TouchKeyBias('d'), TouchKeyBias('o'))
        )
        assertEquals(1, evidence.length())
        assertEquals(2, evidence.getJSONObject(0).getInt("inputIndex"))
        assertEquals("-", evidence.getJSONObject(0).getJSONArray("alternatives").getJSONObject(0).getString("key"))
    }

    @Test fun rawRomajiAndHintsCrossCompositionSeparatorsAndProtectSingleN() {
        val elements = mutableListOf(
            ComposingText.InputElement('k', InputStyle.ROMAN2KANA),
            ComposingText.InputElement('o', InputStyle.ROMAN2KANA),
            ComposingText.InputElement('n', InputStyle.ROMAN2KANA),
            ComposingText.InputElement(InputPiece.CompositionSeparator, InputStyle.FROZEN),
            ComposingText.InputElement('a', InputStyle.ROMAN2KANA),
            ComposingText.InputElement('t', InputStyle.ROMAN2KANA),
            ComposingText.InputElement('o', InputStyle.ROMAN2KANA),
        )
        val composed = ComposingText(
            convertTargetCursorPosition = 4,
            input = elements,
            convertTarget = "こんあと",
        )
        assertEquals("konnato", engineRawRomaji(composed))
        val biases = listOf(
            TouchKeyBias('k'),
            TouchKeyBias('o'),
            TouchKeyBias('n'),
            TouchKeyBias('a', listOf('s' to 0.75f)),
            TouchKeyBias('t'),
            TouchKeyBias('o', listOf('i' to 0.65f)),
        )
        assertEquals("4:s=0.75;6:i=0.65", engineTouchHints(composed, biases))
    }

    @Test fun flickTouchHintsFormatV2ObservationsAndLegacyFallback() {
        val flickComposing = ComposingText().apply {
            "あわ、".forEach { insertAtCursorPosition(it.toString(), InputStyle.DIRECT) }
        }
        assertEquals(
            "flick_toggle",
            engineInputMode(flickComposing, JapaneseInputLayout.FLICK_12KEY, isFlickOnly = false)
        )
        val cornerBias = computeFlickTouchBias(
            keyDef = FlickKeyDefinitions.keyA,
            committedDir = FlickDirection.CENTER,
            dx = 0f,
            dy = 0f,
            thresholdPx = 20f,
            startX = 92f,
            startY = 92f,
            widthPx = 100f,
            heightPx = 100f
        )!!
        val puncEdgeBias = computeFlickTouchBias(
            keyDef = FlickKeyDefinitions.keyPunc,
            committedDir = FlickDirection.CENTER,
            dx = 0f,
            dy = 0f,
            thresholdPx = 20f,
            startX = 5f,
            startY = 50f,
            widthPx = 100f,
            heightPx = 100f
        )!!
        val v2Formatted = engineFlickTouchHints(
            flickComposing,
            listOf(cornerBias, TouchKeyBias('わ'), puncEdgeBias)
        )
        assertEquals("v2;0:f,92,92,0,0;2:f,5,50,0,0", v2Formatted)

        // Legacy TouchKeyBias without flickObservation keeps legacy string format.
        val legacyFormatted = engineFlickTouchHints(
            flickComposing,
            listOf(TouchKeyBias('あ', listOf('か' to 0.65f)), TouchKeyBias('わ'), TouchKeyBias('、', listOf('わ' to 0.72f)))
        )
        assertEquals("0:か=0.65;2:わ=0.72", legacyFormatted)
    }
}
