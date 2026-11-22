package com.zeroconvert.ime.benchmark

import org.junit.Assert.assertEquals
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

}
