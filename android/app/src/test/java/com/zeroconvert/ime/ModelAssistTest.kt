package com.zeroconvert.ime

import com.zeroconvert.ime.assist.*
import com.zeroconvert.ime.model.DictionaryDownloadManager
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Assume.assumeNotNull
import org.junit.Test
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.file.Files
import java.util.Collections

class ModelAssistTest {
    private class Fixture(private val reply: (AssistModel, String) -> ModelCompletion) : AssistInference {
        override val revision = 1L
        val calls = Collections.synchronizedList(mutableListOf<Pair<AssistModel, String>>())
        override fun complete(model: AssistModel, prompt: String, maxTokens: Int, timeoutMs: Int): ModelCompletion {
            assertTrue(maxTokens in 1..384)
            assertTrue(timeoutMs in 1..2000)
            calls.add(model to prompt)
            return reply(model, prompt)
        }
    }
    private fun prediction(first: ModelCompletion, second: ModelCompletion): Pair<AiAssistResult?, Fixture> {
        val fixture = Fixture { _, prompt -> if (prompt.endsWith('\uEE01')) second else first }
        return ModelPredictor(fixture).suggest("文脈", "よろしくおねがい", "よろしくお願い") to fixture
    }

    @Test fun extendsReadingThenConvertsWholeReadingAndReturnsOnlySuffix() {
        val (result, fixture) = prediction(ModelCompletion("イタシマス。", "eos"), ModelCompletion("よろしくお願いいたします。", "eos"))
        assertEquals("いたします。", result!!.continuation)
        assertEquals("よろしくお願いいたします。", result.text)
        assertEquals(60, result.relevance)
        assertEquals("\uEE02文脈\uEE00ヨロシクオネガイ", fixture.calls[0].second)
        assertEquals("\uEE02文脈\uEE00ヨロシクオネガイイタシマス。\uEE01", fixture.calls[1].second)
        assertTrue(fixture.calls.all { it.first == AssistModel.PREDICTION })
    }

    @Test fun refusesPrefixChangesAndUnfinishedSurfaceEvenWhenReadingFinished() {
        for (surface in listOf(ModelCompletion("宜しくお願いいたします。", "eos"), ModelCompletion("よろしくお願いいたし", "limit"))) {
            assertNull(prediction(ModelCompletion("イタシマス。", "eos"), surface).first)
        }
    }

    @Test fun truncatedReadingNeedsACompletedClauseAndRejectsTimeouts() {
        assertNotNull(prediction(ModelCompletion("イタシマス。トチュウ", "limit"), ModelCompletion("よろしくお願いいたします。", "eos")).first)
        val (fragment, fixture) = prediction(ModelCompletion("イタシ", "limit"), ModelCompletion("よろしくお願いいたします。", "eos"))
        assertNull(fragment)
        assertEquals(1, fixture.calls.size)
        assertNull(prediction(ModelCompletion("イタシマス。", "timeout"), ModelCompletion("よろしくお願いいたします。", "eos")).first)
    }

    @Test fun refusesUnsupportedReferencesSpecialTokensAndRepetition() {
        for (text in listOf(
            "よろしくお願いこの記事をご覧ください。",
            "よろしくお願いよろしくお願い",
            "よろしくお願い\uEE00本文"
        )) {
            assertNull(prediction(ModelCompletion("イタシマス。", "eos"), ModelCompletion(text, "eos")).first)
        }
        val clauseFixture = Fixture { _, prompt ->
            if (prompt.endsWith('\uEE01')) {
                ModelCompletion("今日は雨が降っているので、雨が降っているので、", "eos")
            } else {
                ModelCompletion("、アメガフッテイルノデ、", "limit")
            }
        }
        assertNull(ModelPredictor(clauseFixture).suggest("", "きょうはあめがふっているので", "今日は雨が降っているので"))
        assertTrue(repeatsSurfaceClause("今日は雨が降っているので", "、雨が降っているので、"))
        assertNull(validatePredictionSuffix("", "今日は雨が降っているので", "今日は雨が降っているので、雨が降っているので、"))

        assertFalse(repeatsSurfaceClause("雨が降っているので", "、電車が遅れているので、遅刻します。"))
        assertEquals(
            "、電車が遅れているので、遅刻します。",
            validatePredictionSuffix("", "雨が降っているので", "雨が降っているので、電車が遅れているので、遅刻します。")
        )
        val validContinuation = Fixture { _, prompt ->
            if (prompt.endsWith('\uEE01')) {
                ModelCompletion("雨が降っているので、電車が遅れているので、遅刻します。", "eos")
            } else {
                ModelCompletion("、デンシャガオクレテイルノデ、チコクシマス。", "eos")
            }
        }
        if (!BuildConfig.WINDOWS_ENGINE) {
            assertNotNull(ModelPredictor(validContinuation).suggest("", "あめがふっているので", "雨が降っているので"))
        }
    }

