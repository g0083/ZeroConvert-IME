package com.zeroconvert.ime.benchmark

import android.content.Context
import com.zeroconvert.ime.model.DictionaryDownloadManager
import com.zeroconvert.ime.model.ModelManager
import org.json.JSONArray
import org.json.JSONObject

internal class BenchmarkSession(
    context: Context,
    private val scope: ConversionBenchmarkScope = ConversionBenchmarkScope.MODEL,
) : AutoCloseable {
    private val dictionary = DictionaryDownloadManager.reverseDictionary(context).absolutePath
    private val handle: Long
    val batchSize: Int

    init {
        System.loadLibrary("zenz_jni")
        batchSize = JSONObject(nativeRun("{\"op\":\"benchmark_info\"}")).getInt("batch_size")
        val model = ModelManager.selectedModelFile(context)
        checkNotNull(model) { "選択中のモデルをダウンロードしてください。" }
        val libraryDir = context.applicationInfo.nativeLibraryDir
        handle = when (scope) {
            ConversionBenchmarkScope.MODEL -> nativeOpen(model.absolutePath, libraryDir)
            ConversionBenchmarkScope.CANDIDATES -> {
                check(DictionaryDownloadManager.isReady(context)) { "日本語辞書をダウンロードしてください。" }
                nativeOpenCandidates(model.absolutePath, DictionaryDownloadManager.dictionaryDir(context).absolutePath, libraryDir)
            }
        }
        check(handle != 0L) { "ベンチマークを初期化できませんでした。" }
    }

    fun run(cases: List<JSONObject>): JSONArray {
        val request = JSONObject().put("op", "benchmark").put("dictionary", dictionary)
            .put("scope", scope.wire)
            .put("cases", JSONArray(cases))
        val response = JSONObject(nativeRun(request.toString()))
        check(!response.has("error")) { response.getString("error") }
        return response.getJSONArray("rows")
    }

    @Suppress("unused", "UNUSED_PARAMETER") // Called synchronously by JNI on the benchmark worker.
    fun complete(prediction: Boolean, payload: String, timeout: Long): String {
        val request = JSONObject(payload)
        if (scope == ConversionBenchmarkScope.CANDIDATES) {
            return nativeCandidates(handle, request.getString("reading"), request.getString("context"))
        }
        val result = nativeComplete(handle, request.getString("prompt"), request.getInt("n_predict"), timeout.toInt())
        return JSONObject().put("reason", result[0]).put("text", result[1])
            .put("elapsed_ns", result[2].toLong()).toString()
    }

    override fun close() = when (scope) {
        ConversionBenchmarkScope.MODEL -> nativeClose(handle)
        ConversionBenchmarkScope.CANDIDATES -> nativeCloseCandidates(handle)
    }

    private external fun nativeOpen(path: String, libraryDir: String): Long
    private external fun nativeRun(input: String): String
    private external fun nativeComplete(handle: Long, prompt: String, tokens: Int, timeout: Int): Array<String>
    private external fun nativeClose(handle: Long)
    private external fun nativeOpenCandidates(model: String, dictionary: String, libraryDir: String): Long
    private external fun nativeCandidates(handle: Long, reading: String, context: String): String
    private external fun nativeCloseCandidates(handle: Long)
}
