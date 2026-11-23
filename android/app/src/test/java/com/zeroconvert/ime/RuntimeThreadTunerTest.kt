package com.zeroconvert.ime

import com.zeroconvert.ime.engine.CpuTopology
import com.zeroconvert.ime.engine.RuntimeThreadTuner
import com.zeroconvert.ime.engine.TuningCandidate
import com.zeroconvert.ime.engine.TuningMeasurement
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class RuntimeThreadTunerTest {

    @Test
    fun parseTopologyDetectsPrimePlusPerfClustersFromMaxFreq() {
        val topology = RuntimeThreadTuner.parseTopology(
            perCoreMaxFreqKHz = listOf(
                3532800, 3532800, 3532800, 3532800, 3532800, 3532800,
                4320000, 4320000,
            ),
            perCoreCapacity = emptyList(),
            availableProcessors = 8,
        )
        assertEquals("sysfs_freq", topology.source)
        assertEquals(8, topology.logicalCores)
        assertEquals(listOf(2, 6), topology.clusterSizesDescendingFreq)
        assertEquals(2, topology.topPerformanceCores)
        assertEquals("sysfs_freq:8:2+6", topology.signature)
    }

    @Test
    fun parseTopologyDetectsFourPlusFourAndOnePlusThreePlusFour() {
        val fourPlusFour = RuntimeThreadTuner.parseTopology(
            perCoreMaxFreqKHz = listOf(2000000, 2000000, 2000000, 2000000, 2600000, 2600000, 2600000, 2600000),
            perCoreCapacity = emptyList(),
            availableProcessors = 8,
        )
        assertEquals(listOf(4, 4), fourPlusFour.clusterSizesDescendingFreq)
        assertEquals(4, fourPlusFour.topPerformanceCores)

        val triCluster = RuntimeThreadTuner.parseTopology(
            perCoreMaxFreqKHz = listOf(2270000, 2270000, 2270000, 2270000, 3150000, 3150000, 3150000, 3300000),
            perCoreCapacity = emptyList(),
            availableProcessors = 8,
        )
        assertEquals(listOf(1, 3, 4), triCluster.clusterSizesDescendingFreq)
        assertEquals(1, triCluster.topPerformanceCores)
    }

    @Test
    fun parseTopologyFallsBackFromFreqToCapacityThenProcessorCount() {
        val fromCapacity = RuntimeThreadTuner.parseTopology(
            perCoreMaxFreqKHz = listOf(null, null, null, null),
            perCoreCapacity = listOf(512, 512, 1024, 1024),
            availableProcessors = 4,
        )
        assertEquals("sysfs_capacity", fromCapacity.source)
        assertEquals(listOf(2, 2), fromCapacity.clusterSizesDescendingFreq)

        val fallback = RuntimeThreadTuner.parseTopology(
            perCoreMaxFreqKHz = emptyList(),
            perCoreCapacity = emptyList(),
            availableProcessors = 6,
        )
        assertEquals("processor_count", fallback.source)
        assertEquals(6, fallback.logicalCores)
        assertEquals(listOf(6), fallback.clusterSizesDescendingFreq)
        assertEquals(6, fallback.topPerformanceCores)
    }

    @Test
    fun parsePresentCpuCountHandlesRanges() {
        assertEquals(8, RuntimeThreadTuner.parsePresentCpuCount("0-7", 4))
        assertEquals(8, RuntimeThreadTuner.parsePresentCpuCount("0-3,4-7", 2))
        assertEquals(4, RuntimeThreadTuner.parsePresentCpuCount(null, 4))
    }

    @Test
    fun candidateConfigsStayWithinTwoToFourThreadsAndClampLowCoreDevices() {
        val octa = CpuTopology(8, listOf(2, 6), listOf(4320000, 3532800), "sysfs_freq")
        val octaConfigs = RuntimeThreadTuner.candidateConfigs(octa, 32L * 1024 * 1024)
        assertEquals(8, octaConfigs.size)
        assertEquals(listOf(2, 2, 3, 3, 4, 4, 4, 4), octaConfigs.map { it.decodeThreads })
        assertEquals(listOf(2, 2, 3, 3, 4, 4, 6, 6), octaConfigs.map { it.batchThreads })
        assertTrue(octaConfigs.any { it.decodeThreads == 2 && it.directModelFirst })
        assertTrue(octaConfigs.any { it.decodeThreads == 4 && it.batchThreads == 6 && !it.directModelFirst })

        val quad = CpuTopology(4, listOf(4), listOf(2000000), "sysfs_freq")
        val quadConfigs = RuntimeThreadTuner.candidateConfigs(quad, 32L * 1024 * 1024)
        assertEquals(6, quadConfigs.size)
        assertEquals(listOf(2, 2, 3, 3, 4, 4), quadConfigs.map { it.batchThreads })

        val dual = CpuTopology(2, listOf(2), listOf(2000000), "sysfs_freq")
        val dualConfigs = RuntimeThreadTuner.candidateConfigs(dual, 32L * 1024 * 1024)
        assertEquals(2, dualConfigs.size)
        assertEquals(listOf(2, 2), dualConfigs.map { it.decodeThreads })
    }

    @Test
    fun selectBestAppliesFivePercentFewerThreadTieBreaker() {
        val winnerWithinFivePercent = RuntimeThreadTuner.selectBest(
            listOf(
                TuningMeasurement(decodeThreads = 2, batchThreads = 2, directModelFirst = true, meanNs = 12_500_000L, completedSamples = 2),
                TuningMeasurement(decodeThreads = 3, batchThreads = 3, directModelFirst = true, meanNs = 12_200_000L, completedSamples = 2),
                TuningMeasurement(decodeThreads = 4, batchThreads = 4, directModelFirst = true, meanNs = 12_000_000L, completedSamples = 2),
            )
        )
        assertNotNull(winnerWithinFivePercent)
        assertEquals(2, winnerWithinFivePercent?.decodeThreads)

        val winnerSmallerBatchWithinFivePercent = RuntimeThreadTuner.selectBest(
            listOf(
                TuningMeasurement(decodeThreads = 4, batchThreads = 4, directModelFirst = true, meanNs = 12_400_000L, completedSamples = 3),
                TuningMeasurement(decodeThreads = 4, batchThreads = 6, directModelFirst = true, meanNs = 12_000_000L, completedSamples = 3),
            )
        )
        assertEquals(4, winnerSmallerBatchWithinFivePercent?.batchThreads)

        val winnerLargerBatchClearlyFaster = RuntimeThreadTuner.selectBest(
            listOf(
                TuningMeasurement(decodeThreads = 4, batchThreads = 4, directModelFirst = true, meanNs = 15_000_000L, completedSamples = 3),
                TuningMeasurement(decodeThreads = 4, batchThreads = 6, directModelFirst = true, meanNs = 12_000_000L, completedSamples = 3),
            )
        )
        assertEquals(6, winnerLargerBatchClearlyFaster?.batchThreads)

        val winnerClearlyFaster = RuntimeThreadTuner.selectBest(
            listOf(
                TuningMeasurement(decodeThreads = 2, batchThreads = 2, directModelFirst = true, meanNs = 15_000_000L, completedSamples = 2),
                TuningMeasurement(decodeThreads = 4, batchThreads = 4, directModelFirst = false, meanNs = 12_000_000L, completedSamples = 2),
                TuningMeasurement(decodeThreads = 1, batchThreads = 1, directModelFirst = false, meanNs = 1_000_000L, completedSamples = 0),
            )
        )
        assertEquals(4, winnerClearlyFaster?.decodeThreads)
        assertEquals(false, winnerClearlyFaster?.directModelFirst)

        assertNull(
            RuntimeThreadTuner.selectBest(
                listOf(TuningMeasurement(2, 2, true, 0L, 0))
            )
        )
    }

    @Test
    fun defaultFallbackAndCacheKeyAndJsonRoundTripAreDeterministic() {
        val twoPlusSix = CpuTopology(8, listOf(2, 6), listOf(4320000, 3532800), "sysfs_freq")
        val fbSmall = RuntimeThreadTuner.defaultFallback(twoPlusSix, 31L * 1024 * 1024)
        assertEquals(2, fbSmall.decodeThreads)
        assertEquals(2, fbSmall.batchThreads)
        assertEquals(true, fbSmall.directModelFirst)

        val onePlusThreePlusFour = CpuTopology(8, listOf(1, 3, 4), listOf(3300000, 3150000, 2270000), "sysfs_freq")
        val fbLarge = RuntimeThreadTuner.defaultFallback(onePlusThreePlusFour, 55L * 1024 * 1024)
        assertEquals(4, fbLarge.decodeThreads)
        assertEquals(false, fbLarge.directModelFirst)

        val k1 = RuntimeThreadTuner.cacheKey("rev1", twoPlusSix.signature, "nano.gguf", 32000000L, appVersionCode = 19)
        val k2 = RuntimeThreadTuner.cacheKey("rev2", twoPlusSix.signature, "nano.gguf", 32000000L, appVersionCode = 19)
        val k3 = RuntimeThreadTuner.cacheKey("rev1", twoPlusSix.signature, "nano.gguf", 32000001L, appVersionCode = 19)
        val k4 = RuntimeThreadTuner.cacheKey("rev1", twoPlusSix.signature, "nano.gguf", 32000000L, appVersionCode = 20)
        assertNotEquals(k1, k2)
        assertNotEquals(k1, k3)
        assertNotEquals(k1, k4)
        assertTrue(k1.startsWith("v${RuntimeThreadTuner.TUNING_SCHEMA_VERSION}|19|rev1|"))

        val json = RuntimeThreadTuner.configsToJsonArray(
            listOf(TuningCandidate(decodeThreads = 3, batchThreads = 3, directModelFirst = true))
        )
        assertEquals(1, json.length())
        assertEquals(3, json.getJSONObject(0).getInt("decodeThreads"))
        assertEquals(3, json.getJSONObject(0).getInt("batchThreads"))
        assertEquals(true, json.getJSONObject(0).getBoolean("directModelFirst"))

        val parsed = RuntimeThreadTuner.parseMeasurements(
            """[{"decodeThreads":3,"batchThreads":3,"directModelFirst":true,"meanNs":14500000,"completedSamples":2}]"""
        )
        assertEquals(1, parsed.size)
        assertEquals(14_500_000L, parsed[0].meanNs)
    }
}
