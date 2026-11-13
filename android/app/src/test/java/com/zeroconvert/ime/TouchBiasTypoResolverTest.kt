package com.zeroconvert.ime

import com.zeroconvert.ime.assist.AiAssistType
import com.zeroconvert.ime.assist.TouchBiasTypoResolver
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
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
    fun suggestCorrection_resolvesEdgeTypoUsingVocabLookup() {
        val biases = listOf(
            TouchKeyBias('w'),
            TouchKeyBias('a'),
            TouchKeyBias('t'),
            TouchKeyBias('a'),
            TouchKeyBias('d', listOf('s' to 0.88f)),
            TouchKeyBias('i')
        )
        val vocab = mapOf("わたし" to "私")
        val result = TouchBiasTypoResolver.suggestCorrection(
            rawInput = "watadi",
            reading = "わたぢ",
            baseline = "わたぢ",
            touchBiases = biases,
            knownCleanCheck = { it in vocab },
            vocabLookup = { vocab[it] },
            dictLookup = { null },
            allowAutoApplyWhenReady = false
        )
        assertNotNull(result)
        assertEquals(AiAssistType.TYPO_CORRECTION, result?.type)
        assertEquals("私", result?.text)
        assertEquals("💡 私", result?.label)
    }

    @Test
    fun suggestCorrection_breaksTieUsingTouchConfidenceOverAlphabeticalOrder() {
        // Suppose both "かさ" (kasa) and "かふぁ"/"かだ" exist, and typing "kaf..." ('f' near 'd' and 's')
        // landed much closer to 's' (0.90) than 'a'/'g' (0.45). Touch confidence must pick 's'.
        val biases = listOf(
            TouchKeyBias('k'),
            TouchKeyBias('a'),
            TouchKeyBias('f', listOf('d' to 0.45f, 's' to 0.90f)),
            TouchKeyBias('a')
        )
        val vocab = mapOf("かだ" to "かだ候補", "かさ" to "傘")
        val result = TouchBiasTypoResolver.suggestCorrection(
            rawInput = "kafa",
            reading = "かふぁ",
            baseline = "かふぁ",
            touchBiases = biases,
            knownCleanCheck = { it in vocab },
            vocabLookup = { vocab[it] },
            dictLookup = { null },
            allowAutoApplyWhenReady = false
        )
        assertNotNull(result)
        assertEquals("傘", result?.text)
    }

    @Test
    fun suggestCorrection_neverOverwritesKnownCleanReading() {
        val biases = listOf(
            TouchKeyBias('k'),
            TouchKeyBias('a'),
            TouchKeyBias('s', listOf('d' to 0.95f)),
            TouchKeyBias('a')
        )
        val vocab = mapOf("かさ" to "傘", "かだ" to "かだ候補")
        val result = TouchBiasTypoResolver.suggestCorrection(
            rawInput = "kasa",
            reading = "かさ",
            baseline = "傘",
            touchBiases = biases,
            knownCleanCheck = { it in vocab },
            vocabLookup = { vocab[it] },
            dictLookup = { null }
        )
        assertNull(result)
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

    @Test
    fun selectCorrectionResult_prefersTouchOverWeakNonAutoPrimaryButPreservesAutoPrimary() {
        val weakPrimary = com.zeroconvert.ime.assist.AiAssistResult(
            type = AiAssistType.TYPO_CORRECTION,
            text = "別の候補",
            label = "💡 別の候補",
            relevance = 30,
            sourceReading = "きんにちは",
            autoApply = false
        )
        val autoPrimary = com.zeroconvert.ime.assist.AiAssistResult(
            type = AiAssistType.TYPO_CORRECTION,
            text = "こんにちは",
            label = "💡 こんにちは",
            relevance = 90,
            sourceReading = "きんにちは",
            autoApply = true
        )
        val touchStrong = com.zeroconvert.ime.assist.AiAssistResult(
            type = AiAssistType.TYPO_CORRECTION,
            text = "こんにちは",
            label = "💡 こんにちは",
            relevance = 95,
            sourceReading = "きんにちは",
            autoApply = true
        )
        val touchMedium = touchStrong.copy(relevance = 60, autoApply = false)

        assertEquals(
            touchMedium,
            com.zeroconvert.ime.assist.AiAssistManager.selectCorrectionResult(weakPrimary, touchMedium)
        )
        assertEquals(
            touchStrong,
            com.zeroconvert.ime.assist.AiAssistManager.selectCorrectionResult(weakPrimary, touchStrong)
        )
        assertEquals(
            autoPrimary,
            com.zeroconvert.ime.assist.AiAssistManager.selectCorrectionResult(autoPrimary, touchStrong)
        )
        assertEquals(
            weakPrimary,
            com.zeroconvert.ime.assist.AiAssistManager.selectCorrectionResult(weakPrimary, null)
        )
    }

    @Test
    fun streamAiAssist_resolvesTouchBiasCorrectionWhenEnabled() = kotlinx.coroutines.runBlocking {
        val biases = listOf(
            TouchKeyBias('k'),
            TouchKeyBias('i', listOf('o' to 0.85f)),
            TouchKeyBias('n'),
            TouchKeyBias('n'),
            TouchKeyBias('i'),
            TouchKeyBias('c'),
            TouchKeyBias('h'),
            TouchKeyBias('i'),
            TouchKeyBias('h'),
            TouchKeyBias('a')
        )
        val inference = object : com.zeroconvert.ime.assist.AssistInference {
            override val revision: Long = 1L
            override fun complete(
                model: com.zeroconvert.ime.assist.AssistModel,
                prompt: String,
                maxTokens: Int,
                timeoutMs: Int
            ) = com.zeroconvert.ime.assist.ModelCompletion("", "eos")
        }
        val updates = mutableListOf<com.zeroconvert.ime.assist.AssistUpdate>()
        com.zeroconvert.ime.assist.AiAssistManager.streamAiAssist(
            reading = "きんにちは",
            baseline = "きんにちは",
            context = "",
            allowCorrection = true,
            inference = inference,
            rawInput = "kinnichiha",
            touchBiases = biases,
            enableDynamicTouchAssist = true
        ) { update ->
            updates.add(update)
        }
        val chosen = updates.lastOrNull()?.selected
        assertNotNull(chosen)
        assertEquals(AiAssistType.TYPO_CORRECTION, chosen?.type)
        assertEquals("こんにちは", chosen?.text)
    }
}

