package com.zeroconvert.ime.model

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import android.util.Log
import com.zeroconvert.ime.ZenzNative
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream

data class ModelInfo(
    val name: String,
    val file: File,
    val sizeBytes: Long,
    val isDefault: Boolean,
    val isSelected: Boolean
) {
    val sizeMb: String
        get() = String.format("%.1f MB", sizeBytes.toDouble() / (1024 * 1024))
}

object ModelManager {
    private const val TAG = "ModelManager"
    private const val PREFS_NAME = "model_prefs"
    private const val KEY_PATH = "selected_model_path"
    private const val KEY_NAME = "selected_model_name"

    fun getModelsDir(context: Context): File {
        val dir = File(context.filesDir, "models")
        if (!dir.exists()) dir.mkdirs()
        return dir
    }

    data class BuiltInModelSpec(
        val assetPath: String,
        val fileName: String,
        val displayName: String,
        val expectedSize: Long,
        val sha256: String
    )

    val BUILT_IN_MODELS = listOf(
        BuiltInModelSpec(
            assetPath = "models/${ConversionModelSelection.IQ4}",
            fileName = ConversionModelSelection.IQ4,
            displayName = "ZeroCon Model Nano v4 IQ4_emb8 (18.7 MiB)",
            expectedSize = 19602048L,
            sha256 = "31724c4189d8f8fcec90544d1695d42a8c4e7a870d645384e7284f2cc8891f30"
        ),
        BuiltInModelSpec(
            assetPath = "models/${ConversionModelSelection.Q8}",
            fileName = ConversionModelSelection.Q8,
            displayName = "ZeroCon Model Nano v4 Q8 (27.7 MiB)",
            expectedSize = 29039232L,
            sha256 = "a89fe90fc29431279834773cdbe7c9afeba30722ee986327b300bf4b334c0e0f"
        )
    )

    val DEFAULT_MODEL_SPEC = BUILT_IN_MODELS[0]

    private const val CORRECTOR_SHA256 = "dd87dbdc1a04cb4391f8429e6a1addc3c65d824996dd465e96803a5c1411fe2e"

    /** Separate directory: never offer the Corrector as an ordinary conversion model. */
    fun ensureCorrector(context: Context): File? {
        val dir = File(context.filesDir, "assist").apply { mkdirs() }
        val destination = File(dir, "corrector-$CORRECTOR_SHA256.gguf")
        fun hash(file: File): String = file.inputStream().use { input ->
            val digest = java.security.MessageDigest.getInstance("SHA-256")
            val buffer = ByteArray(65536)
            while (true) { val size = input.read(buffer); if (size < 0) break; digest.update(buffer, 0, size) }
            digest.digest().joinToString("") { "%02x".format(it) }
        }
        return try {
            if (destination.isFile && hash(destination) == CORRECTOR_SHA256) return destination
            val temporary = File(dir, "corrector.tmp")
            context.assets.open("models/zerocon-model-corrector.gguf").use { input ->
                temporary.outputStream().use { input.copyTo(it) }
            }
            check(hash(temporary) == CORRECTOR_SHA256) { "Corrector model checksum mismatch" }
            check(temporary.renameTo(destination)) { "Cannot install Corrector model" }
            destination
        } catch (error: Exception) {
            Log.w(TAG, "Corrector model unavailable", error)
            null
        }
    }

    fun ensureBuiltInModel(context: Context, spec: BuiltInModelSpec): File {
        val destFile = if (spec.fileName == "zenz.gguf") {
            File(context.filesDir, "zenz.gguf")
        } else {
            File(getModelsDir(context), spec.fileName)
        }

        if (destFile.parentFile?.exists() != true) {
            destFile.parentFile?.mkdirs()
        }

        return VerifiedModelFile.ensure(destFile, spec.expectedSize, spec.sha256) { context.assets.open(spec.assetPath) }
    }

    /** Resolve only managed selections; user-imported paths remain intact. */
    fun selectedBuiltInSpec(context: Context, savedPath: String?): BuiltInModelSpec? {
        if (savedPath.isNullOrEmpty()) return DEFAULT_MODEL_SPEC
        val file = File(savedPath)
        val managed = file.absolutePath == File(context.filesDir, "zenz.gguf").absolutePath ||
            file.parentFile?.absolutePath == getModelsDir(context).absolutePath
        if (!managed) return null
        val replacement = ConversionModelSelection.replacement(file.name) ?: return null
        return BUILT_IN_MODELS.find { it.fileName == replacement }
    }

