package com.zeroconvert.ime.engine

import android.content.Context
import android.util.JsonReader
import android.util.JsonToken
import com.zeroconvert.ime.*
import com.zeroconvert.ime.assist.*
import com.zeroconvert.ime.azookey.input.ComposingText
import com.zeroconvert.ime.azookey.input.InputPiece
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.StringReader
import java.util.concurrent.Executors

/** Android supplies editor snapshots and model I/O; Windows owns conversion policy. */
object WindowsEngine {
    private const val TAG = "WindowsEngine"
    private const val ERROR_ENVELOPE_PREFIX = "{\"error\""

    /**
     * Above this reading length the neural pass is skipped and conversion falls
     * back to the dictionary path. One batch must hold the whole prompt, so an
     * unbounded reading would otherwise exhaust the batch and KV capacity.
     * Measured on the pinned model: 480 katakana were under 512 tokens and 600
     * reached 543, so 400 characters keeps a margin for context and candidates.
     */
    private const val MAX_NEURAL_READING_CHARS = 400

    private val dispatcher = Executors.newSingleThreadExecutor { Thread(it, "ZeroCon-shared-engine") }.asCoroutineDispatcher()
    private val scope = CoroutineScope(SupervisorJob() + dispatcher)
    private data class BackgroundTask(val revision: Long?, val requiresWrite: Boolean = true, val action: () -> Unit)
    private val backgroundTasks = Channel<BackgroundTask>(128)
    init {
        scope.launch {
            for (task in backgroundTasks) {
                if (!ready || (task.revision != null && (task.revision != KanaKanjiConverter.learningHistoryRevision || (task.requiresWrite && !LearningPolicy.canWrite)))) continue
                try { task.action() }
                catch (failure: Exception) { android.util.Log.e("WindowsEngine", "Shared engine background operation failed", failure) }
            }
        }
    }
    private lateinit var application: Context
    private lateinit var dataRoot: File
    private lateinit var root: File
    private var modelPath = ""
    private var engineRevision = ""
    private var lastSettings = ""
    private val tuningGeneration = java.util.concurrent.atomic.AtomicLong(0L)
    @Volatile private var benchmarkActive = false
    @Volatile private var activeRuntimeTuning: TuningDecision? = null
    private data class ConfigureKey(
        val ai: Boolean,
        val profile: String,
        val personalize: Boolean,
        val dictionaryCorrection: Boolean,
        val predictiveConversion: Boolean,
        val learningMode: LearningMode?,
        val userDictRevision: Int,
        val modelPath: String
    )
    private var lastConfigureKey: ConfigureKey? = null
    private var backendsLoaded = false // confined to dispatcher; llama_backend_free does not unload backends
    @Volatile private var ready = false

    private external fun nativeSwift(request: String): String
    private external fun nativeAssist(request: String, inference: CompletionHost): String
    private external fun nativeAssistPolicy(request: String): String

    /** Bounded decisions only. This JNI call never acquires the conversion worker or model. */
    fun assistPolicy(request: JSONObject): JSONObject {
        val reply = JSONObject(nativeAssistPolicy(request.toString()))
        check(!reply.has("error")) { reply.optString("error") }
        return reply
    }
    private external fun nativeSetDataRoot(root: String)
    private external fun nativeSetDictionaryRoot(path: String)
    private external fun nativeConversionEpoch(): Long
    private external fun nativeAbortConversion()
    private external fun nativeLoadBackends(directory: String)
    private external fun nativePreloadAssistDictionary(path: String)

    fun abort() { nativeAbortConversion() }

    // Durations only. Never retain editor text in performance diagnostics.
    data class ConversionTiming(val queueMs: Double, val configureMs: Double,
                                val nativeMs: Double, val decodeMs: Double, val decodeCount: Long,
                                val stagesMs: Map<String, Long>)
    @Volatile var diagnosticsEnabled = false
    @Volatile var lastTiming: ConversionTiming? = null
        private set

    // Public method is called synchronously by JNI on the assistance worker.
    class CompletionHost(private val inference: AssistInference) {
        @Suppress("unused")
        fun complete(prediction: Boolean, payload: String, timeout: Long): String {
            val input = JSONObject(payload)
            val response = inference.complete(if (prediction) AssistModel.PREDICTION else AssistModel.CORRECTOR,
                input.getString("prompt"), input.getInt("n_predict"), timeout.toInt())
            return JSONObject().put("content", response.text).put("stop_type", when (response.stopReason) {
                "eos" -> "eos"; "stop" -> "stop"; else -> "limit"
            }).put("stopped_limit", !response.finished).put("stopped_eos", response.stopReason == "eos").toString()
        }
    }

    /** Called on the existing native initialization worker, never a key callback. */
    fun initialize(context: Context, selectedModel: File): Boolean = runBlocking(dispatcher) {
        application = context.applicationContext
        if (!backendsLoaded) {
            nativeLoadBackends(application.applicationInfo.nativeLibraryDir)
            backendsLoaded = true
        }
        val engineRoot = File(application.filesDir, "windows-engine").apply { mkdirs() }
        dataRoot = engineRoot
        val fingerprint = application.assets.open("shared-engine/sources.json").bufferedReader().use { it.readText() }
        val revision = java.security.MessageDigest.getInstance("SHA-256").digest(fingerprint.toByteArray())
            .joinToString("") { "%02x".format(it) }
        engineRevision = revision
        root = File(engineRoot, "runtime-$revision")
        engineRoot.listFiles()?.forEach { child ->
            if (child.isDirectory && child.name.startsWith("runtime-") && child.name != root.name) {
                runCatching { child.deleteRecursively() }
            }
        }
        if (!File(root, ".complete").exists()) {
            copyAssets("shared-engine", root)
            File(root, ".complete").writeText(revision)
        }
        nativeSetDataRoot(dataRoot.absolutePath)
        modelPath = selectedModel.absolutePath
        migrateSelections(File(engineRoot, "Azookey/LearningMemory"))
        migrateCorrections(File(engineRoot, "Azookey/ai-assist-learning.json"))
        migrateEnglishLearning(File(engineRoot, "Azookey/english-learning.json"))
        useDictionary(resolveDictionaryRoot())
        loadInputPolicy()
        lastSettings = ""
        configure(true, "", true, false)
        ready = true
        applyInitialRuntimeTuning(selectedModel)
        true
    }