    @Test fun unfinishedRomajiAndUnknownDirectReadingNeverStartInference() {
        val fixture = Fixture { _, _ -> error("must not infer") }
        assertNull(ModelPredictor(fixture).suggest("", "よろしくk", "よろしく"))
        assertNull(ModelPredictor(fixture) { null }.suggest("", "未知の表記", "未知の表記"))
    }

    @Test fun directSurfaceRestoresReadingForPrediction() {
        val fixture = Fixture { _, prompt -> if (prompt.endsWith('\uEE01')) ModelCompletion("学校へ行きます。", "eos") else ModelCompletion("ヘイキマス。", "eos") }
        assertEquals("学校へ行きます。", ModelPredictor(fixture) { "がっこう" }.suggest("", "学校", "学校")!!.text)
        assertEquals("\uEE02\uEE00ガッコウ", fixture.calls.first().second)
    }

    @Test fun completedCorrectorPreemptsFuzzyRulesAndUnchangedOutputVetoesThem() {
        val input = "おつくれさま"
        val changed = Fixture { model, _ -> assertEquals(AssistModel.CORRECTOR, model); ModelCompletion("お疲れ様", "eos") }
        assertEquals("お疲れ様", ModelCorrector(changed, { _, _ -> false }, { error("must not use fallback") }).suggest("", input, input)!!.text)
        val unchanged = Fixture { _, _ -> ModelCompletion("オツクレサマ", "eos") }
        assertNull(ModelCorrector(unchanged, { _, _ -> false }, { error("unchanged response vetoes rules") }).suggest("", input, input))
    }

    @Test fun sameReadingIsNormalConversionAndKnownCleanInputDoesNotInfer() {
        val fixture = Fixture { _, _ -> ModelCompletion("設定", "eos") }
        assertNull(ModelCorrector(fixture, { _, _ -> true }).suggest("", "せっってい", "せっってい"))
        val clean = Fixture { _, _ -> error("clean reading") }
        assertNull(ModelCorrector(clean).suggest("", "こんにちは", "こんにちは"))
    }

    @Test fun longCorrectionKeepsConvertedPrefixAtAlignedPunctuation() {
        val prefix = "あ".repeat(130) + "。"
        val baselinePrefix = "亜".repeat(130) + "。"
        val fixture = Fixture { _, prompt -> assertTrue(prompt.endsWith("\uEE00オツクレサマ\uEE01")); ModelCompletion("お疲れ様", "eos") }
        assertEquals(baselinePrefix + "お疲れ様", ModelCorrector(fixture, { _, _ -> false }).suggest("前文", prefix + "おつくれさま", baselinePrefix + "おつくれ様")!!.text)
        assertNull(ModelCorrector(fixture).suggest("", "あ".repeat(140), "亜".repeat(140)))
    }

