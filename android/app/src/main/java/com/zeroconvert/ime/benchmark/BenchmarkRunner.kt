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

    // 共通ベンチマーク採点 (Rust benchmark.rs と同一契約)。結果を同一にするため
    // Kotlin ミラーは parity_cases.jsonl と突合して検証する。
    internal fun cleanScoringInput(s: String): String =
        s.trim().replace("\r", "").replace("\n", "")

    internal fun normalizeWhitespace(s: String): String =
        s.filterNot { it.isWhitespace() || it == '　' }

    private fun matchesExact(candidate: String, expected: String, acceptable: List<String>): Boolean {
        val cand = cleanScoringInput(candidate)
        val exp = expected.trim()
        if (cand == exp || acceptable.any { it.trim() == cand }) return true
        val normCand = normalizeWhitespace(cand)
        if (normCand == normalizeWhitespace(exp)) return true
        return acceptable.any { normCand == normalizeWhitespace(it.trim()) }
    }

    private fun matchesVariant(candidate: String, expected: String): Boolean {
        val cand = cleanScoringInput(candidate)
        val exp = expected.trim()
        if (cand.isEmpty() || exp.isEmpty()) return false
        return cand.contains(exp) || exp.contains(cand)
    }

    data class ConversionScore(val isCorrect: Boolean, val reason: String)

    internal fun scoreConversion(
        output: String,
        expected: String,
        acceptable: List<String>,
        forbidden: List<String>
    ): ConversionScore {
        val out = cleanScoringInput(output)
        val exp = expected.trim()
        for (fb in forbidden) {
            val fbTrim = fb.trim()
            if (fbTrim.isNotEmpty() && out.contains(fbTrim)) {
                return ConversionScore(false, "forbidden")
            }
        }
        if (out == exp || acceptable.any { it.trim() == out }) {
            return ConversionScore(true, "exact")
        }
        if (normalizeWhitespace(out) == normalizeWhitespace(exp)) {
            return ConversionScore(true, "whitespace")
        }
        val hiraOut = KanaKanjiConverter.toHiragana(out)
        if (hiraOut == KanaKanjiConverter.toHiragana(exp)) {
            return ConversionScore(true, "kana")
        }
        for (acc in acceptable) {
            val accTrim = acc.trim()
            if (normalizeWhitespace(out) == normalizeWhitespace(accTrim)) {
                return ConversionScore(true, "whitespace_acceptable")
            }
            if (hiraOut == KanaKanjiConverter.toHiragana(accTrim)) {
                return ConversionScore(true, "kana_acceptable")
            }
        }
        return ConversionScore(false, "mismatch")
    }

    data class TypoScore(val status: TypoStatus, val suggestionExact: Boolean)

    internal fun scoreTypo(
        item: TypoBenchmarkItem,
        mainOutput: String,
        suggestion: String?
    ): TypoScore {
        if (item.isClean) {
            if (suggestion == null) return TypoScore(TypoStatus.CLEAN_PRESERVED, false)
            val sugg = cleanScoringInput(suggestion)
            val suggExact = matchesExact(sugg, item.expected, item.acceptable)
            if (suggExact) return TypoScore(TypoStatus.CLEAN_PRESERVED, true)
            if (sugg == cleanScoringInput(mainOutput)) return TypoScore(TypoStatus.CLEAN_PRESERVED, false)
            if (normalizeWhitespace(sugg) == normalizeWhitespace(item.inputReading)) {
                return TypoScore(TypoStatus.CLEAN_PRESERVED, false)
            }
            return TypoScore(TypoStatus.FALSE_POSITIVE, false)
        }
        val suggExact = suggestion != null && matchesExact(suggestion, item.expected, item.acceptable)
        val mainExact = matchesExact(mainOutput, item.expected, item.acceptable)
        if (suggExact || mainExact) return TypoScore(TypoStatus.EXACT_MATCH, suggExact)
        var variant = false
        if (suggestion != null) variant = variant || matchesVariant(suggestion, item.expected)
        variant = variant || matchesVariant(mainOutput, item.expected)
        if (variant) return TypoScore(TypoStatus.VARIANT_MATCH, suggExact)
        return TypoScore(TypoStatus.MISSED, suggExact)
    }

    internal fun scorePredictive(
        prediction: String?,
        fullSentence: String,
        expectedContinuation: String,
        acceptable: List<String>
    ): Boolean {
        if (prediction == null) return false
        val pred = prediction.trim()
        if (pred.isEmpty()) return false
        if (pred == fullSentence.trim()) return true
        val expected = expectedContinuation.trim()
        if (expected.isNotEmpty() && pred.endsWith(expected)) return true
        return acceptable.any { it.trim().isNotEmpty() && pred.endsWith(it.trim()) }
    }

    internal fun latencyPercentiles(latencies: List<Double>): Triple<Double, Double, Double> {
        if (latencies.isEmpty()) return Triple(0.0, 0.0, 0.0)
        val sorted = latencies.sorted()
        fun at(p: Double): Double {
            val rank = kotlin.math.ceil(p / 100.0 * sorted.size).toInt()
            return sorted[(rank - 1).coerceIn(0, sorted.size - 1)]
        }
        return Triple(at(50.0), at(95.0), at(99.0))
    }

    private fun evaluateOutput(output: String, item: BenchmarkItem): Pair<Int, String> {
        val out = cleanScoringInput(output)
        val score = scoreConversion(output, item.expected, item.acceptable, item.forbidden)
        return if (score.isCorrect) {
            Pair(2, "OK (${score.reason})")
        } else {
            Pair(0, "NG (出力=[$out] vs 正解=[${item.expected}])")
        }
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

    internal fun isTypoCorrectionAccepted(item: TypoBenchmarkItem, correction: String?): Boolean {
        // 旧 API の互換: メイン出力は correction として評価する（提案単独）。
        if (correction == null) return false
        return scoreTypo(item, correction, correction).status == TypoStatus.EXACT_MATCH ||
            scoreTypo(item, correction, correction).status == TypoStatus.VARIANT_MATCH
    }

    internal fun isCleanInputPreserved(item: TypoBenchmarkItem, correction: String?): Boolean {
        if (correction == null) return true
        return scoreTypo(item, "", correction).status == TypoStatus.CLEAN_PRESERVED
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
            // Windows と同一契約: タイポ入力でメイン変換を先に求め、それを baseline として補正提案する。
            val mainOutput = runCatching {
                ZenzNative.convert(item.context, KanaKanjiConverter.toKatakana(item.inputReading))
            }.getOrElse { item.inputReading }
            val correction = com.zeroconvert.ime.assist.ModelCorrector(ZenzNative.assistSession())
                .suggest(item.context, item.inputReading, mainOutput)?.text
            val latency = (System.currentTimeMillis() - t0).toDouble()
            totalLatency += latency

            val score = scoreTypo(item, mainOutput, correction)
            val isPreserved: Boolean
            if (item.isClean) {
                cleanTotal++
                isPreserved = score.status == TypoStatus.CLEAN_PRESERVED
                if (isPreserved) {
                    cleanPreserved++
                } else {
                    falsePositiveCount++
                }
            } else {
                typoTotal++
                isPreserved = score.status == TypoStatus.EXACT_MATCH || score.status == TypoStatus.VARIANT_MATCH
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

            val isMatch = scorePredictive(pred, item.fullSentence, item.expectedContinuation, item.acceptableContinuations)
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