    /**
     * 日本語辞書の実体を選ぶ。配布APKは辞書を同梱しないため、初回起動で
     * `files/dictionary/` へ取得した一式を使う。取得前（または旧APKの同梱辞書が
     * 残っている場合）だけ、アセットを展開した `runtime-<rev>/Dictionary` を使う。
     */
    private fun resolveDictionaryRoot(): File {
        val downloaded = com.zeroconvert.ime.model.DictionaryDownloadManager.dictionaryDir(application)
        if (com.zeroconvert.ime.model.DictionaryDownloadManager.isReady(application)) return downloaded
        return File(root, "Dictionary")
    }

    /** Swift側へ辞書ルートを渡し、再変換辞書と変換器をその辞書で作り直す。 */
    private fun useDictionary(dictionary: File) {
        nativeSetDictionaryRoot(dictionary.absolutePath)
        val reverseDictionaryPath = File(dictionary, "Reverse/reverse-v2.bin").absolutePath
        assistRequest(JSONObject().put("op", "configurePaths")
            .put("settings", File(dataRoot, "Azookey/settings.json").absolutePath)
            .put("reverse", reverseDictionaryPath))
        nativePreloadAssistDictionary(reverseDictionaryPath)
    }

    /**
     * 辞書のダウンロード完了後に呼ぶ。初期化済みでも辞書だけ差し替える
     * （Windowsの変換サーバー再起動に相当）。
     */
    fun reloadDictionary(): Boolean = runBlocking(dispatcher) {
        if (!ready) return@runBlocking false
        val dictionary = resolveDictionaryRoot()
        useDictionary(dictionary)
        checkedSwift(JSONObject().put("op", "setDictionary").put("path", dictionary.absolutePath))
        if (modelPath.isNotEmpty()) {
            scheduleRuntimeTuningIfNeeded(File(modelPath))
        }
        true
    }

    fun switchModel(model: File): Boolean = runBlocking(dispatcher) {
        if (!ready) return@runBlocking false
        modelPath = model.absolutePath
        lastSettings = ""
        configure(true, "", true, false)
        applyInitialRuntimeTuning(model)
        scheduleRuntimeTuningIfNeeded(model)
        true
    }

    private fun applyRuntimeTuning(decision: TuningDecision) {
        activeRuntimeTuning = decision
        val request = JSONObject()
            .put("op", "applyRuntimeTuning")
            .put("decodeThreads", decision.decodeThreads)
            .apply { decision.batchThreads?.let { put("batchThreads", it) } }
            .put("directModelFirst", decision.directModelFirst)
        checkedSwift(request)
    }

    private fun applyInitialRuntimeTuning(selectedModel: File) {
        val topology = RuntimeThreadTuner.readTopology()
        val revision = engineRevision.ifEmpty { RuntimeThreadTuner.engineRevision(application).also { engineRevision = it } }
        val decision = RuntimeThreadTuner.loadCached(application, revision, topology, selectedModel)
            ?: RuntimeThreadTuner.defaultFallback(topology, selectedModel.length())
        if (!benchmarkActive) {
            applyRuntimeTuning(decision)
        }
        if (decision.source == "cached") {
            android.util.Log.i(
                TAG,
                "Applied cached runtime tuning for ${selectedModel.name}: decode=${decision.decodeThreads} " +
                    "batch=${decision.batchThreads} directModelFirst=${decision.directModelFirst}"
            )
        }
    }

    fun scheduleRuntimeTuningIfNeeded(selectedModel: File) {
        if (!ready || benchmarkActive || !selectedModel.isFile) return
        val topology = RuntimeThreadTuner.readTopology()
        val revision = engineRevision.ifEmpty { RuntimeThreadTuner.engineRevision(application).also { engineRevision = it } }
        RuntimeThreadTuner.loadCached(application, revision, topology, selectedModel)?.let { cached ->
            val generation = tuningGeneration.incrementAndGet()
            scope.launch {
                if (!ready || benchmarkActive || modelPath != selectedModel.absolutePath || tuningGeneration.get() != generation) {
                    return@launch
                }
                applyRuntimeTuning(cached)
            }
            return
        }
        if (!com.zeroconvert.ime.model.DictionaryDownloadManager.isReady(application)) {
            return
        }
        val launchEpoch = nativeConversionEpoch()
        val generation = tuningGeneration.incrementAndGet()
        scope.launch {
            delay(1500)
            if (!ready || benchmarkActive || modelPath != selectedModel.absolutePath ||
                tuningGeneration.get() != generation || nativeConversionEpoch() != launchEpoch
            ) {
                return@launch
            }
            if (!com.zeroconvert.ime.model.DictionaryDownloadManager.isReady(application)) {
                return@launch
            }
            if (RuntimeThreadTuner.loadCached(application, revision, topology, selectedModel) != null) {
                return@launch
            }
            val fallback = RuntimeThreadTuner.defaultFallback(topology, selectedModel.length())
            val configs = RuntimeThreadTuner.candidateConfigs(topology, selectedModel.length())
            val response = runCatching {
                checkedSwift(
                    JSONObject()
                        .put("op", "probeRuntimeTuning")
                        .put("requestEpoch", launchEpoch)
                        .put("configs", RuntimeThreadTuner.configsToJsonArray(configs))
                )
            }.onFailure { failure ->
                android.util.Log.w(TAG, "Runtime auto-tuning probe failed; keeping fallback", failure)
            }.getOrNull()
            if (benchmarkActive || !ready || modelPath != selectedModel.absolutePath || tuningGeneration.get() != generation) {
                return@launch
            }
            if (response == null || response.optBoolean("cancelled", false)) {
                runCatching { applyRuntimeTuning(fallback) }
                return@launch
            }
            val measurements = response.optJSONArray("measurements")
                ?.let(RuntimeThreadTuner::parseMeasurements)
                .orEmpty()
            val winnerMeasurement = RuntimeThreadTuner.selectBest(measurements)
            if (winnerMeasurement == null) {
                runCatching { applyRuntimeTuning(fallback) }
                return@launch
            }
            val winner = TuningDecision(
                decodeThreads = winnerMeasurement.decodeThreads,
                batchThreads = winnerMeasurement.batchThreads,
                directModelFirst = winnerMeasurement.directModelFirst,
                meanNs = winnerMeasurement.meanNs,
                measuredConfigs = measurements.size,
                source = "probed",
            )
            RuntimeThreadTuner.saveCached(application, revision, topology, selectedModel, winner, measurements)
            applyRuntimeTuning(winner)
            android.util.Log.i(
                TAG,
                "Auto-tuned runtime threads for ${selectedModel.name}: decode=${winner.decodeThreads} " +
                    "batch=${winner.batchThreads} directModelFirst=${winner.directModelFirst} " +
                    "meanMs=${"%.2f".format(winner.meanNs / 1_000_000.0)}"
            )
        }
    }

