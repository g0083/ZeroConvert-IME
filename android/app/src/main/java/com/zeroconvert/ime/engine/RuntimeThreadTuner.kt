package com.zeroconvert.ime.engine

import android.content.Context
import android.util.Log
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.security.MessageDigest
import kotlin.math.abs

internal data class CpuTopology(
    val logicalCores: Int,
    val clusterSizesDescendingFreq: List<Int>,
    val clusterMaxFreqKHz: List<Int>,
    val source: String,
) {
    val topPerformanceCores: Int
        get() = clusterSizesDescendingFreq.firstOrNull() ?: logicalCores

    val signature: String
        get() = "$source:$logicalCores:${clusterSizesDescendingFreq.joinToString("+")}"
}

internal data class TuningCandidate(
    val decodeThreads: Int,
    val batchThreads: Int?,
    val directModelFirst: Boolean,
)

internal data class TuningMeasurement(
    val decodeThreads: Int,
    val batchThreads: Int?,
    val directModelFirst: Boolean,
    val meanNs: Long,
    val completedSamples: Int,
)

internal data class TuningDecision(
    val decodeThreads: Int,
    val batchThreads: Int?,
    val directModelFirst: Boolean,
    val meanNs: Long,
    val measuredConfigs: Int,
    val source: String,
)

/**
 * Generic sysfs topology discovery, thread-count/mode probing, selection, and
 * persistent caching for Android Zenzai execution without SoC-specific rules.
 */
internal object RuntimeThreadTuner {
    private const val TAG = "RuntimeThreadTuner"
    private const val PREFS_NAME = "runtime_tuning_prefs"
    internal const val TUNING_SCHEMA_VERSION = 2
    private const val MODEL_FIRST_DEFAULT_THRESHOLD_BYTES = 42L * 1024L * 1024L
    private val probeLock = Any()

    fun readTopology(): CpuTopology {
        val available = Runtime.getRuntime().availableProcessors().coerceAtLeast(1)
        val presentRaw = runCatching { File("/sys/devices/system/cpu/present").readText() }.getOrNull()
        val coreCount = parsePresentCpuCount(presentRaw, available)
        val freqs = (0 until coreCount).map { index ->
            runCatching {
                File("/sys/devices/system/cpu/cpu$index/cpufreq/cpuinfo_max_freq")
                    .readText()
                    .trim()
                    .toIntOrNull()
            }.getOrNull()
        }
        val capacities = (0 until coreCount).map { index ->
            runCatching {
                File("/sys/devices/system/cpu/cpu$index/cpu_capacity")
                    .readText()
                    .trim()
                    .toIntOrNull()
            }.getOrNull()
        }
        return parseTopology(freqs, capacities, coreCount)
    }

    internal fun parsePresentCpuCount(raw: String?, availableProcessors: Int): Int {
        val fallback = availableProcessors.coerceAtLeast(1)
        if (raw.isNullOrBlank()) return fallback
        var total = 0
        for (part in raw.trim().split(',')) {
            val trimmed = part.trim()
            if (trimmed.isEmpty()) continue
            val dash = trimmed.indexOf('-')
            if (dash < 0) {
                if (trimmed.toIntOrNull() != null) total += 1
            } else {
                val start = trimmed.substring(0, dash).toIntOrNull() ?: continue
                val end = trimmed.substring(dash + 1).toIntOrNull() ?: continue
                if (end >= start) total += (end - start + 1)
            }
        }
        return maxOf(total, fallback)
    }

