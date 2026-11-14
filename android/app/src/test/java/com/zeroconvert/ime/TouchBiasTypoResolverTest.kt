package com.zeroconvert.ime

import com.zeroconvert.ime.assist.TouchBiasTypoResolver
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class TouchBiasTypoResolverTest {

    @Test
    fun biasedRomajiVariants_alignsFullOrSuffixBufferAndFiltersNonLetters() {
        val biases = listOf(
            TouchKeyBias('w'),
            TouchKeyBias('a'),
            TouchKeyBias('t'),
            TouchKeyBias('a'),
            TouchKeyBias('d', listOf('s' to 0.85f, 'ー' to 0.99f, 'f' to 0.30f)),
            TouchKeyBias('i')
        )
        val variants = TouchBiasTypoResolver.biasedRomajiVariants("watadi", biases)
        assertEquals(listOf("watasi", "watafi"), variants.map { it.text })
        assertTrue(variants[0].confidence > variants[1].confidence)

        // Suffix alignment works even if an earlier prefix exists before the tracked strokes.
        val suffixBiases = listOf(
            TouchKeyBias('d', listOf('s' to 0.85f)),
            TouchKeyBias('i')
        )
        val suffixVariants = TouchBiasTypoResolver.biasedRomajiVariants("watadi", suffixBiases)
        assertEquals(listOf("watasi"), suffixVariants.map { it.text })
    }

    @Test
    fun rankEnglishCorrections_recoversAdjacentMistypedWord() {
        val words = listOf("the", "they", "there", "tie", "toe", "age", "tiger")
        val biases = listOf(
            TouchKeyBias('t'),
            TouchKeyBias('g', listOf('h' to 0.85f, 'f' to 0.30f)),
            TouchKeyBias('e')
        )
        val recovered = TouchBiasTypoResolver.rankEnglishCorrections(
            prefix = "tge",
            touchBiases = biases,
            words = words,
            limit = 4
        )
        assertEquals("the", recovered.firstOrNull())
        assertTrue(recovered.containsAll(listOf("the", "they", "there")))
    }


}

