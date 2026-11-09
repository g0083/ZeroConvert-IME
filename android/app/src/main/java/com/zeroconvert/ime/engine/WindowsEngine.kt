package com.zeroconvert.ime.engine

import android.content.Context
import com.zeroconvert.ime.*
import com.zeroconvert.ime.assist.*
import com.zeroconvert.ime.azookey.input.ComposingText
import com.zeroconvert.ime.azookey.input.InputPiece
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.concurrent.Executors

/** Android supplies editor snapshots and model I/O; Windows owns conversion policy. */
object WindowsEngine {
    private val dispatcher = Executors.newSingleThreadExecutor { Thread(it, "ZeroCon-shared-engine") }.asCoroutineDispatcher()
    private val scope = CoroutineScope(SupervisorJob() + dispatcher)
    private data class BackgroundTask(val revision: Long?, val action: () -> Unit)
    private val backgroundTasks = Channel<BackgroundTask>(128)
    init {
        scope.launch {
            for (task in backgroundTasks) {
                if (!ready || (task.revision != null && (!LearningPolicy.canWrite || task.revision != KanaKanjiConverter.learningHistoryRevision))) continue
                try { task.action() }
                catch (failure: Exception) { android.util.Log.e("WindowsEngine", "Shared engine background operation failed", failure) }
            }
        }
    }
    private lateinit var application: Context
    private lateinit var root: File
    private var modelPath = ""
    private var lastSettings = ""
    private var backendsLoaded = false // confined to dispatcher; llama_backend_free does not unload backends
    @Volatile private var ready = false