    @Test fun correctionAndPredictionUseIndependentModelsAndWeakCorrectionCannotHidePrediction() = runBlocking {
        val fixture = Fixture { model, prompt ->
            when {
                model == AssistModel.CORRECTOR -> ModelCompletion("お疲れ様", "eos")
                prompt.endsWith('\uEE01') -> ModelCompletion("おつくれ様でした。", "eos")
                else -> ModelCompletion("デシタ。", "eos")
            }
        }
        val result = AiAssistManager.requestAiAssist("unused", "おつくれさま", "おつくれ様", "", inference = fixture)
        assertEquals(AiAssistType.PREDICTION, result!!.type)
        assertEquals(setOf(AssistModel.CORRECTOR, AssistModel.PREDICTION), fixture.calls.map { it.first }.toSet())
        val strong = AiAssistResult(AiAssistType.TYPO_CORRECTION, "学習", "", 70)
        assertEquals(strong, AiAssistManager.choose(strong, result, RustAssistPolicy))
    }

    private fun dictionary(): ReverseReadingDictionary {
        val entries = listOf("今日" to "キョウ", "今日" to "コンニチ", "学校" to "ガッコウ")
        val records = entries.map { (word, reading) ->
            val w = word.toByteArray(); val r = reading.toByteArray()
            ByteBuffer.allocate(8 + w.size + r.size).order(ByteOrder.LITTLE_ENDIAN).apply {
                putShort(w.size.toShort()); putShort(r.size.toShort()); putFloat(1f); put(w); put(r)
            }.array()
        }
        val header = 12 + records.size * 4
        val buffer = ByteBuffer.allocate(header + records.sumOf { it.size }).order(ByteOrder.LITTLE_ENDIAN)
        buffer.put("AZR2".toByteArray()); buffer.putInt(records.size)
        var offset = header
        for (record in records) { buffer.putInt(offset); offset += record.size }
        buffer.putInt(offset)
        records.forEach { buffer.put(it) }
        return ReverseReadingDictionary(buffer.array())
    }

    @Test fun reverseIndexChecksAllHomophonesAndRestoresMixedSurface() {
        val dictionary = dictionary()
        assertTrue(dictionary.matches("今日", "きょう"))
        assertTrue(dictionary.matches("今日", "こんにち"))
        assertFalse(dictionary.matches("今日", "あした"))
        assertEquals("ガッコウヘ", dictionary.restore("学校へ"))
        assertNull(dictionary.restore("未登録漢字"))
        assertTrue(dictionary.matches("学校へ", "がっこうへ"))
    }

    @Test fun malformedReverseDictionaryIsRejected() {
        assertThrows(IllegalArgumentException::class.java) { ReverseReadingDictionary(ByteArray(12)) }
    }
    @Test fun userDictionaryReadingsWorkInsideMixedSurfacesAndTakePriority() {
        val dictionary = dictionary()
        val users = mapOf("零魂" to listOf("ゼロコン"), "今日" to listOf("キョー"))
        assertEquals("ゼロコンヘ", dictionary.restore("零魂へ", users))
        assertTrue(dictionary.matches("零魂へ", "ぜろこんへ", users))
        assertFalse(dictionary.matches("零魂へ", "れいこんへ", users))
        assertEquals("キョー", dictionary.restore("今日", users))
        assertTrue(dictionary.matches("今日", "きょう", users))
        assertNull(dictionary.restore("零魂へ"))
    }

    @Test fun packagedReverseDictionarySupportsRealHomophonesAndCompoundWords() {
        val dictionary = ReverseReadingDictionary.map(TestAssets.file("dictionary/reverse-v2.bin"))
        assertTrue(dictionary.matches("今日", "きょう"))
        assertTrue(dictionary.matches("今日", "こんにち"))
        assertTrue(dictionary.matches("統一", "とういつ"))
        // Individual kanji can also carry valid proper-name readings in this dictionary.
        assertFalse(dictionary.matches("統一", "とういっつ"))
        assertEquals("トウイツ", dictionary.restore("統一"))
        assertEquals("ガッコウヘ", dictionary.restore("学校へ"))
    }