    /** Abort any background auto-tuning probe and drain the shared dispatcher before benchmark runs. */
    fun suspendRuntimeTuningForBenchmark() {
        benchmarkActive = true
        tuningGeneration.incrementAndGet()
        nativeAbortConversion()
        runBlocking(dispatcher) { }
    }

    /** Restore IME runtime tuning after benchmark completion without resetting it out from under live typing. */
    fun resumeRuntimeTuningAfterBenchmark() {
        benchmarkActive = false
        runBlocking(dispatcher) {
            if (!ready || modelPath.isEmpty()) {
                runCatching { checkedSwift(JSONObject().put("op", "applyRuntimeTuning")) }
                return@runBlocking
            }
            val modelFile = File(modelPath)
            val topology = RuntimeThreadTuner.readTopology()
            val revision = engineRevision.ifEmpty { RuntimeThreadTuner.engineRevision(application).also { engineRevision = it } }
            val decision = RuntimeThreadTuner.loadCached(application, revision, topology, modelFile)
                ?: activeRuntimeTuning
                ?: RuntimeThreadTuner.defaultFallback(topology, modelFile.length())
            runCatching { applyRuntimeTuning(decision) }
            if (RuntimeThreadTuner.loadCached(application, revision, topology, modelFile) == null &&
                com.zeroconvert.ime.model.DictionaryDownloadManager.isReady(application)
            ) {
                scheduleRuntimeTuningIfNeeded(modelFile)
            }
        }
    }

    fun close() {
        ready = false
        benchmarkActive = false
        activeRuntimeTuning = null
        tuningGeneration.incrementAndGet()
        runBlocking(dispatcher) {
            runCatching { checkedSwift(JSONObject().put("op", "applyRuntimeTuning")) }
            checkedSwift(JSONObject().put("op", "close"))
            lastSettings = ""
        }
    }

    fun readingForSurface(surface: String): String? = runBlocking(dispatcher) {
        if (!ready) return@runBlocking null
        configure(false, "", true, false)
        val readings = checkedSwift(JSONObject().put("op", "readings").put("surface", surface)).getJSONArray("readings")
        if (readings.length() == 0) null else KanaKanjiConverter.toHiragana(readings.getString(0))
    }

    fun convertSurface(context: String, reading: String, profile: String): String = runBlocking {
        val input = ComposingText().apply {
            insertAtCursorPosition(KanaKanjiConverter.toHiragana(reading), com.zeroconvert.ime.azookey.input.InputStyle.DIRECT)
        }
        // Long readings fall back to the dictionary inside candidates(), so the
        // surface still resolves instead of being returned as raw katakana.
        candidates(input, context, true, profile, LearningPolicy.canRead, false)
            .firstOrNull { it.coversReading(input.convertTarget) }?.previewForReading(input.convertTarget) ?: reading
    }

    private fun copyAssets(asset: String, destination: File) {
        val children = application.assets.list(asset).orEmpty()
        if (children.isEmpty()) {
            destination.parentFile?.mkdirs()
            application.assets.open(asset).use { input -> destination.outputStream().use { input.copyTo(it) } }
        } else {
            destination.mkdirs()
            children.forEach { copyAssets("$asset/$it", File(destination, it)) }
        }
    }

    private fun migrateSelections(directory: File) {
        directory.mkdirs()
        val destination = File(directory, "selection-overrides.json")
        if (destination.exists()) return
        val prefs = application.getSharedPreferences("zerocon_user_data", Context.MODE_PRIVATE)
        val normalized = JSONObject()
        // V2 entries keep a reading-to-outputs list; the engine stores the same shape
        // with katakana rubies, so the newest surface of each reading carries over.
        prefs.getString("learning_memory_v2_json", null)?.let { json ->
            runCatching { JSONObject(json) }.getOrNull()?.let { source ->
                source.keys().forEach { ruby ->
                    val outputs = source.optJSONArray(ruby) ?: return@forEach
                    val surfaces = JSONArray()
                    for (index in 0 until outputs.length()) {
                        val surface = outputs.optJSONObject(index)?.optString("s")?.trim().orEmpty()
                        if (surface.isNotEmpty()) surfaces.put(surface)
                    }
                    if (surfaces.length() > 0) normalized.put(KanaKanjiConverter.toKatakana(ruby), surfaces)
                }
            }
        }
        if (normalized.length() == 0) {
            val legacy = runCatching { JSONObject(prefs.getString("learning_memory_json", null) ?: "{}") }.getOrNull()
            legacy?.keys()?.forEach { ruby ->
                val surface = legacy.optString(ruby, "").trim()
                if (surface.isNotEmpty()) normalized.put(KanaKanjiConverter.toKatakana(ruby), surface)
            }
        }
        atomicWrite(destination, normalized.toString())
        prefs.edit().remove("learning_memory_json").remove("learning_memory_v2_json").apply()
    }

    private fun migrateCorrections(destination: File) {
        if (destination.exists()) return
        val legacy = JSONArray(application.getSharedPreferences("ai_assist_learning", Context.MODE_PRIVATE).getString("entries", "[]"))
        val entries = JSONArray()
        for (index in 0 until legacy.length()) {
            val entry = legacy.getJSONObject(index)
            entries.put(JSONObject().put("reading", entry.getString("r")).put("surface", entry.getString("s"))
                .put("count", entry.optInt("confirmed", 0)).put("updated_at_ms", entry.optLong("updated_at_ms", 0)))
        }
        atomicWrite(destination, JSONObject().put("version", 1).put("entries", entries).toString())
    }

    private fun migrateEnglishLearning(destination: File) {
        if (destination.exists()) return
        destination.parentFile?.mkdirs()
        val legacyJson = application
            .getSharedPreferences("zerocon_english_data", Context.MODE_PRIVATE)
            .getString("english_learning_memory_json", null)
        atomicWrite(destination, buildMigratedEnglishLearningJson(legacyJson))
    }