    internal fun parseTopology(
        perCoreMaxFreqKHz: List<Int?>,
        perCoreCapacity: List<Int?>,
        availableProcessors: Int,
    ): CpuTopology {
        val freqs = perCoreMaxFreqKHz.mapNotNull { it?.takeIf { khz -> khz > 0 } }
        if (freqs.isNotEmpty()) {
            val clusters = groupDescendingWithinTolerance(freqs)
            val logicalCores = maxOf(perCoreMaxFreqKHz.size, availableProcessors).coerceAtLeast(1)
            return CpuTopology(
                logicalCores = logicalCores,
                clusterSizesDescendingFreq = clusters.map { it.size },
                clusterMaxFreqKHz = clusters.map { it.first() },
                source = "sysfs_freq",
            )
        }
        val capacities = perCoreCapacity.mapNotNull { it?.takeIf { cap -> cap > 0 } }
        if (capacities.isNotEmpty()) {
            val clusters = groupDescendingWithinTolerance(capacities)
            val logicalCores = maxOf(perCoreCapacity.size, availableProcessors).coerceAtLeast(1)
            return CpuTopology(
                logicalCores = logicalCores,
                clusterSizesDescendingFreq = clusters.map { it.size },
                clusterMaxFreqKHz = emptyList(),
                source = "sysfs_capacity",
            )
        }
        val cores = availableProcessors.coerceAtLeast(1)
        return CpuTopology(
            logicalCores = cores,
            clusterSizesDescendingFreq = listOf(cores),
            clusterMaxFreqKHz = emptyList(),
            source = "processor_count",
        )
    }

    private fun groupDescendingWithinTolerance(values: List<Int>): List<List<Int>> {
        val sorted = values.sortedDescending()
        val clusters = mutableListOf<MutableList<Int>>()
        for (value in sorted) {
            val current = clusters.lastOrNull()
            if (current == null) {
                clusters.add(mutableListOf(value))
                continue
            }
            val rep = current.first()
            if (abs(rep - value) <= maxOf(1, rep / 100)) {
                current.add(value)
            } else {
                clusters.add(mutableListOf(value))
            }
        }
        return clusters
    }

    fun defaultFallback(topology: CpuTopology, modelSizeBytes: Long): TuningDecision {
        val preferred = if (topology.topPerformanceCores >= 2) {
            topology.topPerformanceCores
        } else {
            topology.clusterSizesDescendingFreq.take(2).sum().coerceAtLeast(topology.topPerformanceCores)
        }
        val threads = if (topology.logicalCores <= 1) {
            1
        } else {
            preferred.coerceIn(2, minOf(4, topology.logicalCores))
        }
        val directModelFirst = modelSizeBytes in 1L until MODEL_FIRST_DEFAULT_THRESHOLD_BYTES
        return TuningDecision(
            decodeThreads = threads,
            batchThreads = threads,
            directModelFirst = directModelFirst,
            meanNs = 0L,
            measuredConfigs = 0,
            source = "fallback",
        )
    }

    fun candidateConfigs(topology: CpuTopology, modelSizeBytes: Long): List<TuningCandidate> {
        val cores = topology.logicalCores.coerceAtLeast(1)
        val threadCounts = (2..4)
            .map { it.coerceIn(1, cores) }
            .distinct()
        val preferredDirectFirst = modelSizeBytes in 1L until MODEL_FIRST_DEFAULT_THRESHOLD_BYTES
        val modes = listOf(preferredDirectFirst, !preferredDirectFirst)
        val defaultBatch = minOf(12, maxOf(4, cores - 2))
        val includeDefaultBatch = cores >= 6 && defaultBatch > 4
        return buildList(threadCounts.size * 2 + if (includeDefaultBatch) 2 else 0) {
            for (threads in threadCounts) {
                for (directModelFirst in modes) {
                    add(
                        TuningCandidate(
                            decodeThreads = threads,
                            batchThreads = threads,
                            directModelFirst = directModelFirst,
                        )
                    )
                }
            }
            if (includeDefaultBatch) {
                for (directModelFirst in modes) {
                    add(
                        TuningCandidate(
                            decodeThreads = 4,
                            batchThreads = defaultBatch,
                            directModelFirst = directModelFirst,
                        )
                    )
                }
            }
        }
    }

