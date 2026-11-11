package com.zeroconvert.ime.translation

import android.content.Context
import android.os.StatFs
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest
import kotlin.coroutines.coroutineContext

/**
 * Downloads and verifies the translation GGUF. The model is intentionally not
 * bundled in the APK: it is ~547 MiB and is fetched only after the user
 * confirms in the settings screen.
 */
object TranslationModelManager {
    private const val TAG = "TranslationModel"

    const val REPOSITORY = "pfnet/plamo-3-translate-0.8b-GGUF"
    const val REVISION = "dbeb0856cb0b004b484f190bb71dd65876297013"
    const val FILENAME = "plamo-3-translate-0.8b-Q4_K_M.gguf"
    const val EXPECTED_BYTES = 573123200L
    const val SHA256 = "923b2f28a9856de1b8b3b610a7e59ded0b14d007466fd5dd526e6a9ecaadafdd"
    const val DOWNLOAD_URL = "https://huggingface.co/$REPOSITORY/resolve/$REVISION/$FILENAME"
    const val LICENSE_URL = "https://huggingface.co/$REPOSITORY/blob/main/LICENSE/ja"
    const val MODEL_LABEL = "PLaMo 3 Translate 0.8B Q4_K_M"

    private const val MARKER = "translation-model.sha256"
    private const val PART_SUFFIX = ".part"
    private const val RESERVE_BYTES = 64L * 1024L * 1024L
    private const val REPORT_STEP_BYTES = 6L * 1024L * 1024L
    private val downloadMutex = Mutex()

    data class Progress(val downloaded: Long, val total: Long = EXPECTED_BYTES) {
        val fraction: Float
            get() = if (total <= 0L) 0f
            else (downloaded.toDouble() / total.toDouble()).toFloat().coerceIn(0f, 1f)
    }

    fun modelDir(context: Context): File = File(context.filesDir, "translation").apply { mkdirs() }

    fun modelFile(context: Context): File = File(modelDir(context), FILENAME)

    private fun partFile(context: Context): File = File(modelDir(context), "$FILENAME$PART_SUFFIX")

    private fun markerFile(context: Context): File = File(modelDir(context), MARKER)

    fun isReady(context: Context): Boolean {
        val file = modelFile(context)
        return file.isFile && file.length() == EXPECTED_BYTES && readMarker(context) == SHA256
    }

    /** Hash a manually placed model once and write the verification marker. */
    suspend fun verifyExisting(context: Context): Boolean = withContext(Dispatchers.IO) {
        val file = modelFile(context)
        if (!file.isFile || file.length() != EXPECTED_BYTES) return@withContext false
        if (readMarker(context) == SHA256) return@withContext true
        try {
            markVerified(context, file)
            true
        } catch (error: Exception) {
            Log.w(TAG, "Translation model verification failed", error)
            false
        }
    }

    fun availableBytes(context: Context): Long = try {
        StatFs(context.filesDir.absolutePath).availableBytes
    } catch (_: Exception) {
        -1L
    }

    fun hasSpace(context: Context): Boolean {
        val available = availableBytes(context)
        return available < 0L || available >= EXPECTED_BYTES + RESERVE_BYTES
    }

    /**
     * Resumable download. The partial file is kept on cancellation/failure and
     * the next attempt requests the remaining byte range.
     */
    suspend fun download(context: Context, onProgress: (Progress) -> Unit): Result<File> {
        if (!downloadMutex.tryLock()) {
            return Result.failure(IllegalStateException("翻訳モデルのダウンロードは既に実行中です"))
        }
        return try {
            downloadLocked(context, onProgress)
        } finally {
            downloadMutex.unlock()
        }
    }

