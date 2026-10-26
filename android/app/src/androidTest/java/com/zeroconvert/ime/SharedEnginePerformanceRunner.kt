package com.zeroconvert.ime

import android.app.Instrumentation
import android.os.Bundle
import com.zeroconvert.ime.benchmark.BenchmarkRunner
import kotlinx.coroutines.*
import org.json.JSONObject
import java.io.File
import java.security.MessageDigest

/** Headless, synthetic-only comparison. Also runs against the unchanged 2.0.4 APK. */
class SharedEnginePerformanceRunner(private val instrumentation: Instrumentation) {
    private val targetContext get() = instrumentation.targetContext
    fun run(arguments: Bundle): Pair<Int, Bundle> {
        return try {
            LearningPolicy.withoutWrites {
                val engine = com.zeroconvert.ime.engine.WindowsEngine
                engine.javaClass.methods.firstOrNull { it.name == "setDiagnosticsEnabled" }
                    ?.invoke(engine, arguments.getString("diagnostics") == "true")
                check(ZenzNative.init(targetContext))
                KanaKanjiConverter.ensureInitialized(targetContext)
                val dataset = BenchmarkRunner.loadDataset(targetContext)
                check(dataset.isNotEmpty())
                val count = (arguments.getString("cases")?.toIntOrNull() ?: 40).coerceIn(1, dataset.size)
                val rounds = (arguments.getString("rounds")?.toIntOrNull() ?: 3).coerceIn(1, 5)
                val items = (0 until count).map { dataset[it * dataset.size / count] }
                val prefs = targetContext.getSharedPreferences("model_prefs", 0)
                val selected = prefs.getString("selected_model_path", null)?.let(::File)
                val modelHash = selected?.takeIf { it.isFile }?.inputStream()?.use { input ->
                    val digest = MessageDigest.getInstance("SHA-256")
                    val buffer = ByteArray(65536)
                    while (true) { val size = input.read(buffer); if (size < 0) break; digest.update(buffer, 0, size) }
                    digest.digest().joinToString("") { "%02x".format(it) }
                }
                val output = File(targetContext.filesDir, "shared-engine-performance.jsonl")
                output.writeText(JSONObject().put("kind", "metadata").put("model", ZenzNative.currentModelName)
                    .put("modelSha256", modelHash).put("cases", count).put("rounds", rounds)
                    .put("version", targetContext.packageManager.getPackageInfo(targetContext.packageName, 0).versionName).toString() + "\n")
                // Model load / first dictionary initialization are outside warm measurements.
                repeat(2) { ZenzNative.convert("", "トウキョウ") }
                for (round in 0 until rounds) {
                    var totalMs = 0.0
                    for (item in items) {
                        val started = System.nanoTime()
                        val converted = ZenzNative.convert(item.leftContext, KanaKanjiConverter.toKatakana(item.target))
                        val elapsed = (System.nanoTime() - started) / 1e6
                        totalMs += elapsed
                        val record = JSONObject().put("kind", "conversion").put("round", round).put("id", item.id)
                            .put("output", converted).put("elapsedMs", elapsed)
                        // Reflection permits the exact same test APK to measure the baseline
                        // app, which has no host timing diagnostics yet.
                        val timing = engine.javaClass.methods.firstOrNull { it.name == "getLastTiming" }?.invoke(engine)
                        if (timing != null) for (method in timing.javaClass.methods.filter {
                            it.parameterCount == 0 && it.name in listOf("getQueueMs", "getConfigureMs", "getNativeMs", "getDecodeMs", "getDecodeCount", "getStagesMs")
                        }) record.put(method.name.removePrefix("get"), JSONObject.wrap(method.invoke(timing)))
                        output.appendText(record.toString() + "\n")
                    }
                    instrumentation.sendStatus(0, Bundle().apply { putString("stream", "round=$round cases=$count mean_ms=${totalMs / count}\n") })
                }
                if (arguments.getString("cancellation") == "true") runBlocking {
                    // Interrupt a long conversion, then ensure the fresh request recovers.
                    // Native callback behavior is also checked by the C++ host regression.
                    repeat(3) {
                        val started = System.nanoTime()
                        val pending = launch(Dispatchers.IO) {
                            ZenzNative.convert("", "キョウハトウキョウニイッテアシタハキョウトニイク".repeat(3))
                        }
                        delay(35)
                        ZenzNative.abort()
                        pending.cancelAndJoin()
                        val recovery = ZenzNative.convert("", "トウキョウ")
                        check(recovery.contains("東京")) { "Conversion did not recover after cancellation: $recovery" }
                        output.appendText(JSONObject().put("kind", "cancel_recovery").put("iteration", it)
                            .put("elapsedMs", (System.nanoTime() - started) / 1e6).toString() + "\n")
                    }
                }
                RESULT_OK to Bundle().apply { putString("stream", "PASS performance comparison; file=shared-engine-performance.jsonl\n") }
            }
        } catch (failure: Throwable) {
            RESULT_CANCELED to Bundle().apply { putString("stream", "FAIL ${failure.javaClass.simpleName}: ${failure.message}\n${failure.stackTrace.take(6).joinToString("\n")}\n") }
        } finally {
            val engine = com.zeroconvert.ime.engine.WindowsEngine
            engine.javaClass.methods.firstOrNull { it.name == "setDiagnosticsEnabled" }?.invoke(engine, false)
        }
    }

    private companion object { const val RESULT_OK = -1; const val RESULT_CANCELED = 0 }
}