    fun selectBest(measurements: List<TuningMeasurement>): TuningMeasurement? {
        val valid = measurements.filter { it.completedSamples > 0 && it.meanNs > 0L }
        if (valid.isEmpty()) return null
        val minMeanNs = valid.minOf { it.meanNs }
        val thresholdNs = minMeanNs + minMeanNs / 20L
        return valid
            .filter { it.meanNs <= thresholdNs }
            .minWithOrNull(
                compareBy<TuningMeasurement>(
                    { it.decodeThreads },
                    { it.batchThreads ?: Int.MAX_VALUE },
                    { it.meanNs },
                    { if (it.directModelFirst) 0 else 1 },
                )
            )
    }

    fun configsToJsonArray(configs: List<TuningCandidate>): JSONArray {
        val array = JSONArray()
        for (config in configs) {
            array.put(
                JSONObject()
                    .put("decodeThreads", config.decodeThreads)
                    .apply { config.batchThreads?.let { put("batchThreads", it) } }
                    .put("directModelFirst", config.directModelFirst)
            )
        }
        return array
    }

    fun parseMeasurements(rawJson: String): List<TuningMeasurement> {
        if (rawJson.isBlank() || rawJson == "null") return emptyList()
        val array = runCatching { JSONArray(rawJson) }.getOrNull() ?: return emptyList()
        return parseMeasurements(array)
    }

    fun parseMeasurements(array: JSONArray): List<TuningMeasurement> {
        return buildList(array.length()) {
            for (index in 0 until array.length()) {
                val entry = array.optJSONObject(index) ?: continue
                val decodeThreads = entry.optInt("decodeThreads", -1)
                if (decodeThreads <= 0) continue
                val batchThreads = if (entry.has("batchThreads") && !entry.isNull("batchThreads")) {
                    entry.optInt("batchThreads", -1).takeIf { it > 0 }
                } else {
                    null
                }
                val meanNs = entry.optLong("meanNs", -1L)
                val completedSamples = entry.optInt("completedSamples", 0)
                if (meanNs <= 0L || completedSamples <= 0) continue
                add(
                    TuningMeasurement(
                        decodeThreads = decodeThreads,
                        batchThreads = batchThreads,
                        directModelFirst = entry.optBoolean("directModelFirst", false),
                        meanNs = meanNs,
                        completedSamples = completedSamples,
                    )
                )
            }
        }
    }

    fun cachePrefix(
        engineRevision: String,
        appVersionCode: Int = com.zeroconvert.ime.BuildConfig.VERSION_CODE,
    ): String = "v$TUNING_SCHEMA_VERSION|$appVersionCode|$engineRevision|"

    fun cacheKey(
        engineRevision: String,
        topologySignature: String,
        modelName: String,
        modelSizeBytes: Long,
        appVersionCode: Int = com.zeroconvert.ime.BuildConfig.VERSION_CODE,
    ): String = "${cachePrefix(engineRevision, appVersionCode)}$topologySignature|$modelName:$modelSizeBytes"

    private fun pruneStaleEntries(prefs: android.content.SharedPreferences, currentPrefix: String) {
        val staleKeys = prefs.all.keys.filter { !it.startsWith(currentPrefix) }
        if (staleKeys.isEmpty()) return
        val editor = prefs.edit()
        for (staleKey in staleKeys) {
            editor.remove(staleKey)
        }
        editor.apply()
    }

    fun engineRevision(context: Context): String {
        return runCatching {
            val fingerprint = context.applicationContext.assets
                .open("shared-engine/sources.json")
                .bufferedReader()
                .use { it.readText() }
            MessageDigest.getInstance("SHA-256")
                .digest(fingerprint.toByteArray())
                .joinToString("") { "%02x".format(it) }
        }.getOrDefault("unknown")
    }

    fun loadCached(
        context: Context,
        engineRevision: String,
        topology: CpuTopology,
        model: File,
    ): TuningDecision? {
        val prefs = context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val prefix = cachePrefix(engineRevision)
        val key = cacheKey(engineRevision, topology.signature, model.name, model.length())
        val raw = prefs.getString(key, null)
        if (raw == null) {
            pruneStaleEntries(prefs, prefix)
            return null
        }
        val json = runCatching { JSONObject(raw) }.getOrNull() ?: return null
        val decodeThreads = json.optInt("decodeThreads", -1)
        if (decodeThreads <= 0) return null
        val batchThreads = if (json.has("batchThreads") && !json.isNull("batchThreads")) {
            json.optInt("batchThreads", -1).takeIf { it > 0 }
        } else {
            null
        }
        return TuningDecision(
            decodeThreads = decodeThreads,
            batchThreads = batchThreads,
            directModelFirst = json.optBoolean("directModelFirst", false),
            meanNs = json.optLong("meanNs", 0L),
            measuredConfigs = json.optInt("measuredConfigs", 0),
            source = "cached",
        )
    }

