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
    val isAI: Boolean = false,
    val assist: com.zeroconvert.ime.assist.AiAssistResult? = null,
    val segments: List<DictionarySegment> = emptyList(),
    val learningAllowed: Boolean = true,
    val engineCandidateId: String = "",
    val engineConsumedReading: Int? = null
) {
    fun previewForReading(reading: String): String = text + engineConsumedReading
        ?.takeIf { it in 1..reading.length }?.let { reading.substring(it) }.orEmpty()
    val isLearningTarget: Boolean get() = learningAllowed && segments.none { it.correctionKind != 0 }
    data class Key(val text: String, val reading: String, val assistType: com.zeroconvert.ime.assist.AiAssistType?)
    val key: Key get() = Key(text, reading, assist?.type)
    val itemKey: String get() = "${text.length}:$text${reading.length}:$reading:${assist?.type?.name.orEmpty()}"
}

/**
 * Small thread-safe LRU cache used to memoize dictionary lookups.
 * Keys include the reading, search limits, keyboard spans and user entries.
 */
private class LruCache<K, V>(private val maxSize: Int) {
    private val map = object : LinkedHashMap<K, V>(16, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<K, V>?): Boolean {
            return size > maxSize
        }
    }

    @Synchronized
    fun get(key: K): V? = map[key]

    @Synchronized
    fun put(key: K, value: V) {
        map[key] = value
    }

    @Synchronized
    fun clear() {
        map.clear()
    }
}