    internal fun buildMigratedEnglishLearningJson(
        legacyJson: String?,
        nowMs: Long = System.currentTimeMillis()
    ): String {
        val wordsByKey = LinkedHashMap<String, JSONObject>()
        val prefixOverrides = JSONArray()
        if (!legacyJson.isNullOrBlank()) {
            runCatching { JSONObject(legacyJson) }.getOrNull()?.let { source ->
                var offset = 1L
                val keys = source.keys()
                while (keys.hasNext()) {
                    val rawPrefix = keys.next().trim()
                    val rawWord = source.optString(rawPrefix, "").trim()
                    if (rawWord.length !in 2..64 || !rawWord.all { it in 'A'..'Z' || it in 'a'..'z' }) {
                        continue
                    }
                    val ts = (nowMs.coerceAtLeast(0) + offset++)
                    val wordKey = rawWord.lowercase(java.util.Locale.ROOT)
                    val existing = wordsByKey[wordKey]
                    if (existing == null) {
                        wordsByKey[wordKey] = JSONObject()
                            .put("word", rawWord)
                            .put("count", 1)
                            .put("explicit_count", 1)
                            .put("in_dictionary", false)
                            .put("updated_at_ms", ts)
                    } else {
                        existing.put("word", rawWord)
                        existing.put("count", existing.optInt("count", 1) + 1)
                        existing.put("explicit_count", existing.optInt("explicit_count", 1) + 1)
                        existing.put("updated_at_ms", ts)
                    }
                    if (rawPrefix.length in 1..64 &&
                        rawPrefix.all { it in 'A'..'Z' || it in 'a'..'z' } &&
                        (rawPrefix.lowercase(java.util.Locale.ROOT) != wordKey || rawWord != rawPrefix)
                    ) {
                        prefixOverrides.put(
                            JSONObject()
                                .put("prefix", rawPrefix.lowercase(java.util.Locale.ROOT))
                                .put("word", rawWord)
                                .put("count", 1)
                                .put("updated_at_ms", ts)
                        )
                    }
                }
            }
        }
        val words = JSONArray()
        for (entry in wordsByKey.values) words.put(entry)
        return JSONObject()
            .put("version", 1)
            .put("words", words)
            .put("prefix_overrides", prefixOverrides)
            .toString()
    }

    private fun atomicWrite(destination: File, text: String) {
        val atomic = android.util.AtomicFile(destination)
        val stream = atomic.startWrite()
        try { stream.write(text.toByteArray(Charsets.UTF_8)); atomic.finishWrite(stream) }
        catch (failure: Exception) { atomic.failWrite(stream); throw failure }
    }

    private fun configure(ai: Boolean, profile: String, personalize: Boolean, dictionaryCorrection: Boolean,
                          predictiveConversion: Boolean = true) {
        val effectiveLearning = if (personalize) LearningPolicy.mode else null
        val dictRevision = if (personalize) KanaKanjiConverter.userDictRevision else -1
        val key = ConfigureKey(ai, profile, personalize, dictionaryCorrection, predictiveConversion, effectiveLearning, dictRevision, modelPath)
        if (lastSettings.isNotEmpty() && key == lastConfigureKey) return
        val users = JSONArray()
        if (personalize) KanaKanjiConverter.getUserDictionary(application).take(MAX_USER_DICTIONARY_ENTRIES).forEach {
            users.put(JSONObject().put("reading", it.reading).put("word", it.word).put("pos", it.pos.key))
        }
        val settings = JSONObject().put("zenzai", JSONObject().put("enable", ai).put("backend", "cpu").put("profile", profile))
            .put("general", JSONObject()
                .put("experimental_typo_correction", dictionaryCorrection)
                .put("predictive_conversion", predictiveConversion))
            .put("learning", JSONObject().put("mode", if (!personalize) "disabled" else when (LearningPolicy.mode) {
                LearningMode.DISABLED -> "disabled"; LearningMode.READ_ONLY -> "read_only"; else -> "enabled"
            })).put("user_dictionary", JSONObject().put("entries", users)).toString()
        if (settings != lastSettings) {
            val settingsFile = File(application.filesDir, "windows-engine/Azookey/settings.json")
            settingsFile.parentFile?.mkdirs()
            atomicWrite(settingsFile, settings)
            assistRequest(JSONObject().put("op", "setVocabulary").put("entries", users))
            checkedSwift(JSONObject().put("op", "configure").put("root", root.absolutePath).put("modelPath", modelPath))
            lastSettings = settings
        }
        lastConfigureKey = key
    }

    /**
     * Fetches the shared input policy once per process.
     *
     * Losing the payload is not fatal: `InputPolicy` keeps its compiled fallback
     * tables, and the parity tests compare those with the same specification the
     * Rust source is tested against.
     */
    private fun loadInputPolicy() {
        val payload = runCatching { assistReply(JSONObject().put("op", "inputPolicy")) }
            .onFailure { android.util.Log.w(TAG, "Shared input policy unavailable", it) }
            .getOrNull() ?: return
        val parsed = runCatching { JSONObject(payload) }
            .onFailure { android.util.Log.w(TAG, "Shared input policy is not valid JSON", it) }
            .getOrNull() ?: return
        val marks = parsed.optJSONArray("punctuation") ?: return
        val widths = parsed.optJSONArray("symbolFullwidth") ?: return
        val readingPunctuation = (0 until marks.length()).mapNotNull { index ->
            marks.optJSONObject(index)?.let { entry ->
                val from = entry.optString("from").singleOrNull() ?: return@let null
                val to = entry.optString("to").singleOrNull() ?: return@let null
                from to to
            }
        }
        val defaultFullwidth = (0 until widths.length()).mapNotNull { index ->
            widths.optJSONObject(index)?.let { entry ->
                val symbol = entry.optString("symbol").takeIf(String::isNotEmpty) ?: return@let null
                if (!entry.has("fullwidth")) return@let null
                symbol to entry.getBoolean("fullwidth")
            }
        }
        val applied = InputPolicy.apply(
            version = parsed.optInt("version", -1),
            immediateCommit = parsed.optString("immediateCommit").takeIf(String::isNotEmpty),
            readingPunctuation = readingPunctuation,
            defaultFullwidth = defaultFullwidth
        )
        if (!applied) android.util.Log.w(TAG, "Shared input policy rejected; using fallback tables")
    }

    private fun checkedSwift(request: JSONObject): JSONObject {
        val result = JSONObject(nativeSwift(request.toString()))
        check(!result.has("error")) { "Shared conversion engine: ${result.optString("error")}" }
        return result
    }

