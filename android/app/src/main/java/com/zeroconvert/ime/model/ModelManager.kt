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
        val expectedSize: Long
    )

    val BUILT_IN_MODELS = listOf(
        BuiltInModelSpec(
            assetPath = "models/zenz-v3.2-xsmall-q4km.gguf",
            fileName = "zenz-v3.2-xsmall-q4km.gguf",
            displayName = "ZeroCon Model v3.2 Nano (Q4_K_M — 17.6MB)",
            expectedSize = 18452288L
        ),
        BuiltInModelSpec(
            assetPath = "zenz.gguf",
            fileName = "zenz.gguf",
            displayName = "ZeroCon Model v3.2 Nano Lite (Q3_K_M — 15.3MB)",
            expectedSize = 16078208L
        ),
        BuiltInModelSpec(
            assetPath = "models/zenz-v3.2-small-q4km.gguf",
            fileName = "zenz-v3.2-small-q4km.gguf",
            displayName = "ZeroCon Model v3.2 Standard (Q4_K_M — 61.3MB)",
            expectedSize = 64282688L
        )
    )

    val DEFAULT_MODEL_SPEC = BUILT_IN_MODELS[0]

    fun ensureBuiltInModel(context: Context, spec: BuiltInModelSpec): File {
        val destFile = if (spec.fileName == "zenz.gguf") {
            File(context.filesDir, "zenz.gguf")
        } else {
            File(getModelsDir(context), spec.fileName)
        }

        if (destFile.parentFile?.exists() != true) {
            destFile.parentFile?.mkdirs()
        }

        if (!destFile.exists() || destFile.length() != spec.expectedSize) {
            Log.i(TAG, "Extracting built-in model ${spec.assetPath} to ${destFile.name}...")
            val tmp = File(destFile.parentFile, "${destFile.name}.tmp")
            context.assets.open(spec.assetPath).use { input ->
                FileOutputStream(tmp).use { output ->
                    input.copyTo(output)
                }
            }
            tmp.renameTo(destFile)
            Log.i(TAG, "Extracted ${destFile.name}: ${destFile.length()} bytes")
        }
        return destFile
    }

    fun getAvailableModels(context: Context): List<ModelInfo> {
        val list = mutableListOf<ModelInfo>()
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val selectedPath = prefs.getString(KEY_PATH, null)

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
                // Default to Q4_K_M if no selection yet
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
        val files = modelsDir.listFiles { f -> f.isFile && f.name.endsWith(".gguf") && !builtInFiles.contains(f.name) }
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
