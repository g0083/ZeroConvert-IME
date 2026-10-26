package com.zeroconvert.ime

import com.zeroconvert.ime.assist.*
import org.junit.Assert.*
import org.junit.Test

class WindowsTypoPortTest {
    private val standard = WindowsTypoData.standard
    private val rules = WindowsTypoRules(standard) { standard[it] }

    @Test fun explicitTableHandlesNasalLongVowelAndTranspositionMistakes() {
        assertEquals("こんにちは", rules.suggest("こんいちは"))
        assertEquals("新橋", rules.suggest("しmばし"))
        assertEquals("入院", rules.suggest("にゅいん"))
        assertEquals("なかった", rules.suggest("なっかた"))
        assertEquals("ファッション", rules.suggest("ふぁしょん"))
    }

    @Test fun longestExplicitPrefixPreservesTheTail() {
        assertEquals("専用車です", rules.suggest("せにょうしゃです"))
        assertEquals("お疲れ様でした", rules.suggest("おつくれさまでした"))
    }

    @Test fun embeddedCorrectionPreservesBothSidesAndRequiresAWordEnd() {
        val isolated = WindowsTypoRules(emptyMap()) { null }
        assertEquals("きのうはお疲れ様でした", isolated.suggest("きのうはおつくれさまでした"))
        assertNull(isolated.suggest("きのうはおつくれさまぞぞ"))
        assertNull(isolated.suggest("きのうぞおつくれさま"))
    }

    @Test fun knownInputIsProtectedEvenIfItAppearsInTheTypoTable() {
        assertNull(rules.suggest("へんかん"))
        assertNull(rules.suggest("きょう"))
        val registered = WindowsTypoRules(standard) { if (it == "にゅいん") "登録した語" else standard[it] }
        assertNull(registered.suggest("にゅいん"))
    }

    @Test fun validVocabularyPrefixDoesNotBecomeAnotherWord() {
        assertNotEquals("昨日ぞぞ", rules.suggest("きょうぞぞ"))
        assertNull(WindowsTypoRules(emptyMap()) { null }.suggest("ぞ".repeat(129)))
    }

    @Test fun phoneticAdjectiveRulesUseVocabularyEvidence() {
        val vocabulary = mapOf("うれしい" to "嬉しい", "うれしかった" to "嬉しかった", "うれしく" to "嬉しく")
        val phonetic = WindowsTypoRules(vocabulary) { vocabulary[it] }
        assertEquals("嬉しい", phonetic.suggest("うれすい"))
        assertEquals("嬉しかった", phonetic.suggest("うれすかった"))
        assertEquals("嬉しく", phonetic.suggest("うれすく"))
    }

    @Test fun kanaAdjacencyMatchesWindowsKeystrokeCoordinates() {
        assertTrue(TypoVariant.kanaAdjacent('す', 'し'))
        assertTrue(TypoVariant.kanaAdjacent('ず', 'じ'))
        assertFalse(TypoVariant.kanaAdjacent('か', 'こ'))
        assertTrue(TypoVariant.generateQwertyVariants("しんずく").contains("しんじく"))
        assertTrue(TypoVariant.generateQwertyVariants("あいうえおかきくけこ").size <= 8)
    }

    @Test fun indexedSuffixCorrectionKeepsLeadingInput() {
        val index = mapOf("しんじく" to "新宿", "たかはし" to "高橋", "しんじゅく" to "新宿")
        assertEquals("新宿", TypoVariant.findQwertyCorrection("しんずく", index::get)?.surface)
        assertEquals("高橋", TypoVariant.findQwertyCorrection("たかはす", index::get)?.surface)
        val embedded = TypoVariant.findQwertyCorrection("きのうのしんずく", index::get)!!
        assertEquals("きのうの新宿", embedded.surface)
        assertEquals("しんずく", embedded.tokenReading)
        assertNull(TypoVariant.findQwertyCorrection("しんじゅく", index::get))
    }

    @Test fun registeredReadingsRefreshProtectionAndInvalidateOldTickets() {
        try {
            val gate = AssistRequestGate()
            fun key() = AssistRequestGate.Key("にゅいん", 4, "", "", 0, true,
                vocabularyRevision = AssistVocabulary.revision)
            val ticket = gate.capture(key())
            AssistVocabulary.replaceUserEntries(listOf("にゅいん" to "登録語", "しmばし" to "個人表記"))
            assertTrue(AssistVocabulary.isKnownCleanReading("にゅいん"))
            assertNull(TypoCorrectionEngine.findCorrection("にゅいん"))
            assertFalse(gate.isCurrent(ticket, key()))
            AssistVocabulary.replaceUserEntries(emptyList())
            assertFalse(AssistVocabulary.isKnownCleanReading("にゅいん"))
        } finally { AssistVocabulary.replaceUserEntries(emptyList()) }
    }

    @Test fun modelUnavailableUsesNewRulesWithoutAutomaticallyApplyingThem() {
        val offline = object : AssistInference {
            override val revision = 0L
            override fun complete(model: AssistModel, prompt: String, maxTokens: Int, timeoutMs: Int) = ModelCompletion("", "unavailable")
        }
        val result = ModelCorrector(offline, sameReading = { _, _ -> false }).suggest("", "なっかた", "なっかた")!!
        assertEquals("なかった", result.text)
        assertFalse(result.autoApply)
    }

    @Test fun everyRegisteredSpellingKeepsItsReadingAndInvalidatesRemovedReverseEntries() {
        try {
            AssistVocabulary.replaceUserEntries(listOf("ぜろこん" to "零魂", "ぜろこん" to "零婚", "れいこん" to "零魂"))
            assertEquals(listOf("ゼロコン"), AssistVocabulary.userReadings()["零婚"])
            assertEquals(listOf("ゼロコン", "レイコン"), AssistVocabulary.userReadings()["零魂"])
            val before = AssistVocabulary.revision
            AssistVocabulary.replaceUserEntries(listOf("ぜろこん" to "零魂", "れいこん" to "零魂"))
            assertNull(AssistVocabulary.userReadings()["零婚"])
            assertTrue(AssistVocabulary.revision > before)
        } finally { AssistVocabulary.replaceUserEntries(emptyList()) }
    }
}
