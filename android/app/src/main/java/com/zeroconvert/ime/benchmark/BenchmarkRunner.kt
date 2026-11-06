package com.zeroconvert.ime.benchmark

import android.content.Context
import com.zeroconvert.ime.KanaKanjiConverter
import com.zeroconvert.ime.ZenzNative
import kotlinx.coroutines.Dispatchers
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
    val acceptable: List<String>
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

enum class BenchmarkKind {
    CONVERSION,
    TYPO,
    PREDICTIVE
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
    val falsePositiveCount: Int = 0
)

object BenchmarkRunner {

    const val CONVERSION_DATASET_NAME = "かな漢字変換 480問"
    const val TYPO_DATASET_NAME = "タイポ補正 300問"
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

    private fun evaluateOutput(output: String, item: BenchmarkItem): Pair<Int, String> {
        val out = output.trim().replace("\r", "").replace("\n", "")
        val expected = item.expected.trim()
        val acceptable = item.acceptable.map { it.trim() }
        val forbidden = item.forbidden.map { it.trim() }

        for (fb in forbidden) {
            if (fb.isNotEmpty() && out.contains(fb)) {
                return Pair(0, "NG (禁止語含有: $fb)")
            }
        }

        if (out == expected || acceptable.contains(out)) {
            return Pair(2, "OK (完全一致)")
        }

        if (out.replace(" ", "").replace("　", "") == expected.replace(" ", "").replace("　", "")) {
            return Pair(2, "OK (空白差異)")
        }

        val hiraOut = KanaKanjiConverter.toHiragana(out)
        val hiraExp = KanaKanjiConverter.toHiragana(expected)
        if (hiraOut == hiraExp) {
            return Pair(2, "OK (かな差異)")
        }

        return Pair(0, "NG (出力=[$out] vs 正解=[$expected])")
    }

    suspend fun runBenchmark(
        context: Context,
        onProgress: (BenchmarkProgress) -> Unit
    ) = withContext(Dispatchers.IO) {
        val items = loadDataset(context)
        if (items.isEmpty()) return@withContext

        var perfectCount = 0
        var homophonePerfect = 0
        var homophoneTotal = 0
        var totalLatency = 0.0

        for (i in items.indices) {
            val item = items[i]
            val isHomophone = item.category.contains("同音異義語")
            if (isHomophone) homophoneTotal++

            val katakana = KanaKanjiConverter.toKatakana(item.target)
            val t0 = System.currentTimeMillis()
            val result = ZenzNative.convert(item.leftContext, katakana)
            val latency = (System.currentTimeMillis() - t0).toDouble()
            totalLatency += latency

            val (score, reason) = evaluateOutput(result, item)
            val isOk = (score == 2)
            if (isOk) {
                perfectCount++
                if (isHomophone) homophonePerfect++
            }

            val avgLat = totalLatency / (i + 1)
            val detail = "#${item.id} [${item.target}] -> 「$result」 ${if (isOk) "OK" else "NG"} ($reason, ${latency.toLong()}ms)"

            onProgress(
                BenchmarkProgress(
                    current = i + 1,
                    total = items.size,
                    perfectCount = perfectCount,
                    homophonePerfect = homophonePerfect,
                    homophoneTotal = homophoneTotal,
                    avgLatencyMs = avgLat,
                    lastItemDetail = detail,
                    isRunning = true,
                    isFinished = false,
                    kind = BenchmarkKind.CONVERSION
                )
            )
        }

        val finalAvg = if (items.isNotEmpty()) totalLatency / items.size else 0.0
        onProgress(
            BenchmarkProgress(
                current = items.size,
                total = items.size,
                perfectCount = perfectCount,
                homophonePerfect = homophonePerfect,
                homophoneTotal = homophoneTotal,
                avgLatencyMs = finalAvg,
                lastItemDetail = "かな漢字変換ベンチマーク完了: 正解数 $perfectCount / ${items.size}",
                isRunning = false,
                isFinished = true,
                kind = BenchmarkKind.CONVERSION
            )
        )
    }

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
                                    acceptable = acceptable
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

    internal fun isTypoCorrectionAccepted(item: TypoBenchmarkItem, correction: String?): Boolean {
        if (correction == null) return false
        if (correction == item.expected || item.acceptable.contains(correction)) return true
        if (correction.replace(" ", "").replace("　", "") == item.expected.replace(" ", "").replace("　", "")) {
            return true
        }
        return correction.contains(item.expected) || item.expected.contains(correction)
    }

    internal fun isCleanInputPreserved(item: TypoBenchmarkItem, correction: String?): Boolean {
        if (correction == null) return true
        if (correction == item.expected || correction == item.inputReading) return true
        return correction.replace(" ", "").replace("　", "") ==
            item.inputReading.replace(" ", "").replace("　", "")
    }