    private suspend fun downloadLocked(context: Context, onProgress: (Progress) -> Unit): Result<File> =
        withContext(Dispatchers.IO) {
            try {
                val directory = modelDir(context)
                if (!directory.isDirectory) {
                    return@withContext Result.failure(
                        IllegalStateException("翻訳モデル用フォルダを作成できません")
                    )
                }
                if (!hasSpace(context)) {
                    return@withContext Result.failure(
                        IllegalStateException("空き容量が足りません（約${EXPECTED_BYTES / (1024 * 1024)}MB必要）")
                    )
                }
                val part = partFile(context)
                var existing = if (part.isFile) part.length() else 0L
                if (existing > EXPECTED_BYTES) {
                    part.delete()
                    existing = 0L
                }
                if (existing == EXPECTED_BYTES) {
                    return@withContext finish(context, part, onProgress)
                }

                val connection = (URL(DOWNLOAD_URL).openConnection() as HttpURLConnection).apply {
                    connectTimeout = 30_000
                    readTimeout = 30_000
                    requestMethod = "GET"
                    instanceFollowRedirects = true
                    setRequestProperty("Accept-Encoding", "identity")
                    setRequestProperty("User-Agent", "ZeroConvert-IME-Android")
                    if (existing > 0L) setRequestProperty("Range", "bytes=$existing-")
                }
                try {
                    connection.connect()
                    val code = connection.responseCode
                    if (code != HttpURLConnection.HTTP_OK && code != HttpURLConnection.HTTP_PARTIAL) {
                        return@withContext Result.failure(IllegalStateException("ダウンロードに失敗しました (HTTP $code)"))
                    }
                    var append = code == HttpURLConnection.HTTP_PARTIAL && existing > 0L
                    if (!append) existing = 0L

                    var written = existing
                    var lastReported = -RESERVE_BYTES
                    onProgress(Progress(written))
                    connection.inputStream.use { input ->
                        FileOutputStream(part, append).use { output ->
                            val buffer = ByteArray(256 * 1024)
                            while (true) {
                                coroutineContext.ensureActive()
                                val read = input.read(buffer)
                                if (read < 0) break
                                output.write(buffer, 0, read)
                                written += read
                                if (written > EXPECTED_BYTES) {
                                    output.close()
                                    part.delete()
                                    return@withContext Result.failure(
                                        IllegalStateException("モデルサイズが想定を超えました")
                                    )
                                }
                                if (written - lastReported >= REPORT_STEP_BYTES || written == EXPECTED_BYTES) {
                                    lastReported = written
                                    onProgress(Progress(written))
                                }
                            }
                        }
                    }
                    if (written != EXPECTED_BYTES) {
                        return@withContext Result.failure(
                            IllegalStateException("ダウンロードが完了していません (${written}/${EXPECTED_BYTES})")
                        )
                    }
                    finish(context, part, onProgress)
                } finally {
                    connection.disconnect()
                }
            } catch (cancelled: kotlinx.coroutines.CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                Log.w(TAG, "Translation model download failed", error)
                Result.failure(error)
            }
        }

    private fun finish(
        context: Context,
        part: File,
        onProgress: (Progress) -> Unit
    ): Result<File> {
        return try {
            if (sha256(part) != SHA256) {
                part.delete()
                return Result.failure(IllegalStateException("モデルのSHA-256が一致しません"))
            }
            val destination = modelFile(context)
            if (destination.exists() && !destination.delete()) {
                return Result.failure(IllegalStateException("既存モデルを置き換えられません"))
            }
            if (!part.renameTo(destination)) {
                return Result.failure(IllegalStateException("モデルを保存できません"))
            }
            markerFile(context).writeText(SHA256)
            onProgress(Progress(EXPECTED_BYTES))
            Result.success(destination)
        } catch (error: Exception) {
            Log.w(TAG, "Translation model finalization failed", error)
            Result.failure(error)
        }
    }

    private fun markVerified(context: Context, file: File) {
        require(sha256(file) == SHA256) { "モデルのSHA-256が一致しません" }
        markerFile(context).writeText(SHA256)
    }

    fun delete(context: Context): Boolean {
        var ok = true
        for (file in listOf(modelFile(context), partFile(context), markerFile(context))) {
            if (file.exists() && !file.delete()) ok = false
        }
        return ok
    }

    private fun readMarker(context: Context): String? = try {
        markerFile(context).readText().trim()
    } catch (_: Exception) {
        null
    }

    private fun sha256(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val buffer = ByteArray(256 * 1024)
            while (true) {
                val read = input.read(buffer)
                if (read < 0) break
                digest.update(buffer, 0, read)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }
}