    /**
     * Raw assist reply, or null when the engine declined the request. The Rust
     * bridge answers oversized requests with an error envelope, so refusal must
     * stay recoverable instead of surfacing as a parse exception.
     */
    private fun assistReply(raw: String): String? {
        if (raw.isEmpty() || raw == "null") return null
        if (!raw.startsWith(ERROR_ENVELOPE_PREFIX)) return raw
        android.util.Log.w(TAG, "Assist request declined: " + runCatching { JSONObject(raw).optString("error") }.getOrNull())
        return null
    }

    private fun assistReply(request: JSONObject): String? = assistReply(assistRequest(request))

    internal suspend fun candidates(input: ComposingText, context: String, ai: Boolean, profile: String,
                           personalize: Boolean, dictionaryCorrection: Boolean,
                           predictiveConversion: Boolean = true,
                           continuation: ConversionContinuation? = null,
                           touchBiases: List<TouchKeyBias> = emptyList()): List<Candidate> {
        if (input.convertTargetCursorPosition <= 0) return emptyList()
        val queued = System.nanoTime()
        val epoch = nativeConversionEpoch()
        return withContext(dispatcher) {
            ensureActive()
            if (epoch != nativeConversionEpoch()) throw CancellationException("Superseded conversion")
            val dequeued = System.nanoTime()
            check(ready) { "Shared conversion engine is not ready" }
            // A reading this long cannot fit one decode batch. Ask for the same
            // conversion without the neural pass so the dictionary still answers.
            val neuralAllowed = ai && input.convertTarget.count() <= MAX_NEURAL_READING_CHARS
            if (ai && !neuralAllowed) {
                android.util.Log.i(TAG, "Skipping neural conversion for a long reading")
            }
            configure(neuralAllowed, profile, personalize, dictionaryCorrection, predictiveConversion)
            ensureActive()
            val configured = System.nanoTime()
            val elements = JSONArray()
            engineInputElements(input).forEach { element ->
                elements.put(JSONObject().put("style", element.style)
                    .put("separator", element.separator).put("text", element.text))
            }
            val rawReply = nativeSwift(JSONObject().put("op", "convert").put("elements", elements)
                .put("touchEvidence", engineTouchEvidence(input, touchBiases))
                .put("cursorUtf16", input.convertTargetCursorPosition).put("expectedReading", input.convertTarget)
                .put("continuationReading", continuation?.reading.orEmpty())
                .put("continuationSurface", continuation?.surface.orEmpty())
                .put("context", context).put("allowAI", neuralAllowed)
                .put("requestEpoch", epoch).put("diagnostics", diagnosticsEnabled).toString())
            val parsed = parseConvertResponse(rawReply, input, neuralAllowed)
                ?: throw CancellationException("Native conversion superseded")
            ensureActive()
            lastTiming = ConversionTiming(
                (dequeued - queued) / 1e6,
                (configured - dequeued) / 1e6,
                (System.nanoTime() - configured) / 1e6,
                parsed.decodeNs / 1e6,
                parsed.decodeCount,
                parsed.stagesMs,
            )
            val converted = parsed.candidates
            val macrosRaw = assistReply(JSONObject().put("op", "macros").put("input", input.convertTargetBeforeCursor))
            val macroTexts = macrosRaw?.let { raw ->
                val array = runCatching { JSONArray(raw) }.getOrNull()
                if (array == null) emptyList()
                else (0 until array.length()).mapNotNull { array.optString(it).takeIf(String::isNotEmpty) }
            }.orEmpty()
            MacroCandidateMerge.merge(converted, macroTexts, KanaKanjiConverter.toKatakana(input.convertTargetBeforeCursor))
        }
    }

    private data class ParsedConvertResponse(
        val candidates: List<Candidate>,
        val decodeNs: Long,
        val decodeCount: Long,
        val stagesMs: Map<String, Long>,
    )

    private fun JsonReader.nextStringOrEmpty(): String {
        if (peek() == JsonToken.NULL) {
            nextNull()
            return ""
        }
        return nextString()
    }

    private fun parseConvertResponse(
        raw: String,
        input: ComposingText,
        neuralAllowed: Boolean,
    ): ParsedConvertResponse? {
        val reading = input.convertTarget
        val cursor = input.convertTargetCursorPosition
        val katakanaReading = KanaKanjiConverter.toKatakana(reading)
        val parsedCandidates = ArrayList<Candidate>(128)
        val seen = HashSet<Pair<String, Int>>(128)
        var cancelled = false
        var decodeNs = 0L
        var decodeCount = 0L
        var stagesMs: Map<String, Long> = emptyMap()

        JsonReader(StringReader(raw)).use { reader ->
            reader.beginObject()
            while (reader.hasNext()) {
                when (reader.nextName()) {
                    "error" -> throw IllegalStateException("Shared conversion engine: ${reader.nextStringOrEmpty()}")
                    "cancelled" -> cancelled = reader.nextBoolean()
                    "candidates" -> {
                        reader.beginArray()
                        while (reader.hasNext()) {
                            var text = ""
                            var rawConsumed = -1
                            var candidateId = "0"
                            var learningAllowed = false
                            var predictionReading = ""
                            var readingCompletion = false
                            reader.beginObject()
                            while (reader.hasNext()) {
                                when (reader.nextName()) {
                                    "text" -> text = reader.nextStringOrEmpty()
                                    "consumedReadingUtf16" -> rawConsumed = reader.nextInt()
                                    "candidateId" -> candidateId = reader.nextStringOrEmpty()
                                    "learningAllowed" -> learningAllowed = reader.nextBoolean()
                                    "predictionReading" -> predictionReading = reader.nextStringOrEmpty()
                                    "readingCompletion" -> readingCompletion = reader.nextBoolean()
                                    else -> reader.skipValue()
                                }
                            }
                            reader.endObject()
                            val consumed = safeConsumedReading(
                                reading = reading,
                                rawConsumed = rawConsumed,
                                cursor = cursor,
                            )
                            if (consumed == null) {
                                android.util.Log.w(
                                    TAG,
                                    "Ignoring out-of-bounds candidate consumedReadingUtf16=$rawConsumed cursor=$cursor"
                                )
                                continue
                            }
                            if (!seen.add(text to consumed)) continue
                            parsedCandidates.add(
                                Candidate(
                                    text = text,
                                    reading = katakanaReading.take(consumed),
                                    isAI = neuralAllowed,
                                    learningAllowed = learningAllowed,
                                    engineCandidateId = candidateId.takeUnless { it == "0" }.orEmpty(),
                                    engineConsumedReading = consumed,
                                    enginePredictionReading = predictionReading,
                                    isReadingCompletion = readingCompletion,
                                )
                            )
                        }
                        reader.endArray()
                    }
                    "timing" -> {
                        reader.beginObject()
                        while (reader.hasNext()) {
                            when (reader.nextName()) {
                                "decodeNs" -> decodeNs = reader.nextLong()
                                "decodeCount" -> decodeCount = reader.nextLong()
                                "stagesMs" -> {
                                    val stageMap = HashMap<String, Long>(12)
                                    reader.beginObject()
                                    while (reader.hasNext()) {
                                        stageMap[reader.nextName()] = reader.nextLong()
                                    }
                                    reader.endObject()
                                    stagesMs = stageMap
                                }
                                else -> reader.skipValue()
                            }
                        }
                        reader.endObject()
                    }
                    else -> reader.skipValue()
                }
            }
            reader.endObject()
        }
        if (cancelled) return null
        return ParsedConvertResponse(
            candidates = parsedCandidates,
            decodeNs = decodeNs,
            decodeCount = decodeCount,
            stagesMs = stagesMs,
        )
    }