    fun saveCached(
        context: Context,
        engineRevision: String,
        topology: CpuTopology,
        model: File,
        decision: TuningDecision,
        measurements: List<TuningMeasurement> = emptyList(),
    ) {
        val prefs = context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val prefix = cachePrefix(engineRevision)
        val key = cacheKey(engineRevision, topology.signature, model.name, model.length())
        val payload = JSONObject()
            .put("schemaVersion", TUNING_SCHEMA_VERSION)
            .put("appVersionCode", com.zeroconvert.ime.BuildConfig.VERSION_CODE)
            .put("decodeThreads", decision.decodeThreads)
            .apply { decision.batchThreads?.let { put("batchThreads", it) } }
            .put("directModelFirst", decision.directModelFirst)
            .put("meanNs", decision.meanNs)
            .put("measuredConfigs", decision.measuredConfigs)
            .put("source", decision.source)
            .put("topology", topology.signature)
            .put("clusterFreqsKHz", JSONArray(topology.clusterMaxFreqKHz))
            .put("engineRevision", engineRevision)
            .put("modelName", model.name)
            .put("modelSize", model.length())
            .put("updatedAtMs", System.currentTimeMillis())
        if (measurements.isNotEmpty()) {
            val encoded = JSONArray()
            for (item in measurements) {
                encoded.put(
                    JSONObject()
                        .put("decodeThreads", item.decodeThreads)
                        .apply { item.batchThreads?.let { put("batchThreads", it) } }
                        .put("directModelFirst", item.directModelFirst)
                        .put("meanNs", item.meanNs)
                        .put("completedSamples", item.completedSamples)
                )
            }
            payload.put("measurements", encoded)
        }
        val editor = prefs.edit().putString(key, payload.toString())
        for (existingKey in prefs.all.keys) {
            if (existingKey != key && !existingKey.startsWith(prefix)) {
                editor.remove(existingKey)
            }
        }
        editor.commit()
    }

    fun resolveOrProbeForBenchmark(
        context: Context,
        model: File,
        runProbe: (String) -> String,
    ): TuningDecision = synchronized(probeLock) {
        val topology = readTopology()
        val revision = engineRevision(context)
        loadCached(context, revision, topology, model)?.let { cached ->
            Log.i(
                TAG,
                "Benchmark cached tuning for ${model.name}: decode=${cached.decodeThreads} " +
                    "batch=${cached.batchThreads} directModelFirst=${cached.directModelFirst} " +
                    "topology=${topology.signature}"
            )
            return cached
        }
        val candidates = candidateConfigs(topology, model.length())
        val raw = runProbe(configsToJsonArray(candidates).toString())
        val measurements = parseMeasurements(raw)
        val best = selectBest(measurements)
            ?: return defaultFallback(topology, model.length())
        val decision = TuningDecision(
            decodeThreads = best.decodeThreads,
            batchThreads = best.batchThreads,
            directModelFirst = best.directModelFirst,
            meanNs = best.meanNs,
            measuredConfigs = measurements.size,
            source = "probed",
        )
        saveCached(context, revision, topology, model, decision, measurements)
        Log.i(
            TAG,
            "Benchmark auto-tuned ${model.name}: decode=${decision.decodeThreads} " +
                "batch=${decision.batchThreads} directModelFirst=${decision.directModelFirst} " +
                "meanMs=${"%.2f".format(decision.meanNs / 1_000_000.0)} " +
                "topology=${topology.signature} freqsKHz=${topology.clusterMaxFreqKHz}"
        )
        decision
    }
}