    private external fun nativeSwift(request: String): String
    private external fun nativeAssist(request: String, inference: CompletionHost): String
    private external fun nativeSetDataRoot(root: String)
    private external fun nativeConversionEpoch(): Long
    private external fun nativeAbortConversion()
    private external fun nativeLoadBackends(directory: String)

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
        val dataRoot = File(application.filesDir, "windows-engine").apply { mkdirs() }
        val fingerprint = application.assets.open("shared-engine/sources.json").bufferedReader().use { it.readText() }
        val revision = java.security.MessageDigest.getInstance("SHA-256").digest(fingerprint.toByteArray())
            .joinToString("") { "%02x".format(it) }
        root = File(dataRoot, "runtime-$revision")
        if (!File(root, ".complete").exists()) {
            copyAssets("shared-engine", root)
            File(root, ".complete").writeText(revision)
        }
        nativeSetDataRoot(dataRoot.absolutePath)
        modelPath = selectedModel.absolutePath
        migrateSelections(File(dataRoot, "Azookey/LearningMemory"))
        migrateCorrections(File(dataRoot, "Azookey/ai-assist-learning.json"))
        assistRequest(JSONObject().put("op", "configurePaths")
            .put("settings", File(dataRoot, "Azookey/settings.json").absolutePath)
            .put("reverse", File(root, "Dictionary/Reverse/reverse-v2.bin").absolutePath))
        lastSettings = ""
        configure(true, "", true, false)
        ready = true
        true
    }

    fun switchModel(model: File): Boolean = runBlocking(dispatcher) {
        if (!ready) return@runBlocking false
        modelPath = model.absolutePath
        lastSettings = ""
        configure(true, "", true, false)
        true
    }

    fun close() {
        ready = false
        runBlocking(dispatcher) { checkedSwift(JSONObject().put("op", "close")); lastSettings = "" }
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
        candidates(input, context, true, profile, LearningPolicy.canRead, false)
            .firstOrNull()?.previewForReading(input.convertTarget) ?: reading
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
        val legacy = application.getSharedPreferences("zerocon_user_data", Context.MODE_PRIVATE)
            .getString("learning_memory_json", null) ?: "{}"
        val original = JSONObject(legacy)
        val normalized = JSONObject()
        original.keys().forEach { normalized.put(KanaKanjiConverter.toKatakana(it), original.getString(it)) }
        atomicWrite(destination, normalized.toString())
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

    private fun atomicWrite(destination: File, text: String) {
        val atomic = android.util.AtomicFile(destination)
        val stream = atomic.startWrite()
        try { stream.write(text.toByteArray(Charsets.UTF_8)); atomic.finishWrite(stream) }
        catch (failure: Exception) { atomic.failWrite(stream); throw failure }
    }

    private fun configure(ai: Boolean, profile: String, personalize: Boolean, dictionaryCorrection: Boolean,
                          predictiveConversion: Boolean = true) {
        val users = JSONArray()
        if (personalize) KanaKanjiConverter.getUserDictionary(application).forEach {
            users.put(JSONObject().put("reading", it.reading).put("word", it.word))
        }
        val settings = JSONObject().put("zenzai", JSONObject().put("enable", ai).put("backend", "cpu").put("profile", profile))
            .put("general", JSONObject()
                .put("experimental_typo_correction", dictionaryCorrection)
                .put("predictive_conversion", predictiveConversion))
            .put("learning", JSONObject().put("mode", if (!personalize) "disabled" else when (LearningPolicy.mode) {
                LearningMode.DISABLED -> "disabled"; LearningMode.READ_ONLY -> "read_only"; else -> "enabled"
            })).put("user_dictionary", JSONObject().put("entries", users)).toString()
        if (settings == lastSettings) return
        val settingsFile = File(application.filesDir, "windows-engine/Azookey/settings.json")
        settingsFile.parentFile?.mkdirs()
        atomicWrite(settingsFile, settings)
        assistRequest(JSONObject().put("op", "setVocabulary").put("entries", users))
        checkedSwift(JSONObject().put("op", "configure").put("root", root.absolutePath).put("modelPath", modelPath))
        lastSettings = settings
    }

    private fun checkedSwift(request: JSONObject): JSONObject {
        val result = JSONObject(nativeSwift(request.toString()))
        check(!result.has("error")) { "Shared conversion engine: ${result.optString("error")}" }
        return result
    }

    internal suspend fun candidates(input: ComposingText, context: String, ai: Boolean, profile: String,
                           personalize: Boolean, dictionaryCorrection: Boolean,
                           predictiveConversion: Boolean = true,
                           continuation: ConversionContinuation? = null): List<Candidate> {
        val queued = System.nanoTime()
        val epoch = nativeConversionEpoch()
        return withContext(dispatcher) {
            ensureActive()
            if (epoch != nativeConversionEpoch()) throw CancellationException("Superseded conversion")
            val dequeued = System.nanoTime()
            check(ready) { "Shared conversion engine is not ready" }
            configure(ai, profile, personalize, dictionaryCorrection, predictiveConversion)
            ensureActive()
            val configured = System.nanoTime()
            val elements = JSONArray()
            engineInputElements(input).forEach { element ->
                elements.put(JSONObject().put("style", element.style)
                    .put("separator", element.separator).put("text", element.text))
            }
            val result = checkedSwift(JSONObject().put("op", "convert").put("elements", elements)
                .put("cursorUtf16", input.convertTargetCursorPosition).put("expectedReading", input.convertTarget)
                .put("continuationReading", continuation?.reading.orEmpty())
                .put("continuationSurface", continuation?.surface.orEmpty())
                .put("context", context).put("allowAI", ai).put("requestEpoch", epoch).put("diagnostics", diagnosticsEnabled))
            if (result.optBoolean("cancelled")) throw CancellationException("Native conversion superseded")
            ensureActive()
            val timing = result.getJSONObject("timing")
            val stages = timing.getJSONObject("stagesMs")
            lastTiming = ConversionTiming((dequeued - queued) / 1e6, (configured - dequeued) / 1e6,
                (System.nanoTime() - configured) / 1e6, timing.getLong("decodeNs") / 1e6, timing.getLong("decodeCount"),
                stages.keys().asSequence().associateWith { stages.getLong(it) })
            val candidates = result.getJSONArray("candidates")
            val converted = (0 until candidates.length()).map { index ->
                val candidate = candidates.getJSONObject(index)
                val consumed = candidate.getInt("consumedReadingUtf16")
                check(consumed in 1..input.convertTargetCursorPosition)
                Candidate(candidate.getString("text"), KanaKanjiConverter.toKatakana(input.convertTarget.take(consumed)),
                    isAI = ai, learningAllowed = candidate.getBoolean("learningAllowed"),
                    engineCandidateId = candidate.getString("candidateId").takeUnless { it == "0" }.orEmpty(),
                    engineConsumedReading = consumed)
            }.distinctBy { it.text to it.engineConsumedReading }
            val macros = JSONArray(assistRequest(JSONObject().put("op", "macros").put("input", input.convertTargetBeforeCursor)))
            val macroTexts = (0 until macros.length()).map { macros.getString(it) }
            MacroCandidateMerge.merge(converted, macroTexts, KanaKanjiConverter.toKatakana(input.convertTargetBeforeCursor))
        }
    }

    fun assist(op: String, context: String, input: String, baseline: String, inference: AssistInference): AiAssistResult? {
        if (!ready) return null
        val raw = nativeAssist(JSONObject().put("op", op).put("revision", (inference.revision shl 32) xor AssistVocabulary.revision)
            .put("readLearning", LearningPolicy.canRead)
            .put("context", context).put("input", input).put("baseline", baseline).toString(), CompletionHost(inference))
        if (raw == "null") return null
        val result = JSONObject(raw)
        check(!result.has("error")) { result.optString("error") }
        val correction = op == "correction"
        val text = result.getString("text")
        return AiAssistResult(if (correction) AiAssistType.TYPO_CORRECTION else AiAssistType.PREDICTION,
            text, "${if (correction) "💡" else "🔮"} $text", result.getInt("relevance"),
            sourceReading = input, continuation = result.optString("continuation"), autoApply = result.optBoolean("autoApply"))
    }

    private fun assistRequest(request: JSONObject): String = nativeAssist(request.toString(), CompletionHost(object : AssistInference {
        override val revision = -1L
        override fun complete(model: AssistModel, prompt: String, maxTokens: Int, timeoutMs: Int) = ModelCompletion("", "unavailable")
    }))

    fun recordCorrection(reading: String, surface: String) {
        backgroundTasks.trySend(BackgroundTask(KanaKanjiConverter.learningHistoryRevision) {
            assistRequest(JSONObject().put("op", "learn").put("input", reading).put("baseline", surface))
        })
    }

    fun autoCommit(raw: String, reading: String, history: List<InferenceSnapshot>, safeBoundary: (Int) -> Boolean): AutoCommitCandidate? {
        val entries = JSONArray()
        history.forEach { snapshot ->
            entries.put(JSONObject().put("rawCount", snapshot.rawInputCount)
                .put("readingCount", snapshot.readingHiragana.codePointCount(0, snapshot.readingHiragana.length))
                .put("surface", snapshot.aiSurface))
        }
        val result = assistRequest(JSONObject().put("op", "autoCommit").put("raw", raw).put("input", reading)
            .put("baseline", history.lastOrNull()?.aiSurface.orEmpty()).put("history", entries))
        if (result == "null") return null
        val candidate = JSONObject(result)
        val length = reading.offsetByCodePoints(0, candidate.getInt("readingLength"))
        if (!safeBoundary(length)) return null
        return AutoCommitCandidate(length, reading.take(length), candidate.getString("surface"))
    }

    fun commit(candidateId: String) {
        if (candidateId.isEmpty()) return
        backgroundTasks.trySend(BackgroundTask(KanaKanjiConverter.learningHistoryRevision) {
            check(checkedSwift(JSONObject().put("op", "commit").put("candidateId", candidateId)).optBoolean("learned")) {
                "Shared engine candidate is no longer available for learning"
            }
        })
    }

    fun resetLearning() {
        backgroundTasks.trySend(BackgroundTask(null) { checkedSwift(JSONObject().put("op", "resetLearning")) })
    }
}