object ZenzNative {
    private const val TAG = "ZenzNative"
    private val modelEpoch = java.util.concurrent.atomic.AtomicLong()
    private val lifecycleRequests = java.util.concurrent.atomic.AtomicLong()
    private val closer = java.util.concurrent.Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "ZeroCon-model-close").apply { isDaemon = true }
    }
    private var converterPath = ""
    val modelRevision: Long get() = modelEpoch.get()

    // Returned by nativeLookupDict when the lookup was interrupted by a newer
    // keystroke; the candidate set is then incomplete and must not be cached.
    private const val ABORTED_MARKER = "\u0001"

    // Memoized dictionary lookups. Bounded to keep memory usage flat.
    private data class DictionaryCacheKey(
        val reading: String, val prefix: Boolean, val count: Int,
        val keyboardEnds: List<Int>, val userEntries: List<UserDictEntry>,
        val learnedEntries: List<MorphologyLearningMemory.Entry>
    )
    private val dictCache = LruCache<DictionaryCacheKey, List<Candidate>>(256)

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
    private external fun nativeInitAssist(converterPath: String, correctorPath: String): Int
    private external fun nativeAssistEpoch(): Long
    private external fun nativeGenerate(kind: Int, prompt: String, maxTokens: Int, timeoutMs: Int, epoch: Long): Array<String>
    @Volatile private var assistReady = 0
    private var correctorPath = ""
    private val correctorCache = LruCache<String, com.zeroconvert.ime.assist.ModelCompletion>(32)
    private val predictionCache = LruCache<String, com.zeroconvert.ime.assist.ModelCompletion>(64)

    fun assistSession(): com.zeroconvert.ime.assist.AssistInference {
        val revision = modelRevision
        val expectedEpoch = if (assistReady != 0) nativeAssistEpoch() else -1
        return object : com.zeroconvert.ime.assist.AssistInference {
            override val revision = revision
            override fun complete(model: com.zeroconvert.ime.assist.AssistModel, prompt: String, maxTokens: Int, timeoutMs: Int): com.zeroconvert.ime.assist.ModelCompletion {
                if (expectedEpoch < 0) return com.zeroconvert.ime.assist.ModelCompletion("", "unavailable")
                if (modelRevision != revision || nativeAssistEpoch() != expectedEpoch) {
                    return com.zeroconvert.ime.assist.ModelCompletion("", "cancelled")
                }
                val kind = model.ordinal
                if (assistReady and (1 shl kind) == 0) return com.zeroconvert.ime.assist.ModelCompletion("", "unavailable")
                val key = "$revision|$kind|$maxTokens|$prompt"
                val cache = if (model == com.zeroconvert.ime.assist.AssistModel.CORRECTOR) correctorCache else predictionCache
                cache.get(key)?.let { return it }
                val reply = nativeGenerate(kind, prompt, maxTokens, timeoutMs, expectedEpoch)
                if (modelRevision != revision || nativeAssistEpoch() != expectedEpoch) {
                    return com.zeroconvert.ime.assist.ModelCompletion("", "cancelled")
                }
                val completion = com.zeroconvert.ime.assist.ModelCompletion(reply.getOrElse(1) { "" }, reply.getOrElse(0) { "error" })
                if (completion.finished) cache.put(key, completion)
                return completion
            }
        }
    }

    private external fun nativeInitDict(dictDir: String): Boolean
    private external fun nativeLookupDict(reading: String, prefixSearch: Boolean, maxCount: Int, keyboardEnds: IntArray?,
                                         userReadings: Array<String>, userWords: Array<String>,
                                         learnedReadings: Array<String>, learnedWords: Array<String>,
                                         learnedIds: IntArray, learnedScores: FloatArray): String

    fun abort() {
        if (isInitialized) {
            try {
                if (BuildConfig.WINDOWS_ENGINE) com.zeroconvert.ime.engine.WindowsEngine.abort()
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

    @Synchronized
    fun loadModel(modelFile: File, displayName: String? = null): Boolean {
        lifecycleRequests.incrementAndGet()
        if (!modelFile.exists()) {
            Log.e(TAG, "loadModel failed: file not found at ${modelFile.absolutePath}")
            return false
        }
        modelEpoch.incrementAndGet()
        abort()
        val ok = try {
            val loaded = if (BuildConfig.WINDOWS_ENGINE) com.zeroconvert.ime.engine.WindowsEngine.switchModel(modelFile)
                else nativeLoadModel(modelFile.absolutePath)
            assistReady = if (loaded) nativeInitAssist(modelFile.absolutePath, correctorPath) else 0
            loaded
        } finally { modelEpoch.incrementAndGet() }
        if (ok) {
            converterPath = modelFile.absolutePath
            isInitialized = true
            currentModelName = displayName ?: modelFile.name
            Log.i(TAG, "Switched model to: $currentModelName (${modelFile.length()} bytes)")
        } else {
            Log.e(TAG, "Failed to load model: ${modelFile.absolutePath}")
        }
        return ok
    }

    @Synchronized
    fun init(context: Context): Boolean {
        lifecycleRequests.incrementAndGet()
        if (isInitialized && isDictInitialized) {
            if (assistReady == 0 && converterPath.isNotEmpty()) assistReady = nativeInitAssist(converterPath, correctorPath)
            return true
        }

        // 1. Initialize ZeroCon AI Model
        if (!isInitialized) {
            val prefs = context.getSharedPreferences("model_prefs", Context.MODE_PRIVATE)
            val savedPath = prefs.getString("selected_model_path", null)
            val savedName = prefs.getString("selected_model_name", null)

            val builtIn = ModelManager.selectedBuiltInSpec(context, savedPath)
            val modelFile = if (builtIn != null) {
                currentModelName = builtIn.displayName
                ModelManager.ensureBuiltInModel(context, builtIn)
            } else if (!savedPath.isNullOrEmpty() && File(savedPath).isFile) {
                currentModelName = savedName ?: File(savedPath).name
                File(savedPath)
            } else {
                currentModelName = ModelManager.DEFAULT_MODEL_SPEC.displayName
                ModelManager.ensureBuiltInModel(context, ModelManager.DEFAULT_MODEL_SPEC)
            }

            val logFile = File(context.filesDir, "zenz_debug.log")
            isInitialized = if (BuildConfig.WINDOWS_ENGINE) com.zeroconvert.ime.engine.WindowsEngine.initialize(context, modelFile)
                else nativeInit(modelFile.absolutePath, logFile.absolutePath)
            if (isInitialized) {
                converterPath = modelFile.absolutePath
                prefs.edit().putString("selected_model_path", modelFile.absolutePath)
                    .putString("selected_model_name", currentModelName).apply()
                modelEpoch.incrementAndGet()
                try {
                    correctorPath = ModelManager.ensureCorrector(context)?.absolutePath.orEmpty()
                    com.zeroconvert.ime.assist.AssistVocabulary.load(context)
                    com.zeroconvert.ime.assist.ReadingCheck.load(context)
                    assistReady = nativeInitAssist(modelFile.absolutePath, correctorPath)
                } finally { modelEpoch.incrementAndGet() }
            }
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
            // Dictionary contents may have changed; drop memoized lookups.
            dictCache.clear()
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
        if (BuildConfig.WINDOWS_ENGINE) {
            return com.zeroconvert.ime.engine.WindowsEngine.convertSurface(leftContext, readingKatakana, profile)
        }
        return try {
            nativeConvert(leftContext, readingKatakana, profile)
        } catch (e: Exception) {
            Log.e(TAG, "nativeConvert error", e)
            readingKatakana
        }
    }

    internal fun lookupDict(readingKatakana: String, prefixSearch: Boolean = true, maxCount: Int = 20,
                   keyboardEnds: IntArray? = null, userEntries: List<UserDictEntry> = emptyList(),
                   learnedEntries: List<MorphologyLearningMemory.Entry> = emptyList()): List<Candidate> {
        if (!isDictInitialized || readingKatakana.isEmpty()) return emptyList()
        val users = userEntries.filter {
            it.reading.codePointCount(0, it.reading.length) in 1..128 &&
                it.word.codePointCount(0, it.word.length) in 1..512 &&
                (it.reading + it.word).none { ch -> ch.isISOControl() || ch in '\uEE00'..'\uEE0F' }
        }.take(50).toList()
        val learned = learnedEntries.take(65536).toList()
        val cacheKey = DictionaryCacheKey(readingKatakana, prefixSearch, maxCount, keyboardEnds?.toList().orEmpty(), users, learned)
        val cacheable = learned.size <= 4096
        if (cacheable) dictCache.get(cacheKey)?.let { return it }
        return try {
            val raw = nativeLookupDict(readingKatakana, prefixSearch, maxCount, keyboardEnds,
                users.map { KanaKanjiConverter.toKatakana(it.reading) }.toTypedArray(), users.map { it.word }.toTypedArray(),
                learned.map { it.reading }.toTypedArray(), learned.map { it.surface }.toTypedArray(),
                IntArray(learned.size * 3) { index -> learned[index / 3].let { when (index % 3) { 0 -> it.lcid; 1 -> it.rcid; else -> it.mid } } },
                FloatArray(learned.size) { learned[it].score })
            // "\x01" marks a lookup that was aborted by a newer keystroke.
            // Its result set is incomplete, so it must not be memoized.
            if (raw == ABORTED_MARKER) return emptyList()
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
                    val segments = if (parts.size >= 4) DictionarySegments.parse(parts[3], ruby, word) else emptyList()
                    candidates.add(Candidate(word, ruby, cost, isAI = false, segments = segments))
                }
            }
            if (cacheable) dictCache.put(cacheKey, candidates)
            candidates
        } catch (e: Exception) {
            Log.e(TAG, "nativeLookupDict error", e)
            emptyList()
        }
    }

    fun close() {
        abort()
        assistReady = 0
        modelEpoch.incrementAndGet()
        val request = lifecycleRequests.incrementAndGet()
        // Service teardown must not wait for model loading or a native decode on the UI thread.
        closer.execute {
            synchronized(this) {
                if (lifecycleRequests.get() == request) {
                    if (BuildConfig.WINDOWS_ENGINE) com.zeroconvert.ime.engine.WindowsEngine.close()
                    nativeClose()
                    assistReady = 0
                    isInitialized = false
                    isDictInitialized = false
                    correctorCache.clear()
                    predictionCache.clear()
                    dictCache.clear()
                }
            }
        }
    }
}