    fun getAvailableModels(context: Context): List<ModelInfo> {
        val list = mutableListOf<ModelInfo>()
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val savedPath = prefs.getString(KEY_PATH, null)
        val selectedSpec = selectedBuiltInSpec(context, savedPath)
        val selectedPath = selectedSpec?.let { File(getModelsDir(context), it.fileName).absolutePath } ?: savedPath

        val builtInFiles = mutableSetOf<String>()

        // 1. Built-in bundled models
        for (spec in BUILT_IN_MODELS) {
            val file = if (spec.fileName == "zenz.gguf") {
                File(context.filesDir, "zenz.gguf")
            } else {
                File(getModelsDir(context), spec.fileName)
            }
            builtInFiles.add(file.name)

            val isSel = if (selectedPath == null) {
                // Default to Nano v4 IQ4_emb8 if no selection yet
                spec.fileName == DEFAULT_MODEL_SPEC.fileName
            } else {
                selectedPath == file.absolutePath
            }

            list.add(
                ModelInfo(
                    name = spec.displayName,
                    file = file,
                    sizeBytes = spec.expectedSize,
                    isDefault = true,
                    isSelected = isSel
                )
            )
        }

        // 2. User imported models in files/models/
        val modelsDir = getModelsDir(context)
        val files = modelsDir.listFiles { f -> f.isFile && f.name.endsWith(".gguf") && !builtInFiles.contains(f.name) && f.name !in ConversionModelSelection.retiredFiles }
        if (files != null) {
            for (f in files.sortedBy { it.name }) {
                val isSel = (selectedPath == f.absolutePath)
                list.add(
                    ModelInfo(
                        name = f.name,
                        file = f,
                        sizeBytes = f.length(),
                        isDefault = false,
                        isSelected = isSel
                    )
                )
            }
        }

        return list
    }

    suspend fun importModelFromUri(context: Context, uri: Uri): Result<ModelInfo> = withContext(Dispatchers.IO) {
        try {
            var fileName = "imported_model.gguf"
            context.contentResolver.query(uri, null, null, null, null)?.use { cursor ->
                val nameIndex = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                if (nameIndex != -1 && cursor.moveToFirst()) {
                    val name = cursor.getString(nameIndex)
                    if (!name.isNullOrEmpty()) {
                        fileName = name
                    }
                }
            }

            if (!fileName.endsWith(".gguf")) {
                fileName = "$fileName.gguf"
            }

            val destFile = File(getModelsDir(context), fileName)
            Log.i(TAG, "Importing model from $uri to ${destFile.absolutePath}...")

            context.contentResolver.openInputStream(uri)?.use { input ->
                FileOutputStream(destFile).use { output ->
                    input.copyTo(output)
                }
            } ?: return@withContext Result.failure(Exception("Failed to open input stream"))

            Log.i(TAG, "Import completed: ${destFile.name} (${destFile.length()} bytes)")
            val info = ModelInfo(
                name = destFile.name,
                file = destFile,
                sizeBytes = destFile.length(),
                isDefault = false,
                isSelected = false
            )
            Result.success(info)
        } catch (e: Exception) {
            Log.e(TAG, "importModelFromUri error", e)
            Result.failure(e)
        }
    }

    suspend fun selectModel(context: Context, modelInfo: ModelInfo): Boolean = withContext(Dispatchers.IO) {
        val fileToLoad = if (modelInfo.isDefault) {
            val spec = BUILT_IN_MODELS.find { it.displayName == modelInfo.name }
            if (spec != null) {
                ensureBuiltInModel(context, spec)
            } else {
                modelInfo.file
            }
        } else {
            modelInfo.file
        }

        val ok = ZenzNative.loadModel(fileToLoad, modelInfo.name)
        if (ok) {
            val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            prefs.edit()
                .putString(KEY_PATH, fileToLoad.absolutePath)
                .putString(KEY_NAME, modelInfo.name)
                .apply()
        }
        ok
    }

    fun deleteModel(context: Context, modelInfo: ModelInfo): Boolean {
        if (modelInfo.isDefault) return false
        val deleted = modelInfo.file.delete()
        if (deleted) {
            val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            val selectedPath = prefs.getString(KEY_PATH, null)
            if (selectedPath == modelInfo.file.absolutePath) {
                // Fallback to default
                val defaultFile = ensureBuiltInModel(context, DEFAULT_MODEL_SPEC)
                selectModelDirect(context, defaultFile, DEFAULT_MODEL_SPEC.displayName)
            }
        }
        return deleted
    }

    private fun selectModelDirect(context: Context, file: File, name: String) {
        ZenzNative.loadModel(file, name)
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        prefs.edit()
            .putString(KEY_PATH, file.absolutePath)
            .putString(KEY_NAME, name)
            .apply()
    }
}
