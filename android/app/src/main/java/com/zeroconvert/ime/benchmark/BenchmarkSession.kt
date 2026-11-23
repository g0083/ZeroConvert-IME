package com.zeroconvert.ime.benchmark

import android.content.Context
import com.zeroconvert.ime.ZeroConvertInputMethodService
import com.zeroconvert.ime.engine.RuntimeThreadTuner
import com.zeroconvert.ime.engine.WindowsEngine
import com.zeroconvert.ime.model.DictionaryDownloadManager
import com.zeroconvert.ime.model.ModelManager
import org.json.JSONArray
import org.json.JSONObject

internal class BenchmarkSession(
    context: Context,
    private val scope: ConversionBenchmarkScope = ConversionBenchmarkScope.MODEL,
) : AutoCloseable {
    private val dictionary = DictionaryDownloadManager.reverseDictionary(context).absolutePath
    private val typoEnabled = scope == ConversionBenchmarkScope.TYPO &&
        context.getSharedPreferences(ZeroConvertInputMethodService.PREFS_SETTINGS, Context.MODE_PRIVATE)
            .getBoolean(ZeroConvertInputMethodService.KEY_DICTIONARY_TYPO_CORRECTION,
                ZeroConvertInputMethodService.DEFAULT_DICTIONARY_TYPO_CORRECTION)
    private val inference: Long
    private val candidates: Long
    val batchSize: Int

    init {
        WindowsEngine.suspendRuntimeTuningForBenchmark()
        var candidatesHandle = 0L
        var inferenceHandle = 0L
        try {
            System.loadLibrary("zenz_jni")
            batchSize = JSONObject(nativeRun("{\"op\":\"benchmark_info\"}", 0)).getInt("batch_size")
            val model = ModelManager.selectedModelFile(context)
            checkNotNull(model) { "選択中のモデルをダウンロードしてください。" }
            val libraryDir = context.applicationInfo.nativeLibraryDir
            candidatesHandle = if (scope != ConversionBenchmarkScope.MODEL) {
                check(DictionaryDownloadManager.isReady(context)) { "日本語辞書をダウンロードしてください。" }
                nativeOpenCandidates(
                    model.absolutePath, DictionaryDownloadManager.dictionaryDir(context).absolutePath, libraryDir,
                    scope == ConversionBenchmarkScope.TYPO, typoEnabled,
                ).also { check(it != 0L) { "候補生成ベンチマークを初期化できませんでした。" } }
            } else {
                0L
            }
            inferenceHandle = if (scope == ConversionBenchmarkScope.MODEL || typoEnabled) {
                nativeOpen(model.absolutePath, libraryDir, if (typoEnabled) dictionary else "")
                    .also { check(it != 0L) { "ベンチマークの推論を初期化できませんでした。" } }
            } else {
                0L
            }
            if (scope == ConversionBenchmarkScope.CANDIDATES || scope == ConversionBenchmarkScope.TYPO) {
                val decision = RuntimeThreadTuner.resolveOrProbeForBenchmark(context, model) { configsJson ->
                    nativeProbeCandidates(candidatesHandle, configsJson)
                }
                nativeConfigureCandidates(
                    decision.decodeThreads,
                    decision.batchThreads ?: -1,
                    if (decision.directModelFirst) 1 else 0
                )
            }
            candidates = candidatesHandle
            inference = inferenceHandle
        } catch (failure: Throwable) {
            if (inferenceHandle != 0L) nativeClose(inferenceHandle)
            if (candidatesHandle != 0L) nativeCloseCandidates(candidatesHandle)
            WindowsEngine.resumeRuntimeTuningAfterBenchmark()
            throw failure
        }
    }

    fun run(cases: List<JSONObject>): JSONArray {
        val request = JSONObject().put("op", "benchmark").put("dictionary", dictionary)
            .put("scope", scope.wire)
            .put("cases", JSONArray(cases))
        if (scope == ConversionBenchmarkScope.TYPO) request.put("typo_enabled", typoEnabled)
        val response = JSONObject(nativeRun(request.toString(), if (typoEnabled) inference else 0))
        check(!response.has("error")) { response.getString("error") }
        return response.getJSONArray("rows")
    }

    @Suppress("unused", "UNUSED_PARAMETER") // Called synchronously by JNI on the benchmark worker.
    fun complete(prediction: Boolean, payload: String, timeout: Long): String {
        val request = JSONObject(payload)
        if (request.has("reading")) {
            return nativeCandidates(candidates, request.getString("reading"), request.getString("context"))
        }
        val result = nativeComplete(inference, request.getString("prompt"), request.getInt("n_predict"), timeout.toInt())
        return JSONObject().put("reason", result[0]).put("text", result[1])
            .put("elapsed_ns", result[2].toLong()).toString()
    }

    override fun close() {
        if (inference != 0L) nativeClose(inference)
        if (candidates != 0L) nativeCloseCandidates(candidates)
        WindowsEngine.resumeRuntimeTuningAfterBenchmark()
    }

    private external fun nativeOpen(path: String, libraryDir: String, reverseDictionary: String): Long
    private external fun nativeRun(input: String, benchmarkInference: Long): String
    private external fun nativeComplete(handle: Long, prompt: String, tokens: Int, timeout: Int): Array<String>
    private external fun nativeClose(handle: Long)
    private external fun nativeOpenCandidates(model: String, dictionary: String, libraryDir: String,
                                              typoBenchmark: Boolean, typoEnabled: Boolean): Long
    private external fun nativeCandidates(handle: Long, reading: String, context: String): String
    private external fun nativeCloseCandidates(handle: Long)
    private external fun nativeConfigureCandidates(decodeThreads: Int, batchThreads: Int, directModelFirstMode: Int)
    private external fun nativeProbeCandidates(handle: Long, configsJson: String): String
}
