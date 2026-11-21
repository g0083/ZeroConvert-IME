package com.zeroconvert.ime.benchmark

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class BenchmarkRunnerTest {

    private fun asset(path: String): File {
        val candidates = listOf(
            File("src/main/assets/$path"),
            File("../app/src/main/assets/$path")
        )
        return candidates.firstOrNull { it.exists() }
            ?: error("benchmark asset not found: $path")
    }

    @Test
    fun latestBenchmarkDatasetsMatchWindowsCountsAndSchema() {
        val conversion = asset("benchmark_dataset.jsonl").readLines().filter { it.isNotBlank() }
        assertEquals(740, conversion.size)
        assertEquals(230, conversion.count { it.contains("\"subset\":\"basic\"") })
        assertEquals(250, conversion.count { it.contains("\"subset\":\"hard\"") })
        assertEquals(160, conversion.count { it.contains("\"subset\":\"realistic\"") })
        assertEquals(100, conversion.count { it.contains("\"subset\":\"colloquial\"") })
        assertTrue(conversion.first().contains("\"subset_label\""))

        val typo = asset("typo_testset.jsonl").readLines().filter { it.isNotBlank() }
        assertEquals(450, typo.size)
        assertEquals(255, typo.count { it.contains("\"is_clean\":false") })
        assertEquals(195, typo.count { it.contains("\"is_clean\":true") })
        assertTrue(typo.first().contains("\"input_reading\""))
        assertTrue(typo.first().contains("\"typo_type_label\""))
        assertTrue(typo.first().contains("\"input_mode\""))
        assertTrue(typo.first().contains("\"expected_action\""))

        val predictive = asset("predictive_testset.jsonl").readLines().filter { it.isNotBlank() }
        assertEquals(30, predictive.size)
    }

    @Test
    fun commonScorerMatchesParityCases() {
        // benchmarks/common/parity_cases.jsonl の抜粋。Rust と同一判定であることを固定する。
        val conv = BenchmarkRunner.scoreConversion("記者が取材する", "記者が取材する", emptyList(), emptyList())
        assertTrue(conv.isCorrect)
        assertEquals("exact", conv.reason)
        val kana = BenchmarkRunner.scoreConversion("今日は天気がイイ", "今日は天気がいい", emptyList(), emptyList())
        assertTrue(kana.isCorrect)
        assertEquals("kana", kana.reason)
        val forbidden = BenchmarkRunner.scoreConversion(
            "記者が駅に到着した", "汽車が駅に到着した", emptyList(), listOf("記者が駅に到着した")
        )
        assertFalse(forbidden.isCorrect)
        assertEquals("forbidden", forbidden.reason)

        val typoItem = TypoBenchmarkItem(
            id = 1, category = "business", typoType = "missing_vowel", typoTypeLabel = "母音抜け",
            isClean = false, context = "", inputReading = "ゴカクニンクダサ",
            targetReading = "ごかくにんください", expected = "ご確認ください", acceptable = emptyList()
        )
        val exact = BenchmarkRunner.scoreTypo(typoItem, "ご確認ください", null)
        assertEquals(TypoStatus.EXACT_MATCH, exact.status)
        assertFalse(exact.suggestionExact)
        val variant = BenchmarkRunner.scoreTypo(typoItem, "ご確認ください。", null)
        assertEquals(TypoStatus.VARIANT_MATCH, variant.status)

        val cleanItem = TypoBenchmarkItem(
            id = 12, category = "business", typoType = "clean", typoTypeLabel = "正常入力 (FP検証)",
            isClean = true, context = "", inputReading = "ケントウイタシマス",
            targetReading = "けんとういたします", expected = "検討いたします", acceptable = emptyList()
        )
        assertEquals(TypoStatus.FALSE_POSITIVE, BenchmarkRunner.scoreTypo(cleanItem, "検討いたします", "原因を検討します").status)
        assertEquals(TypoStatus.CLEAN_PRESERVED, BenchmarkRunner.scoreTypo(cleanItem, "検討いたします", null).status)

        assertTrue(BenchmarkRunner.scorePredictive("よろしくお願いします。", "よろしくお願いいたします。", "いたします。", listOf("いたします", "します。")))
        assertFalse(BenchmarkRunner.scorePredictive(null, "よろしくお願いいたします。", "いたします。", listOf("いたします")))
        // nearest-rank: [10,20,30,40,50] -> (30,50,50)
        assertEquals(Triple(30.0, 50.0, 50.0), BenchmarkRunner.latencyPercentiles(listOf(10.0, 20.0, 30.0, 40.0, 50.0)))
    }

    @Test
    fun typoScoringAcceptsExactAndVariantMatches() {
        val item = TypoBenchmarkItem(
            id = 1,
            category = "business",
            typoType = "missing_vowel",
            typoTypeLabel = "母音抜け",
            isClean = false,
            context = "",
            inputReading = "ゴカクニンクダサ",
            targetReading = "ごかくにんください",
            expected = "ご確認ください",
            acceptable = listOf("ご確認下さい")
        )

        assertTrue(BenchmarkRunner.isTypoCorrectionAccepted(item, "ご確認ください"))
        assertTrue(BenchmarkRunner.isTypoCorrectionAccepted(item, "ご確認下さい"))
        assertTrue(BenchmarkRunner.isTypoCorrectionAccepted(item, "ご確認ください。"))
        assertFalse(BenchmarkRunner.isTypoCorrectionAccepted(item, null))
        assertFalse(BenchmarkRunner.isTypoCorrectionAccepted(item, "ご確認します"))
    }

    @Test
    fun cleanInputIsPreservedOnlyWhenCorrectionDoesNotChangeIt() {
        val item = TypoBenchmarkItem(
            id = 12,
            category = "business",
            typoType = "clean",
            typoTypeLabel = "正常入力 (FP検証)",
            isClean = true,
            context = "",
            inputReading = "ケントウイタシマス",
            targetReading = "けんとういたします",
            expected = "検討いたします",
            acceptable = emptyList()
        )

        assertTrue(BenchmarkRunner.isCleanInputPreserved(item, null))
        assertTrue(BenchmarkRunner.isCleanInputPreserved(item, "検討いたします"))
        assertTrue(BenchmarkRunner.isCleanInputPreserved(item, "ケントウイタシマス"))
        assertFalse(BenchmarkRunner.isCleanInputPreserved(item, "原因を検討します"))
    }
}