    suspend fun runTypoBenchmark(
        context: Context,
        onProgress: (BenchmarkProgress) -> Unit
    ) = withContext(Dispatchers.IO) {
        val items = loadTypoDataset(context)
        if (items.isEmpty()) return@withContext

        var successCount = 0
        var typoTotal = 0
        var typoCorrect = 0
        var cleanTotal = 0
        var cleanPreserved = 0
        var falsePositiveCount = 0
        var totalLatency = 0.0

        for (i in items.indices) {
            val item = items[i]
            val t0 = System.currentTimeMillis()
            val correction = com.zeroconvert.ime.assist.ModelCorrector(ZenzNative.assistSession())
                .suggest(item.context, item.inputReading, item.inputReading)?.text
            val latency = (System.currentTimeMillis() - t0).toDouble()
            totalLatency += latency

            val isPreserved: Boolean
            if (item.isClean) {
                cleanTotal++
                isPreserved = isCleanInputPreserved(item, correction)
                if (isPreserved) {
                    cleanPreserved++
                } else {
                    falsePositiveCount++
                }
            } else {
                typoTotal++
                isPreserved = isTypoCorrectionAccepted(item, correction)
                if (isPreserved) typoCorrect++
            }
            if (isPreserved) successCount++

            val avgLat = totalLatency / (i + 1)
            val detail = "#${item.id} [${item.inputReading}] -> 「${correction ?: "(なし)"}」 " +
                "${if (isPreserved) "OK" else "NG"} (${latency.toLong()}ms)"

            onProgress(
                BenchmarkProgress(
                    current = i + 1,
                    total = items.size,
                    perfectCount = successCount,
                    homophonePerfect = cleanPreserved,
                    homophoneTotal = cleanTotal,
                    avgLatencyMs = avgLat,
                    lastItemDetail = detail,
                    isRunning = true,
                    isFinished = false,
                    kind = BenchmarkKind.TYPO,
                    falsePositiveCount = falsePositiveCount
                )
            )
        }

        val finalAvg = totalLatency / items.size
        onProgress(
            BenchmarkProgress(
                current = items.size,
                total = items.size,
                perfectCount = successCount,
                homophonePerfect = cleanPreserved,
                homophoneTotal = cleanTotal,
                avgLatencyMs = finalAvg,
                lastItemDetail = "タイポ補正: $typoCorrect / $typoTotal 正解、" +
                    "正常入力保持: $cleanPreserved / $cleanTotal（誤補正 $falsePositiveCount 件）",
                isRunning = false,
                isFinished = true,
                kind = BenchmarkKind.TYPO,
                falsePositiveCount = falsePositiveCount
            )
        )
    }

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

    suspend fun runPredictiveBenchmark(
        context: Context,
        onProgress: (BenchmarkProgress) -> Unit
    ) = withContext(Dispatchers.IO) {
        val items = loadPredictiveDataset(context)
        if (items.isEmpty()) return@withContext

        var perfectCount = 0
        var totalLatency = 0.0

        for (i in items.indices) {
            val item = items[i]
            val t0 = System.currentTimeMillis()
            val pred = com.zeroconvert.ime.assist.PredictiveAssistEngine.findPrediction(
                currentSurface = item.partialInput,
                hiraganaReading = com.zeroconvert.ime.assist.ReadingCheck.readingForSurface(item.partialInput)
                    ?: KanaKanjiConverter.toHiragana(item.partialInput),
                context = item.context
            )
            val latency = (System.currentTimeMillis() - t0).toDouble()
            totalLatency += latency

            val isMatch = (pred != null && (
                pred == item.fullSentence ||
                pred.endsWith(item.expectedContinuation) ||
                item.acceptableContinuations.any { pred.endsWith(it) }
            ))
            if (isMatch) {
                perfectCount++
            }

            val avgLat = totalLatency / (i + 1)
            val detail = "#${item.id} [${item.partialInput}] -> 「${pred ?: "(なし)"}」 " +
                "${if (isMatch) "OK" else "NG (正解: ${item.expectedContinuation})"} (${latency.toLong()}ms)"

            onProgress(
                BenchmarkProgress(
                    current = i + 1,
                    total = items.size,
                    perfectCount = perfectCount,
                    homophonePerfect = 0,
                    homophoneTotal = 0,
                    avgLatencyMs = avgLat,
                    lastItemDetail = detail,
                    isRunning = true,
                    isFinished = false,
                    kind = BenchmarkKind.PREDICTIVE
                )
            )
        }

        val finalAvg = totalLatency / items.size
        onProgress(
            BenchmarkProgress(
                current = items.size,
                total = items.size,
                perfectCount = perfectCount,
                homophonePerfect = 0,
                homophoneTotal = 0,
                avgLatencyMs = finalAvg,
                lastItemDetail = "先読みベンチマーク完了: 正解数 $perfectCount / ${items.size}",
                isRunning = false,
                isFinished = true,
                kind = BenchmarkKind.PREDICTIVE
            )
        )
    }
}
