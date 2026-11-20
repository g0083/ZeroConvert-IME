package com.zeroconvert.ime

import android.content.Context
import android.util.Log
import com.zeroconvert.ime.engine.WindowsEngine
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest
import java.util.zip.GZIPInputStream
import kotlin.coroutines.coroutineContext

/**
 * 英単語辞書を初回起動時に取得する。辞書はAPKに同梱せず、上流の固定URLから
 * msgpack.gz（約1.4MB）を取得して端末内で抽出・並べ替え、テキストキャッシュを書く。
 *
 * 頻度データは wordfreq 3.1.1（CC BY-SA 4.0）。配布物にはデータを含めず、
 * 抽出結果（改変物）も端末内にのみ存在する。
 */
object EnglishDictionaryManager {
    private const val TAG = "EnglishDictionary"

    const val REPOSITORY = "rspeer/wordfreq"
    const val REVISION = "ec48c0a1237f271b343fa4e38fb896c8da587804"
    const val FILENAME = "wordfreq/data/large_en.msgpack.gz"
    const val DOWNLOAD_URL =
        "https://raw.githubusercontent.com/rspeer/wordfreq/$REVISION/$FILENAME"
    const val EXPECTED_BYTES = 1_494_836L
    const val SHA256 = "dffae8066b78dce0a6667cf5f58e567054f902674667090a7ac8a8a44628b05c"
    const val LICENSE_LABEL = "CC BY-SA 4.0"
    const val ATTRIBUTION = "wordfreq 3.1.1 by Robyn Speer (Luminoso)"
    const val WORD_LIMIT = 30_000

    private const val CACHE_NAME = "english-words.txt"
    private const val GZIP_SUFFIX = ".gz.part"
    private const val MSGPACK_SUFFIX = ".msgpack.part"
    private val downloadMutex = Mutex()

    data class Progress(val received: Long, val total: Long = EXPECTED_BYTES) {
        val fraction: Float
            get() = if (total <= 0L) 0f
            else (received.toDouble() / total.toDouble()).toFloat().coerceIn(0f, 1f)
    }

    fun directory(context: Context): File = File(context.filesDir, "english").apply { mkdirs() }

    fun cacheFile(context: Context): File = File(directory(context), CACHE_NAME)

    fun isReady(context: Context): Boolean = cacheFile(context).let { it.isFile && it.length() > 0L }

    /** 取得・検証・展開・抽出をまとめて行い、書き込んだ語数を返す。 */
    suspend fun download(context: Context, onProgress: (Progress) -> Unit = {}): Result<Int> =
        downloadMutex.withLock {
            withContext(Dispatchers.IO) {
                runCatching {
                    if (isReady(context)) return@runCatching 0
                    val directory = directory(context)
                    val gzip = File(directory, CACHE_NAME + GZIP_SUFFIX)
                    val msgpack = File(directory, CACHE_NAME + MSGPACK_SUFFIX)
                    try {
                        fetchTo(gzip, onProgress)
                        gunzipTo(gzip, msgpack)
                        val words = WindowsEngine.importEnglishDictionary(
                            msgpack.absolutePath,
                            cacheFile(context).absolutePath,
                            WORD_LIMIT
                        )
                        check(words > 0) { "辞書の抽出に失敗しました" }
                        onProgress(Progress(EXPECTED_BYTES, EXPECTED_BYTES))
                        words
                    } finally {
                        gzip.delete()
                        msgpack.delete()
                    }
                }.onFailure { Log.w(TAG, "英語辞書の取得に失敗しました", it) }
            }
        }

    private suspend fun fetchTo(destination: File, onProgress: (Progress) -> Unit) {
        val connection = (URL(DOWNLOAD_URL).openConnection() as HttpURLConnection).apply {
            connectTimeout = 20_000
            readTimeout = 60_000
            instanceFollowRedirects = true
            // 途中で二重圧縮されるとSHA検証が落ちるため、素の本文を受け取る。
            setRequestProperty("Accept-Encoding", "identity")
        }
        try {
            val code = connection.responseCode
            check(code == HttpURLConnection.HTTP_OK) { "HTTP $code" }
            val hash = MessageDigest.getInstance("SHA-256")
            var received = 0L
            var lastReported = 0L
            FileOutputStream(destination).use { output ->
                connection.inputStream.use { input ->
                    val buffer = ByteArray(64 * 1024)
                    while (true) {
                        coroutineContext.ensureActive()
                        val read = input.read(buffer)
                        if (read <= 0) break
                        received += read
                        check(received <= EXPECTED_BYTES * 2) { "ダウンロードサイズが想定を超えました" }
                        output.write(buffer, 0, read)
                        hash.update(buffer, 0, read)
                        if (received - lastReported >= 128 * 1024) {
                            lastReported = received
                            onProgress(Progress(received))
                        }
                    }
                }
            }
            val actual = hash.digest().joinToString("") { "%02x".format(it) }
            check(actual.equals(SHA256, ignoreCase = true)) {
                "チェックサム不一致 (期待 $SHA256, 実際 $actual)"
            }
        } finally {
            connection.disconnect()
        }
    }

    private fun gunzipTo(source: File, destination: File) {
        GZIPInputStream(source.inputStream(), 64 * 1024).use { input ->
            FileOutputStream(destination).use { output ->
                val buffer = ByteArray(64 * 1024)
                var total = 0L
                while (true) {
                    val read = input.read(buffer)
                    if (read <= 0) break
                    total += read
                    check(total <= 32L * 1024L * 1024L) { "展開サイズが想定を超えました" }
                    output.write(buffer, 0, read)
                }
            }
        }
    }
}