    internal data class PostCompositionResult(
        val leftSurface: String,
        val predictions: List<Candidate>,
        val accepted: Boolean = false,
        val terminal: Boolean = false
    )

    internal fun parsePostCompositionResult(response: JSONObject): PostCompositionResult {
        val leftSurface = response.optString("leftSurface", "")
        val predictionsJson = response.optJSONArray("predictions")
        val predictions = buildList {
            if (predictionsJson != null) {
                for (index in 0 until predictionsJson.length()) {
                    val entry = predictionsJson.optJSONObject(index) ?: continue
                    val id = entry.optString("id", "").takeIf { it.isNotEmpty() && it != "0" } ?: continue
                    val text = entry.optString("text", "").takeIf { it.isNotEmpty() } ?: continue
                    val reading = entry.optString("reading", "")
                    add(
                        Candidate(
                            text = text,
                            reading = KanaKanjiConverter.toKatakana(reading),
                            isAI = false,
                            learningAllowed = false,
                            engineCandidateId = "",
                            enginePostCompositionId = id
                        )
                    )
                }
            }
        }
        return PostCompositionResult(
            leftSurface = leftSurface,
            predictions = predictions,
            accepted = response.optBoolean("accepted", false),
            terminal = response.optBoolean("terminal", false)
        )
    }

    internal suspend fun postCompositionPredictions(
        candidateId: String,
        committedText: String,
        committedReading: String,
        context: String,
        postCompositionPrediction: Boolean
    ): PostCompositionResult {
        if (!postCompositionPrediction || committedText.isEmpty()) {
            return PostCompositionResult(committedText, emptyList())
        }
        if (!ready) return PostCompositionResult(committedText, emptyList())
        val epoch = nativeConversionEpoch()
        return withContext(dispatcher) {
            ensureActive()
            if (!ready) return@withContext PostCompositionResult(committedText, emptyList())
            if (epoch != nativeConversionEpoch()) throw CancellationException("Superseded post-composition prediction")
            val response = checkedSwift(
                JSONObject()
                    .put("op", "postComposition")
                    .put("candidateId", candidateId)
                    .put("committedText", committedText)
                    .put("committedReading", committedReading)
                    .put("context", context)
                    .put("postCompositionPrediction", postCompositionPrediction)
                    .put("requestEpoch", epoch)
            )
            if (response.optBoolean("cancelled")) throw CancellationException("Post-composition prediction cancelled")
            parsePostCompositionResult(response)
        }
    }

    internal suspend fun selectPostCompositionPrediction(
        predictionId: String,
        context: String,
        allowLearning: Boolean,
        postCompositionPrediction: Boolean
    ): PostCompositionResult {
        if (!postCompositionPrediction || predictionId.isEmpty()) {
            return PostCompositionResult("", emptyList(), accepted = false, terminal = true)
        }
        if (!ready) {
            return PostCompositionResult("", emptyList(), accepted = false, terminal = true)
        }
        val epoch = nativeConversionEpoch()
        return withContext(dispatcher) {
            ensureActive()
            if (!ready) {
                return@withContext PostCompositionResult("", emptyList(), accepted = false, terminal = true)
            }
            if (epoch != nativeConversionEpoch()) throw CancellationException("Superseded post-composition selection")
            val response = checkedSwift(
                JSONObject()
                    .put("op", "selectPostComposition")
                    .put("predictionId", predictionId)
                    .put("context", context)
                    .put("allowLearning", allowLearning)
                    .put("postCompositionPrediction", postCompositionPrediction)
                    .put("requestEpoch", epoch)
            )
            if (response.optBoolean("cancelled")) throw CancellationException("Post-composition selection cancelled")
            parsePostCompositionResult(response)
        }
    }

    fun assist(op: String, context: String, input: String, baseline: String, inference: AssistInference,
               baselineReading: String = "", rawInput: String = "", touchHints: String = "",
               inputMode: String = "", autoCorrectionStrength: String = "standard",
               originReading: String = "", originRaw: String = "", originTouchHints: String = "",
               appliedPrefix: String = ""): AiAssistResult? {
        if (!ready) return null
        val requestJson = JSONObject().put("op", op)
            .put("revision", (inference.revision shl 32) xor AssistVocabulary.revision)
            .put("readLearning", LearningPolicy.canRead)
            .put("context", context).put("input", input).put("baseline", baseline)
            .put("baselineReading", baselineReading)
        if (rawInput.isNotEmpty()) requestJson.put("raw", rawInput)
        if (touchHints.isNotEmpty()) requestJson.put("touchHints", touchHints)
        if (inputMode.isNotEmpty()) requestJson.put("inputMode", inputMode)
        if (op == "correction") {
            if (autoCorrectionStrength.isNotEmpty()) {
                requestJson.put("autoCorrectionStrength", autoCorrectionStrength)
            }
            if (originReading.isNotEmpty()) requestJson.put("originReading", originReading)
            if (originRaw.isNotEmpty()) requestJson.put("originRaw", originRaw)
            if (originTouchHints.isNotEmpty()) requestJson.put("originTouchHints", originTouchHints)
            if (appliedPrefix.isNotEmpty()) requestJson.put("appliedPrefix", appliedPrefix)
        }
        val raw = assistReply(nativeAssist(requestJson.toString(),
            CompletionHost(inference))) ?: return null
        val result = JSONObject(raw)
        val correction = op == "correction"
        if (correction && result.optBoolean("revertToOrigin", false)) {
            return AiAssistResult(
                type = AiAssistType.TYPO_CORRECTION,
                text = "",
                label = "",
                relevance = result.optInt("relevance", 100),
                sourceReading = input,
                autoApply = true,
                sourceSurface = baseline,
                revertToOrigin = true,
            )
        }
        val text = result.optString("text")
        if (text.isEmpty()) return null
        return AiAssistResult(if (correction) AiAssistType.TYPO_CORRECTION else AiAssistType.PREDICTION,
            text, "${if (correction) "💡" else "🔮"} $text", result.optInt("relevance"),
            sourceReading = input, continuation = result.optString("continuation"),
            autoApply = result.optBoolean("autoApply"), sourceSurface = baseline)
    }

