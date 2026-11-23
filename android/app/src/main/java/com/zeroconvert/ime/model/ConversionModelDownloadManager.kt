package com.zeroconvert.ime.model

import android.content.Context
import android.os.StatFs
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.io.RandomAccessFile
import java.net.HttpURLConnection
import java.net.URL
import kotlin.coroutines.coroutineContext

/**
 * Downloads converter models on demand. Models are not bundled in the APK
 * (~171 MiB total): only a verified cache in `files/models/` is used, and
 * missing files are fetched from the pinned GitHub release assets with
 * size + SHA-256 verification, mirroring the English dictionary download.
 * The mandatory Nano v4.2 IQ4_emb8 is required for conversion.
 */
object ConversionModelDownloadManager {
    private const val TAG = "ConversionModelDownload"
    private const val PART_SUFFIX = ".part"
    private const val RESERVE_BYTES = 64L * 1024L * 1024L
    private const val REPORT_STEP_BYTES = 2L * 1024L * 1024L
    private val downloadMutex = Mutex()

    data class Progress(val downloaded: Long, val total: Long) {
        val fraction: Float
            get() = if (total <= 0L) 0f
            else (downloaded.toDouble() / total.toDouble()).toFloat().coerceIn(0f, 1f)
    }

    fun modelFile(context: Context, spec: ModelManager.BuiltInModelSpec): File =
        File(ModelManager.getModelsDir(context), spec.fileName)

    private fun partFile(context: Context, spec: ModelManager.BuiltInModelSpec): File =
        File(ModelManager.getModelsDir(context), spec.fileName + PART_SUFFIX)

    /** True only when the cached bytes match the catalog size and SHA-256. */
    fun hasVerified(context: Context, spec: ModelManager.BuiltInModelSpec): Boolean {
        val file = modelFile(context, spec)
        if (!file.isFile || file.length() != spec.expectedSize) return false
        return VerifiedModelFile.sha256(file) == spec.sha256
    }

    fun availableBytes(context: Context): Long = try {
        StatFs(context.filesDir.absolutePath).availableBytes
    } catch (_: Exception) {
        -1L
    }

    fun hasSpace(context: Context, spec: ModelManager.BuiltInModelSpec): Boolean {
        val available = availableBytes(context)
        return available < 0L || available >= spec.expectedSize + RESERVE_BYTES
    }

    /** Resumable download. The partial file is kept and the next attempt
     *  requests the remaining byte range. */
    suspend fun download(
        context: Context,
        spec: ModelManager.BuiltInModelSpec,
        onProgress: (Progress) -> Unit,
    ): Result<File> = downloadMutex.run {
        downloadLocked(context, spec, onProgress)
    }

    private suspend fun downloadLocked(
        context: Context,
        spec: ModelManager.BuiltInModelSpec,
        onProgress: (Progress) -> Unit,
    ): Result<File> = withContext(Dispatchers.IO) {
        try {
            if (hasVerified(context, spec)) {
                onProgress(Progress(spec.expectedSize, spec.expectedSize))
                return@withContext Result.success(modelFile(context, spec))
            }
            if (!hasSpace(context, spec)) {
                return@withContext Result.failure(
                    IllegalStateException("空き容量が足りません（約${spec.expectedSize / (1024 * 1024)}MB必要）")
                )
            }
            val part = partFile(context, spec)
            var existing = if (part.isFile) part.length() else 0L
            if (existing > spec.expectedSize) {
                part.delete()
                existing = 0L
            }
            val connection = (URL(spec.downloadUrl).openConnection() as HttpURLConnection).apply {
                connectTimeout = 30_000
                readTimeout = 60_000
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
                    return@withContext Result.failure(
                        IllegalStateException("ダウンロードに失敗しました (HTTP $code)")
                    )
                }
                var append = code == HttpURLConnection.HTTP_PARTIAL && existing > 0L
                if (!append) existing = 0L

                var written = existing
                var lastReported = -RESERVE_BYTES
                onProgress(Progress(written, spec.expectedSize))
                connection.inputStream.use { input ->
                    FileOutputStream(part, append).use { output ->
                        val buffer = ByteArray(256 * 1024)
                        while (true) {
                            coroutineContext.ensureActive()
                            val read = input.read(buffer)
                            if (read < 0) break
                            output.write(buffer, 0, read)
                            written += read
                            if (written > spec.expectedSize) {
                                part.delete()
                                return@withContext Result.failure(
                                    IllegalStateException("モデルサイズが想定を超えました")
                                )
                            }
                            if (written - lastReported >= REPORT_STEP_BYTES || written == spec.expectedSize) {
                                lastReported = written
                                onProgress(Progress(written, spec.expectedSize))
                            }
                        }
                    }
                }
                if (written != spec.expectedSize) {
                    return@withContext Result.failure(
                        IllegalStateException("ダウンロードが完了していません (${written}/${spec.expectedSize})")
                    )
                }
                if (VerifiedModelFile.sha256(part) != spec.sha256) {
                    part.delete()
                    return@withContext Result.failure(IllegalStateException("モデルのSHA-256が一致しません"))
                }
                val destination = modelFile(context, spec)
                if (destination.exists() && !destination.delete()) {
                    return@withContext Result.failure(IllegalStateException("既存モデルを置き換えられません"))
                }
                if (!part.renameTo(destination)) {
                    // renameTo fails across some filesystems; fall back to a copy, then verify again.
                    part.copyTo(destination, overwrite = true)
                    if (VerifiedModelFile.sha256(destination) != spec.sha256) {
                        destination.delete()
                        return@withContext Result.failure(IllegalStateException("モデルのSHA-256が一致しません"))
                    }
                    part.delete()
                }
                onProgress(Progress(spec.expectedSize, spec.expectedSize))
                Result.success(destination)
            } finally {
                connection.disconnect()
            }
        } catch (cancelled: kotlinx.coroutines.CancellationException) {
            throw cancelled
        } catch (error: Exception) {
            Log.w(TAG, "Conversion model download failed: ${spec.fileName}", error)
            Result.failure(error)
        }
    }

    /** Length of a partial download, for retry reporting. */
    fun partialBytes(context: Context, spec: ModelManager.BuiltInModelSpec): Long {
        val part = partFile(context, spec)
        return if (part.isFile) part.length() else 0L
    }

    fun discardPart(context: Context, spec: ModelManager.BuiltInModelSpec) {
        partFile(context, spec).delete()
    }

    /** RandomAccessFile is used only for deterministic range resume in tests. */
    @Suppress("unused")
    private fun preallocated(part: File, size: Long) = RandomAccessFile(part, "rw").use { it.setLength(size) }
}
