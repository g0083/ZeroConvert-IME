package com.zeroconvert.ime

import android.content.Context
import android.util.Log
import com.zeroconvert.ime.model.ModelManager
import java.io.File
import java.io.FileOutputStream

data class Candidate(
    val text: String,
    val reading: String,
    val cost: Float = 0f,
    val isAI: Boolean = false
)

object ZenzNative {
    private const val TAG = "ZenzNative"

    init {
        try {
            System.loadLibrary("zenz_jni")
            try { Log.i(TAG, "libzenz_jni.so loaded successfully") } catch (t: Throwable) {}
        } catch (e: Throwable) {
            try { Log.w(TAG, "Could not load libzenz_jni.so (normal in host tests)") } catch (t: Throwable) {}
        }
    }

    private external fun nativeInit(modelPath: String, logPath: String): Boolean
    private external fun nativeLoadModel(modelPath: String): Boolean
    private external fun nativeConvert(leftContext: String, readingKatakana: String, profile: String): String
    private external fun nativeAbort()
    private external fun nativeClose()

    private external fun nativeInitDict(dictDir: String): Boolean
    private external fun nativeLookupDict(reading: String, prefixSearch: Boolean, maxCount: Int): String

    fun abort() {
        if (isInitialized) {
            try {
                nativeAbort()
            } catch (e: Exception) {
                Log.w(TAG, "abort error", e)
            }
        }
    }

    @Volatile
    private var isInitialized = false

    @Volatile
    private var isDictInitialized = false

    var currentModelName: String = "ZeroCon Model v3.2 Nano (Q4_K_M — 17.6MB)"
        private set

    fun loadModel(modelFile: File, displayName: String? = null): Boolean {
        if (!modelFile.exists()) {
            Log.e(TAG, "loadModel failed: file not found at ${modelFile.absolutePath}")
            return false
        }
        val ok = nativeLoadModel(modelFile.absolutePath)
        if (ok) {
            isInitialized = true
            currentModelName = displayName ?: modelFile.name
            Log.i(TAG, "Switched model to: $currentModelName (${modelFile.length()} bytes)")
        } else {
            Log.e(TAG, "Failed to load model: ${modelFile.absolutePath}")
        }
        return ok
    }

    fun init(context: Context): Boolean {
        if (isInitialized && isDictInitialized) return true

        // 1. Initialize ZeroCon AI Model
        if (!isInitialized) {
            val prefs = context.getSharedPreferences("model_prefs", Context.MODE_PRIVATE)
            val savedPath = prefs.getString("selected_model_path", null)
            val savedName = prefs.getString("selected_model_name", null)

            val modelFile = if (!savedPath.isNullOrEmpty() && File(savedPath).exists()) {
                val f = File(savedPath)
                val builtInMatch = ModelManager.BUILT_IN_MODELS.find { it.fileName == f.name }
                currentModelName = builtInMatch?.displayName ?: savedName ?: f.name
                f
            } else {
                val spec = ModelManager.DEFAULT_MODEL_SPEC
                val defaultFile = ModelManager.ensureBuiltInModel(context, spec)
                currentModelName = spec.displayName
                defaultFile
            }

            val logFile = File(context.filesDir, "zenz_debug.log")
            isInitialized = nativeInit(modelFile.absolutePath, logFile.absolutePath)
        }

        // 2. Initialize azooKey System Dictionary
        if (!isDictInitialized) {
            val dictDir = File(context.filesDir, "dictionary")
            val loudsDir = File(dictDir, "louds")
            val chidFile = File(loudsDir, "charID.chid")
            val cbFile = File(dictDir, "cb_packed.binary")
            val mmFile = File(dictDir, "mm.binary")

            val versionMarker = File(dictDir, "version_v3_cb815k.marker")
            if (!versionMarker.exists() || !chidFile.exists() || !cbFile.exists() || !mmFile.exists()) {
                Log.i(TAG, "Extracting azooKey dictionary from assets...")
                copyAssetFolder(context, "dictionary", dictDir)
                try {
                    versionMarker.createNewFile()
                } catch (e: Exception) {
                    Log.w(TAG, "Could not create version marker", e)
                }
            }

            isDictInitialized = nativeInitDict(dictDir.absolutePath)
            Log.i(TAG, "azooKey dictionary init result: $isDictInitialized")
        }

        return isInitialized
    }

    private fun copyAssetFolder(context: Context, assetPath: String, destDir: File) {
        val files = context.assets.list(assetPath) ?: return
        if (!destDir.exists()) destDir.mkdirs()

        for (file in files) {
            val subAssetPath = if (assetPath.isEmpty()) file else "$assetPath/$file"
            val subFiles = context.assets.list(subAssetPath)
            if (subFiles != null && subFiles.isNotEmpty()) {
                copyAssetFolder(context, subAssetPath, File(destDir, file))
            } else {
                val outFile = File(destDir, file)
                val assetSize = try {
                    context.assets.open(subAssetPath).use { it.available().toLong() }
                } catch (e: Exception) { -1L }

                if (!outFile.exists() || outFile.length() == 0L || (assetSize > 0 && outFile.length() != assetSize)) {
                    context.assets.open(subAssetPath).use { input ->
                        FileOutputStream(outFile).use { output ->
                            input.copyTo(output)
                        }
                    }
                }
            }
        }
    }

    fun convert(leftContext: String, readingKatakana: String, profile: String = ""): String {
        if (!isInitialized || readingKatakana.isEmpty()) return readingKatakana
        return try {
            nativeConvert(leftContext, readingKatakana, profile)
        } catch (e: Exception) {
            Log.e(TAG, "nativeConvert error", e)
            readingKatakana
        }
    }

    fun lookupDict(readingKatakana: String, prefixSearch: Boolean = true, maxCount: Int = 20): List<Candidate> {
        if (!isDictInitialized || readingKatakana.isEmpty()) return emptyList()
        return try {
            val raw = nativeLookupDict(readingKatakana, prefixSearch, maxCount)
            if (raw.isEmpty()) return emptyList()

            val lines = raw.split('\n')
            val candidates = mutableListOf<Candidate>()
            for (line in lines) {
                if (line.isEmpty()) continue
                val parts = line.split('\t')
                if (parts.size >= 3) {
                    val word = parts[0]
                    val ruby = parts[1]
                    val cost = parts[2].toFloatOrNull() ?: 0f
                    candidates.add(Candidate(word, ruby, cost, isAI = false))
                }
            }
            candidates
        } catch (e: Exception) {
            Log.e(TAG, "nativeLookupDict error", e)
            emptyList()
        }
    }

    fun close() {
        if (isInitialized) {
            nativeClose()
            isInitialized = false
            isDictInitialized = false
        }
    }
}
