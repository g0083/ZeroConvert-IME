package com.zeroconvert.ime.benchmark

import android.content.Context
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.BufferedReader
import java.io.InputStreamReader

data class BenchmarkItem(
    val id: Int,
    val subset: String,
    val subsetLabel: String,
    val category: String,
    val leftContext: String,
    val target: String,
    val expected: String,
    val acceptable: List<String>,
    val forbidden: List<String>,
    val focus: String
)

data class TypoBenchmarkItem(
    val id: Int,
    val category: String,
    val typoType: String,
    val typoTypeLabel: String,
    val isClean: Boolean,
    val context: String,
    val inputReading: String,
    val targetReading: String,
    val expected: String,
    val acceptable: List<String>,
    val inputMode: String = "kana",
    val origin: String = "",
    val sourceSubset: String = ""
)

data class PredictiveBenchmarkItem(
    val id: Int,
    val category: String,
    val context: String,
    val partialInput: String,
    val expectedContinuation: String,
    val acceptableContinuations: List<String>,
    val fullSentence: String
)

enum class TypoStatus(val wire: String) {
    EXACT_MATCH("exact_match"),
    VARIANT_MATCH("variant_match"),
    CLEAN_PRESERVED("clean_preserved"),
    FALSE_POSITIVE("false_positive"),
    MISSED("missed");
    companion object {
        fun fromWire(wire: String): TypoStatus? = entries.firstOrNull { it.wire == wire }
    }
}

enum class BenchmarkKind {
    CONVERSION,
    TYPO,
    PREDICTIVE
}

enum class ConversionBenchmarkScope(val wire: String, val label: String) {
    MODEL("model", "モデル推論"),
    CANDIDATES("candidates", "共通候補生成"),
}

data class BenchmarkProgress(
    val current: Int,
    val total: Int,
    val perfectCount: Int,
    val homophonePerfect: Int,
    val homophoneTotal: Int,
    val avgLatencyMs: Double,
    val lastItemDetail: String,
    val isRunning: Boolean,
    val isFinished: Boolean,
    val kind: BenchmarkKind = BenchmarkKind.CONVERSION,
    val falsePositiveCount: Int = 0,
    val wallTimeMs: Double = 0.0,
    val conversionScope: ConversionBenchmarkScope = ConversionBenchmarkScope.MODEL,
    val avgNBestMs: Double? = null,
)

object BenchmarkRunner {

    const val CONVERSION_DATASET_NAME = "かな漢字変換 740問"
    const val TYPO_DATASET_NAME = "タイポ補正 450問"
    const val PREDICTIVE_DATASET_NAME = "先読み 30問"