    private fun assistRequest(request: JSONObject): String = nativeAssist(request.toString(), CompletionHost(object : AssistInference {
        override val revision = -1L
        override fun complete(model: AssistModel, prompt: String, maxTokens: Int, timeoutMs: Int) = ModelCompletion("", "unavailable")
    }))

    fun recordCorrection(reading: String, surface: String, canRecord: () -> Boolean) {
        if (!canRecord() || !LearningPolicy.canWrite) return
        backgroundTasks.trySend(BackgroundTask(KanaKanjiConverter.learningHistoryRevision) {
            if (!canRecord()) return@BackgroundTask
            assistRequest(JSONObject().put("op", "learn").put("input", reading).put("baseline", surface))
        })
    }

    /** Deletes one learned typo correction from the shared store. */
    fun forgetCorrection(reading: String, surface: String) {
        backgroundTasks.trySend(BackgroundTask(KanaKanjiConverter.learningHistoryRevision, requiresWrite = false) {
            if (!LearningPolicy.canRead) return@BackgroundTask
            assistRequest(JSONObject().put("op", "forget").put("input", reading).put("baseline", surface))
        })
    }

    /** Deletes every learned typo correction from the shared store. */
    fun clearCorrections() {
        backgroundTasks.trySend(BackgroundTask(KanaKanjiConverter.learningHistoryRevision, requiresWrite = false) {
            assistRequest(JSONObject().put("op", "forgetAll"))
        })
    }

    data class EnglishLearnedSuggestion(
        val text: String,
        val learned: Boolean,
        val key: String
    )

    data class EnglishSuggestResponse(
        val suggestions: List<EnglishLearnedSuggestion> = emptyList(),
        val promotedPrefix: Boolean = false
    )

    fun isReady(): Boolean = ready

    fun englishSuggest(
        prefix: String,
        limit: Int = 8,
        readLearning: Boolean = LearningPolicy.canRead
    ): EnglishSuggestResponse {
        if (!ready || !readLearning || prefix.isEmpty()) return EnglishSuggestResponse()
        val reply = runCatching {
            assistReply(
                assistRequest(
                    JSONObject()
                        .put("op", "englishSuggest")
                        .put("input", prefix)
                        .put("limit", limit)
                        .put("readLearning", true)
                )
            )
        }.getOrNull() ?: return EnglishSuggestResponse()
        val obj = runCatching { JSONObject(reply) }.getOrNull() ?: return EnglishSuggestResponse()
        val arr = obj.optJSONArray("suggestions") ?: JSONArray()
        val suggestions = ArrayList<EnglishLearnedSuggestion>(arr.length())
        for (i in 0 until arr.length()) {
            val item = arr.optJSONObject(i) ?: continue
            val text = item.optString("text")
            if (text.isNotEmpty()) {
                suggestions.add(
                    EnglishLearnedSuggestion(
                        text = text,
                        learned = item.optBoolean("learned", true),
                        key = item.optString("key")
                    )
                )
            }
        }
        return EnglishSuggestResponse(suggestions, obj.optBoolean("promotedPrefix", false))
    }

    /**
     * 展開済みのwordfreq msgpackを読み、端末内でフィルタしてキャッシュを書く。
     * 取得（HTTPS）とgzip展開は `EnglishDictionaryManager`、抽出と並べ替えは共有Rustの責務。
     * 戻り値は書き込んだ語数。失敗時は -1。
     */
    fun importEnglishDictionary(
        sourcePath: String,
        destinationPath: String,
        limit: Int = 30_000
    ): Int = runBlocking(dispatcher) {
        if (!ready) return@runBlocking -1
        val reply = assistReply(
            assistRequest(
                JSONObject()
                    .put("op", "englishDictionaryImport")
                    .put("source", sourcePath)
                    .put("destination", destinationPath)
                    .put("limit", limit)
            )
        ) ?: return@runBlocking -1
        runCatching { JSONObject(reply).optInt("words", -1) }.getOrDefault(-1)
    }

    fun recordEnglishWord(
        prefix: String,
        word: String,
        explicitSelection: Boolean,
        inDictionary: Boolean,
        expectedRevision: Long = KanaKanjiConverter.learningHistoryRevision
    ) {
        if (word.isEmpty()) return
        backgroundTasks.trySend(BackgroundTask(expectedRevision) {
            assistRequest(
                JSONObject()
                    .put("op", "englishRecord")
                    .put("prefix", prefix)
                    .put("word", word)
                    .put("explicitSelection", explicitSelection)
                    .put("inDictionary", inDictionary)
            )
        })
    }

    fun forgetEnglishWordBlocking(word: String, prefix: String = ""): Boolean = runBlocking(dispatcher) {
        if (!ready || !LearningPolicy.canRead || word.isEmpty()) return@runBlocking false
        runCatching {
            val reply = assistReply(
                assistRequest(
                    JSONObject()
                        .put("op", "englishForget")
                        .put("word", word)
                        .put("prefix", prefix)
                )
            )
            reply?.let { JSONObject(it).optBoolean("forgot", false) } ?: false
        }.getOrDefault(false)
    }

    fun clearEnglishLearningBlocking(): Boolean = runBlocking(dispatcher) {
        if (!ready) return@runBlocking false
        runCatching {
            assistReply(assistRequest(JSONObject().put("op", "englishForgetAll"))) != null
        }.getOrDefault(false)
    }