    @Test fun downloadedDictionaryRequiresTheCurrentAssetHashAndContents() {
        val root = Files.createTempDirectory("dictionary-test").toFile()
        try {
            val marker = root.resolve(".complete")
            val characters = root.resolve("Dictionary/louds/charID.chid")
            characters.parentFile?.mkdirs()
            characters.writeText("charset")
            assertFalse(DictionaryDownloadManager.isReadyAt(root))
            marker.writeText("9f406888dd17b8e9ab1aff60891a1786f40966898f90368d9e6e62d30d59d29b")
            assertFalse(DictionaryDownloadManager.isReadyAt(root))
            marker.writeText(DictionaryDownloadManager.SHA256)
            assertTrue(DictionaryDownloadManager.isReadyAt(root))
            characters.delete()
            assertFalse(DictionaryDownloadManager.isReadyAt(root))
        } finally {
            root.deleteRecursively()
        }
    }

    @Test fun expandedAddressIndexReadsChomeAndKoazaFromTheReleaseAsset() {
        val path = System.getenv("ZEROCON_ANDROID_DICTIONARY_INDEX")
        assumeNotNull(path)
        val dictionary = ReverseReadingDictionary.map(File(requireNotNull(path)))
        assertTrue(dictionary.matches("大通西１１丁目", "おおどおりにしじゅういっちょうめ"))
        assertTrue(dictionary.matches("大字留萌村字トイヨイ", "おおあざるもいむらあざといよい"))
    }

    @Test fun cancelledCorrectionDoesNotLaunchAnOfflineDictionarySearch() {
        val fixture = Fixture { _, _ -> ModelCompletion("", "cancelled") }
        assertNull(ModelCorrector(fixture, { _, _ -> false }, { error("stale fallback must not run") })
            .suggest("", "おつくれさま", "おつくれさま"))
    }

    @Test fun multilineContextIsBoundedAndNormalizedWithoutSuppressingPrediction() {
        val fixture = Fixture { _, prompt ->
            assertFalse(prompt.contains('\n'))
            if (prompt.endsWith('\uEE01')) ModelCompletion("学校へ行きます。", "eos") else ModelCompletion("ヘイキマス。", "eos")
        }
        assertNotNull(ModelPredictor(fixture).suggest("前文\n続き", "がっこう", "学校"))
    }

    @Test fun namMaskingMasksPromptsAndRestoresDigitsWithoutCorruptingEnglish() {
        val masked = NamMasking.mask("１００えんのあめ")
        assertEquals("namえんのあめ", masked.text)
        assertEquals(listOf("100"), masked.digits)
        assertEquals("100円の飴", NamMasking.restore("nam円の飴", masked.digits))
        assertEquals("SELECT name FROM customers", NamMasking.restore("SELECT name FROM customers", listOf("10")))
        assertEquals("SELECT nameです dynamic", NamMasking.restore("SELECT nameです dynamic", listOf("10")))
        assertEquals("3bonまとめて持つ", NamMasking.restore("nambonまとめて持つ", listOf("3")))
        assertEquals("Wi-Fi 6EとBluetooth 5.4", NamMasking.restore("Wi-Fi namEとBluetooth nam.nam", listOf("6", "5", "4")))

        if (!BuildConfig.WINDOWS_ENGINE) {
            val seenCorrectorPrompts = mutableListOf<String>()
            val correctorFixture = Fixture { _, prompt ->
                seenCorrectorPrompts += prompt
                ModelCompletion("nam円", "eos")
            }
            val correction = ModelCorrector(correctorFixture, { _, _ -> false }, { null })
                .suggest("２０２６ねんに", "１００えん", "１００えん")
            assertEquals("100円", correction?.text)
            assertEquals(listOf("\uEE02namねんに\uEE00namエン\uEE01"), seenCorrectorPrompts)

            val seenPredictorPrompts = mutableListOf<String>()
            val predictorFixture = Fixture { _, prompt ->
                seenPredictorPrompts += prompt
                if (prompt.endsWith('\uEE01')) ModelCompletion("nam月に開催", "eos") else ModelCompletion("ニカイサイ", "eos")
            }
            val prediction = ModelPredictor(predictorFixture).suggest("２０２６ねん", "4がつ", "4月")
            assertEquals("4月に開催", prediction?.text)
            assertEquals(listOf("\uEE02namねん\uEE00namガツ", "\uEE02namねん\uEE00namガツニカイサイ\uEE01"), seenPredictorPrompts)
        }
    }
}
