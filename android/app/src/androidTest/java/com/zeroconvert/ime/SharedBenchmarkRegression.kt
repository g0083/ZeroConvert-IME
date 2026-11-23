package com.zeroconvert.ime

import android.app.Activity
import android.app.Instrumentation
import android.os.Bundle
import com.zeroconvert.ime.benchmark.BenchmarkSession
import com.zeroconvert.ime.benchmark.ConversionBenchmarkScope
import com.zeroconvert.ime.benchmark.TypoStatus
import org.json.JSONObject
import java.io.File

internal class SharedBenchmarkRegression(private val instrumentation: Instrumentation) {
    fun run(limit: Int?): Pair<Int, Bundle> {
        return try {
            val context = instrumentation.targetContext
            val output = File(context.filesDir, "shared-benchmarks.jsonl")
            output.bufferedWriter().use { writer ->
                for ((kind, asset, scope) in listOf(
                    Triple("conversion", "benchmark_dataset.jsonl", ConversionBenchmarkScope.MODEL),
                    Triple("conversion", "benchmark_dataset.jsonl", ConversionBenchmarkScope.CANDIDATES),
                    Triple("typo", "typo_testset.jsonl", ConversionBenchmarkScope.TYPO),
                    Triple("predictive", "predictive_testset.jsonl", ConversionBenchmarkScope.MODEL),
                )) {
                    val cases = context.assets.open(asset).bufferedReader().useLines { lines ->
                        lines.filter { it.isNotBlank() }.take(limit ?: Int.MAX_VALUE)
                            .map { JSONObject(it).put("kind", kind) }.toList()
                    }
                    check(cases.isNotEmpty())
                    val started = System.nanoTime()
                    BenchmarkSession(context, scope).use { session ->
                        for (batch in cases.chunked(session.batchSize)) {
                            val rows = session.run(batch)
                            check(rows.length() == batch.size)
                            for ((index, item) in batch.withIndex()) {
                                val row = rows.getJSONObject(index)
                                check(row.getInt("id") == item.getInt("id"))
                                check(row.getDouble("latency_ms").isFinite() && row.getDouble("latency_ms") >= 0)
                                if (scope == ConversionBenchmarkScope.CANDIDATES) {
                                    val nbest = row.getDouble("nbest_ms")
                                    check(nbest > 0 && nbest <= row.getDouble("latency_ms"))
                                    check(row.getJSONArray("candidates").length() > 0)
                                }
                                if (scope == ConversionBenchmarkScope.TYPO) {
                                    check(row.getString("main_output").isNotEmpty())
                                    check(row.has("correction_reading"))
                                    checkNotNull(TypoStatus.fromWire(row.getString("status")))
                                }
                                writer.appendLine(row.put("kind", kind).put("scope", scope.wire).toString())
                            }
                        }
                    }
                    val elapsed = (System.nanoTime() - started) / 1e6
                    writer.appendLine(JSONObject().put("kind", "summary").put("suite", kind)
                        .put("scope", scope.wire).put("count", cases.size).put("wall_ms", elapsed).toString())
                    writer.flush()
                    instrumentation.sendStatus(0, Bundle().apply {
                        putString("stream", "$kind ${scope.wire} ${cases.size} cases, $elapsed ms\n")
                    })
                }
            }
            Activity.RESULT_OK to Bundle().apply { putString("stream", "PASS shared benchmarks\n") }
        } catch (error: Exception) {
            Activity.RESULT_CANCELED to Bundle().apply {
                putString("stream", "FAIL shared benchmarks: ${error.stackTraceToString()}\n")
            }
        }
    }
}
