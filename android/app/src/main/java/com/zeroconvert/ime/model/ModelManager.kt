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
    val isSelected: Boolean,
    val downloaded: Boolean = false,
    val mandatory: Boolean = false
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
        val fileName: String,
        val displayName: String,
        val expectedSize: Long,
        val sha256: String,
        val downloadUrl: String,
        val mandatory: Boolean = false
    )

    private const val RELEASE_DOWNLOAD_BASE =
        "https://github.com/g0083/ZeroConvert-IME/releases/download/models-v4.1/"

    val BUILT_IN_MODELS = listOf(
        BuiltInModelSpec(
            fileName = ConversionModelSelection.IQ4,
            displayName = "ZeroCon Model Nano v4.1 IQ4_emb8 (18.7 MiB)",
            expectedSize = 19602048L,
            sha256 = "6a99ca4e1c46b3f6fb86dcf884ea0652c967d86d606c12268b33d894d3853b24",
            downloadUrl = RELEASE_DOWNLOAD_BASE + ConversionModelSelection.IQ4,
            mandatory = true
        ),
        BuiltInModelSpec(
            fileName = ConversionModelSelection.Q8,
            displayName = "ZeroCon Model Nano v4.1 Q8 (27.7 MiB)",
            expectedSize = 29039232L,
            sha256 = "001d2cf3acd75343d2dd4ea11472e28258bc7429d7d46091b50a78776dead378",
            downloadUrl = RELEASE_DOWNLOAD_BASE + ConversionModelSelection.Q8
        ),
        BuiltInModelSpec(
            fileName = ConversionModelSelection.STANDARD_IQ4,
            displayName = "ZeroCon Model Standard v4.1 IQ4_emb8 (58.5 MiB)",
            expectedSize = 61399072L,
            sha256 = "61af7b70aaf664a7c5b380bc373149fe0e0c67122c697f7127d7e8985fdbfb41",
            downloadUrl = RELEASE_DOWNLOAD_BASE + ConversionModelSelection.STANDARD_IQ4
        ),
        BuiltInModelSpec(
            fileName = ConversionModelSelection.STANDARD_Q8,
            displayName = "ZeroCon Model Standard v4.1 Q8 (99.0 MiB)",
            expectedSize = 103866400L,
            sha256 = "5065b64d9d0cabdd41bef8cd748863adb84691426be24d2a9be95fa23269c8ca",
            downloadUrl = RELEASE_DOWNLOAD_BASE + ConversionModelSelection.STANDARD_Q8
        )
    )

    val DEFAULT_MODEL_SPEC = BUILT_IN_MODELS[0]

    /** Remove retired separate corrector copies from upgraded app storage. */
    fun cleanupLegacyCorrector(context: Context) {
        try {
            val assistDir = File(context.filesDir, "assist")
            if (assistDir.exists()) assistDir.deleteRecursively()
        } catch (error: Exception) {
            Log.w(TAG, "Failed to remove legacy assist dir", error)
        }
    }

    /** The managed cache file for a spec, if it is present and complete.
     *  Does not download and does not extract assets (APK no longer
     *  bundles GGUF weights). */
    fun cachedFile(context: Context, spec: BuiltInModelSpec): File? {
        val file = File(getModelsDir(context), spec.fileName)
        return if (file.isFile && file.length() == spec.expectedSize) file else null
    }

    /** Same as [ensureBuiltInModel], kept for the instrumentation test seam:
     *  returns the verified cache file; throws when it is not downloaded yet. */
    fun ensureBuiltInModel(context: Context, spec: BuiltInModelSpec): File {
        check(ConversionModelDownloadManager.hasVerified(context, spec)) {
            "Cached model is missing or invalid: ${spec.fileName} (download it first)"
        }
        return ConversionModelDownloadManager.modelFile(context, spec)
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

        // 1. Downloadable catalog models (Nano / Standard, IQ4_emb8 / Q8)
        for (spec in BUILT_IN_MODELS) {
            val file = File(getModelsDir(context), spec.fileName)
            builtInFiles.add(file.name)
            val downloaded = file.isFile && file.length() == spec.expectedSize

            val isSel = if (selectedPath == null) {
                // Default to Nano v4.1 IQ4_emb8 if no selection yet
                spec.fileName == DEFAULT_MODEL_SPEC.fileName
            } else {
                selectedPath == file.absolutePath
            }

            list.add(
                ModelInfo(
                    name = spec.displayName,
                    file = file,
                    sizeBytes = if (downloaded) file.length() else spec.expectedSize,
                    isDefault = true,
                    isSelected = isSel,
                    downloaded = downloaded,
                    mandatory = spec.mandatory
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
                        isSelected = isSel,
                        downloaded = true
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
                isSelected = false,
                downloaded = true
            )
            Result.success(info)
        } catch (e: Exception) {
            Log.e(TAG, "importModelFromUri error", e)
            Result.failure(e)
        }
    }

    suspend fun selectModel(context: Context, modelInfo: ModelInfo): Boolean = withContext(Dispatchers.IO) {
        if (modelInfo.isDefault && !modelInfo.downloaded) return@withContext false
        val fileToLoad = modelInfo.file

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
        if (modelInfo.isDefault) return false // catalog models (incl. mandatory Nano IQ4) are kept
        val deleted = modelInfo.file.delete()
        if (deleted) {
            val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            val selectedPath = prefs.getString(KEY_PATH, null)
            if (selectedPath == modelInfo.file.absolutePath) {
                // Fall back to the mandatory model selection (downloaded or not).
                selectModelDirect(context, File(getModelsDir(context), DEFAULT_MODEL_SPEC.fileName), DEFAULT_MODEL_SPEC.displayName)
            }
        }
        return deleted
    }

    private fun selectModelDirect(context: Context, file: File, name: String) {
        if (file.isFile) ZenzNative.loadModel(file, name)
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        prefs.edit()
            .putString(KEY_PATH, file.absolutePath)
            .putString(KEY_NAME, name)
            .apply()
    }
}