    fun loadDataset(context: Context): List<BenchmarkItem> {
        val list = mutableListOf<BenchmarkItem>()
        try {
            context.assets.open("benchmark_dataset.jsonl").use { inputStream ->
                BufferedReader(InputStreamReader(inputStream, Charsets.UTF_8)).use { reader ->
                    var line: String? = reader.readLine()
                    while (line != null) {
                        val trimmed = line.trim()
                        if (trimmed.isNotEmpty()) {
                            val obj = JSONObject(trimmed)
                            val id = obj.optInt("id", 0)
                            val subset = obj.optString("subset", "")
                            val subsetLabel = obj.optString("subset_label", "")
                            val cat = obj.optString("category", "その他")
                            val rawCtx = obj.optString("left_context", "")
                            val ctx = if (rawCtx == "なし") "" else rawCtx
                            val target = obj.optString("target", "")
                            val expected = obj.optString("expected", "")

                            val accList = mutableListOf<String>()
                            val accArr = obj.optJSONArray("acceptable")
                            if (accArr != null) {
                                for (i in 0 until accArr.length()) {
                                    accList.add(accArr.getString(i))
                                }
                            }

                            val fbList = mutableListOf<String>()
                            val fbArr = obj.optJSONArray("forbidden_examples")
                            if (fbArr != null) {
                                for (i in 0 until fbArr.length()) {
                                    fbList.add(fbArr.getString(i))
                                }
                            }

                            list.add(
                                BenchmarkItem(
                                    id = id,
                                    subset = subset,
                                    subsetLabel = subsetLabel,
                                    category = cat,
                                    leftContext = ctx,
                                    target = target,
                                    expected = expected,
                                    acceptable = accList,
                                    forbidden = fbList,
                                    focus = obj.optString("focus", "")
                                )
                            )
                        }
                        line = reader.readLine()
                    }
                }
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
        return list
    }

    suspend fun runBenchmark(
        context: Context,
        scope: ConversionBenchmarkScope = ConversionBenchmarkScope.MODEL,
        onProgress: (BenchmarkProgress) -> Unit,
    ) = runDataset(context, "benchmark_dataset.jsonl", BenchmarkKind.CONVERSION, onProgress, scope)

    fun loadTypoDataset(context: Context): List<TypoBenchmarkItem> {
        val list = mutableListOf<TypoBenchmarkItem>()
        try {
            context.assets.open("typo_testset.jsonl").use { stream ->
                BufferedReader(InputStreamReader(stream, Charsets.UTF_8)).use { reader ->
                    var line: String? = reader.readLine()
                    while (line != null) {
                        val trimmed = line.trim()
                        if (trimmed.isNotEmpty()) {
                            val obj = JSONObject(trimmed)
                            val inputReading = obj.optString("input_reading", obj.optString("raw_input", ""))
                            val typoType = obj.optString("typo_type", obj.optString("category", ""))
                            val acceptable = mutableListOf<String>()
                            val accArr = obj.optJSONArray("acceptable")
                            if (accArr != null) {
                                for (i in 0 until accArr.length()) {
                                    acceptable.add(accArr.getString(i))
                                }
                            }
                            list.add(
                                TypoBenchmarkItem(
                                    id = obj.optInt("id", 0),
                                    category = obj.optString("category", ""),
                                    typoType = typoType,
                                    typoTypeLabel = obj.optString("typo_type_label", typoType),
                                    isClean = obj.optBoolean("is_clean", false),
                                    context = obj.optString("context", ""),
                                    inputReading = inputReading,
                                    targetReading = obj.optString("target_reading", ""),
                                    expected = obj.optString("expected", ""),
                                    acceptable = acceptable,
                                    inputMode = obj.optString("input_mode", "kana"),
                                    origin = obj.optString("origin", ""),
                                    sourceSubset = obj.optString("source_subset", "")
                                )
                            )
                        }
                        line = reader.readLine()
                    }
                }
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
        return list
    }

    suspend fun runTypoBenchmark(context: Context, onProgress: (BenchmarkProgress) -> Unit) =
        runDataset(context, "typo_testset.jsonl", BenchmarkKind.TYPO, onProgress)

    fun loadPredictiveDataset(context: Context): List<PredictiveBenchmarkItem> {
        val list = mutableListOf<PredictiveBenchmarkItem>()
        try {
            context.assets.open("predictive_testset.jsonl").use { stream ->
                BufferedReader(InputStreamReader(stream, Charsets.UTF_8)).use { reader ->
                    var line: String? = reader.readLine()
                    while (line != null) {
                        val trimmed = line.trim()
                        if (trimmed.isNotEmpty()) {
                            val obj = JSONObject(trimmed)
                            val id = obj.optInt("id", 0)
                            val cat = obj.optString("category", "")
                            val ctx = obj.optString("context", "")
                            val partial = obj.optString("partial_input", "")
                            val expectedCont = obj.optString("expected_continuation", "")
                            val fullSent = obj.optString("full_sentence", "")
                            val accList = mutableListOf<String>()
                            val accArr = obj.optJSONArray("acceptable_continuations")
                            if (accArr != null) {
                                for (i in 0 until accArr.length()) {
                                    accList.add(accArr.getString(i))
                                }
                            }
                            list.add(
                                PredictiveBenchmarkItem(
                                    id = id,
                                    category = cat,
                                    context = ctx,
                                    partialInput = partial,
                                    expectedContinuation = expectedCont,
                                    acceptableContinuations = accList,
                                    fullSentence = fullSent
                                )
                            )
                        }
                        line = reader.readLine()
                    }
                }
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
        return list
    }

    suspend fun runPredictiveBenchmark(context: Context, onProgress: (BenchmarkProgress) -> Unit) =
        runDataset(context, "predictive_testset.jsonl", BenchmarkKind.PREDICTIVE, onProgress)

    private val runLock = Mutex()

    private suspend fun runDataset(
        context: Context,
        asset: String,
        kind: BenchmarkKind,
        onProgress: (BenchmarkProgress) -> Unit,
        scope: ConversionBenchmarkScope = ConversionBenchmarkScope.MODEL,
    ) = withContext(Dispatchers.IO) {
        runLock.withLock {
            var current = 0
            var total = 0
            var correct = 0
            var groupCorrect = 0
            var groupTotal = 0
            var falsePositives = 0
            var totalLatency = 0.0
            var totalNBest = 0.0
            var started = 0L
            fun update(detail: String, running: Boolean, finished: Boolean) {
                onProgress(BenchmarkProgress(
                    current, total, correct, groupCorrect, groupTotal,
                    if (current == 0) 0.0 else totalLatency / current,
                    detail, running, finished, kind, falsePositives,
                    if (started == 0L) 0.0 else (System.nanoTime() - started) / 1e6,
                    scope,
                    if (scope == ConversionBenchmarkScope.CANDIDATES && current > 0) totalNBest / current else null,
                ))
            }
            update("モデルを準備中…", running = true, finished = false)
            try {
                val cases = context.assets.open(asset).bufferedReader().useLines { lines ->
                    lines.filter { it.isNotBlank() }.map {
                        JSONObject(it).put("kind", kind.name.lowercase(java.util.Locale.ROOT))
                    }.toList()
                }
                total = cases.size
                check(total > 0) { "ベンチマークデータが空です。" }
                var detail = ""
                started = System.nanoTime()
                BenchmarkSession(context, scope).use { session ->
                    for (batch in cases.chunked(session.batchSize)) {
                        ensureActive()
                        val rows = session.run(batch)
                        for ((i, item) in batch.withIndex()) {
                            val row = rows.getJSONObject(i)
                            check(row.getInt("id") == item.getInt("id"))
                            val latency = row.getDouble("latency_ms")
                            val (success, output) = when (kind) {
                                BenchmarkKind.CONVERSION -> {
                                    if (scope == ConversionBenchmarkScope.CANDIDATES) totalNBest += row.getDouble("nbest_ms")
                                    val hit = row.getBoolean("is_correct")
                                    if (item.getString("category").contains("同音異義語")) {
                                        groupTotal++
                                        if (hit) groupCorrect++
                                    }
                                    hit to row.getString("top1")
                                }
                                BenchmarkKind.TYPO -> {
                                    val status = TypoStatus.fromWire(row.getString("status"))
                                    if (item.getBoolean("is_clean")) {
                                        groupTotal++
                                        if (status == TypoStatus.CLEAN_PRESERVED) groupCorrect++
                                        if (status == TypoStatus.FALSE_POSITIVE) falsePositives++
                                    }
                                    (status == TypoStatus.CLEAN_PRESERVED || status == TypoStatus.EXACT_MATCH ||
                                        status == TypoStatus.VARIANT_MATCH) to
                                        (if (row.isNull("moshikashite_output")) row.getString("main_output")
                                        else row.getString("moshikashite_output"))
                                }
                                BenchmarkKind.PREDICTIVE -> row.getBoolean("is_match") to
                                    (if (row.isNull("prediction")) "(なし)" else row.getString("prediction"))
                            }
                            if (success) correct++
                            current++
                            totalLatency += latency
                            detail = "#${row.getInt("id")} 「$output」 ${if (success) "OK" else "NG"} (${latency.toLong()} ms)"
                        }
                        update(detail, running = true, finished = false)
                    }
                }
                update("ベンチマーク完了", running = false, finished = true)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: Exception) {
                update(failure.message ?: "ベンチマークに失敗しました。", running = false, finished = false)
            }
        }
    }
}