    fun learningEnglishCount(): Int = runBlocking(dispatcher) {
        if (!ready) return@runBlocking 0
        runCatching { assistReply(assistRequest(JSONObject().put("op", "learningStats"))) }
            .getOrNull()?.let { JSONObject(it).optInt("englishCount", 0) } ?: 0
    }

    fun englishPromotedWords(readLearning: Boolean = LearningPolicy.canRead): List<String> {
        if (!ready || !readLearning) return emptyList()
        val reply = runCatching {
            assistReply(
                assistRequest(
                    JSONObject()
                        .put("op", "englishPromotedWords")
                        .put("readLearning", true)
                )
            )
        }.getOrNull() ?: return emptyList()
        val arr = runCatching { JSONObject(reply).optJSONArray("words") }.getOrNull() ?: return emptyList()
        return List(arr.length()) { arr.optString(it) }.filter { it.isNotEmpty() }
    }

    /** Number of learned typo corrections the shared store holds. */
    fun learningTypoCount(): Int = runBlocking(dispatcher) {
        if (!ready) return@runBlocking 0
        runCatching { assistReply(assistRequest(JSONObject().put("op", "learningStats"))) }
            .getOrNull()?.let { JSONObject(it).optInt("typoCount", 0) } ?: 0
    }

    /**
     * Returns the clause prefix the shared rule may commit, or null.
     *
     * This is the only auto-commit rule: Android has no second implementation, so
     * a change here cannot drift from the Windows client. A not-ready engine
     * declines instead of throwing, because this runs on the inference result path.
     */
    fun autoCommit(raw: String, reading: String, history: List<InferenceSnapshot>, safeBoundary: (Int) -> Boolean): AutoCommitCandidate? {
        if (!ready) return null
        val entries = JSONArray()
        history.forEach { snapshot ->
            entries.put(JSONObject().put("rawCount", snapshot.rawInputCount)
                .put("readingCount", snapshot.readingHiragana.codePointCount(0, snapshot.readingHiragana.length))
                .put("surface", snapshot.aiSurface))
        }
        val result = assistReply(JSONObject().put("op", "autoCommit").put("raw", raw).put("input", reading)
            .put("baseline", history.lastOrNull()?.aiSurface.orEmpty()).put("history", entries)) ?: return null
        val candidate = runCatching { JSONObject(result) }.getOrNull() ?: return null
        val length = safeAutoCommitLength(reading, candidate.optInt("readingLength", -1))
        if (length <= 0 || !safeBoundary(length)) return null
        return AutoCommitCandidate(length, reading.take(length), candidate.optString("surface"))
    }

    internal fun safeConsumedReading(reading: String, rawConsumed: Int, cursor: Int): Int? {
        if (cursor <= 0 || rawConsumed !in 1..cursor || rawConsumed > reading.length) return null
        val snapped = ComposingEditorState.snapBoundary(reading, rawConsumed)
        return snapped.takeIf { it in 1..cursor }
    }

    internal fun safeAutoCommitLength(reading: String, readingLength: Int): Int {
        if (readingLength <= 0 || reading.isEmpty()) return 0
        val count = reading.codePointCount(0, reading.length)
        val clamped = readingLength.coerceIn(0, count)
        return reading.offsetByCodePoints(0, clamped)
    }

    /**
     * Queues a learning commit. [expectedRevision] is the history generation seen
     * when the selection was committed, so a reset in between drops it.
     */
    fun commit(
        candidateId: String,
        expectedRevision: Long = KanaKanjiConverter.learningHistoryRevision,
        explicitSelection: Boolean = false,
        canRecord: () -> Boolean = { LearningPolicy.canWrite }
    ) {
        if (candidateId.isEmpty() || !canRecord()) return
        backgroundTasks.trySend(BackgroundTask(expectedRevision) {
            if (!canRecord()) return@BackgroundTask
            val request = JSONObject().put("op", "commit").put("candidateId", candidateId)
                .put("explicitSelection", explicitSelection)
            check(checkedSwift(request).optBoolean("learned")) {
                "Shared engine candidate is no longer available for learning"
            }
        })
    }

    /**
     * Deletes one published candidate's conversion learning.
     *
     * Addressed by the engine's own candidate id, so the deletion uses the same
     * dictionary path the engine learned through. Returns true only when the
     * engine confirmed the removal.
     */
    fun forgetCandidate(candidateId: String): Boolean = runBlocking(dispatcher) {
        if (!ready || candidateId.isEmpty()) return@runBlocking false
        runCatching {
            checkedSwift(JSONObject().put("op", "forget").put("candidateId", candidateId))
                .optBoolean("forgot", false)
        }.getOrElse {
            android.util.Log.e(TAG, "Shared engine learning deletion failed", it)
            false
        }
    }

    /**
     * Applies the deletion guards and single-shot consume without writing any store.
     *
     * Device checks need the id contract of [forgetCandidate] but must not rewrite
     * the user's learning memory, so they use this instead. The id is consumed in
     * the in-memory cache exactly as a real deletion would consume it.
     */
    fun validateCandidateDeletion(candidateId: String): Boolean = runBlocking(dispatcher) {
        if (!ready || candidateId.isEmpty()) return@runBlocking false
        runCatching {
            checkedSwift(JSONObject().put("op", "validateForget").put("candidateId", candidateId))
                .optBoolean("valid", false)
        }.getOrElse {
            android.util.Log.e(TAG, "Shared engine learning deletion validation failed", it)
            false
        }
    }

    /** Learning counters for the settings screen. Null when the engine is not ready. */
    fun learningStats(): JSONObject? = runBlocking(dispatcher) {
        if (!ready) return@runBlocking null
        runCatching {
            checkedSwift(JSONObject().put("op", "learningStats"))
        }.getOrNull()
    }

    /** Learned readings in the shared selection store, for the settings summary. */
    fun learningSelectionCount(): Int = runBlocking(dispatcher) {
        if (!ready) return@runBlocking 0
        runCatching { checkedSwift(JSONObject().put("op", "learningStats")).optInt("selectionCount", 0) }.getOrDefault(0)
    }

    /**
     * Resets conversion learning and waits for the shared engine to answer, so the
     * settings screen reports the real outcome instead of an optimistic one.
     */
    fun resetLearningBlocking(): Boolean = runBlocking(dispatcher) {
        if (!ready) return@runBlocking false
        runCatching {
            checkedSwift(JSONObject().put("op", "resetLearning")).optBoolean("reset", false)
        }.getOrElse {
            android.util.Log.e(TAG, "Shared engine learning reset failed", it)
            false
        }
    }
}
