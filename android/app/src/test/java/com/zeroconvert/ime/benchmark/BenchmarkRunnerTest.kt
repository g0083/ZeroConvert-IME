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
        assertEquals(480, conversion.size)
        assertEquals(230, conversion.count { it.contains("\"subset\":\"basic\"") })
        assertEquals(250, conversion.count { it.contains("\"subset\":\"hard\"") })
        assertTrue(conversion.first().contains("\"subset_label\""))

        val typo = asset("typo_testset.jsonl").readLines().filter { it.isNotBlank() }
        assertEquals(300, typo.size)
        assertEquals(255, typo.count { it.contains("\"is_clean\":false") })
        assertEquals(45, typo.count { it.contains("\"is_clean\":true") })
        assertTrue(typo.first().contains("\"input_reading\""))
        assertTrue(typo.first().contains("\"typo_type_label\""))
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
