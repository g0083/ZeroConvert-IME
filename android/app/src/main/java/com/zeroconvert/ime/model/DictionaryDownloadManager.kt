package com.zeroconvert.ime.model

import android.content.Context
import android.os.StatFs
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.BufferedOutputStream
import java.io.DataOutputStream
import java.io.File
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.zip.ZipFile
import kotlin.coroutines.coroutineContext

/**
 * 日本語辞書一式（azooKey標準辞書＋追加辞書）を初回起動時に取得する。
 *
 * APKには同梱せず、`windows/deployment/dictionary-catalog.json` の固定アセット（zip）を
 * GitHub リリースから取得して `files/dictionary/` へ展開する。取得済みなら何もしない。
 * 展開は「ステージング→置き換え→.complete」の順で行い、変換エンジンが半端な辞書を
 * 読まないようにする。
 *
 * 値は Windows 側 `windows/crates/shared/src/runtime_assets.rs` と同じカタログを正本とする。
 */
object DictionaryDownloadManager {
    private const val TAG = "DictionaryDownload"

    const val REPOSITORY = "g0083/ZeroConvert-IME"
    const val RELEASE_TAG = "dictionary-v2"
    const val FILENAME = "zerocon-dictionary-azookey-expanded.zip"
    const val DOWNLOAD_URL = "https://github.com/$REPOSITORY/releases/download/$RELEASE_TAG/$FILENAME"
    const val EXPECTED_BYTES = 60_975_173L
    const val SHA256 = "e0a5366edfccd7b1481c30211635b90c49a8b4b93830a077e24e68286138d030"
    const val FILE_COUNT = 5_230
    const val EXPANDED_BYTES = 197_378_811L

    private const val ARCHIVE_ROOT = "Dictionary"
    private const val COMPLETE_MARKER = ".complete"
    private const val PART_SUFFIX = ".part"
    private const val RESERVE_BYTES = 32L * 1024L * 1024L
    private const val REPORT_STEP_BYTES = 2L * 1024L * 1024L
    // 破損アセットの無限展開を防ぐ上限。
    private const val MAX_EXPANDED_BYTES = 512L * 1024L * 1024L
    private const val MAX_CONNECTION_ROWS = 1_318
    private const val DEFAULT_CONNECTION_COST = -25.0f
    private val downloadMutex = Mutex()

    data class Progress(val downloaded: Long, val total: Long) {
        val fraction: Float
            get() = if (total <= 0L) 0f
            else (downloaded.toDouble() / total.toDouble()).toFloat().coerceIn(0f, 1f)
    }

    fun root(context: Context): File = File(context.filesDir, "dictionary")

    /** 展開済みの `Dictionary` ディレクトリ（`louds/`, `Reverse/` を含む）。 */
    fun dictionaryDir(context: Context): File = File(root(context), ARCHIVE_ROOT)

    fun reverseDictionary(context: Context): File =
        File(dictionaryDir(context), "Reverse/reverse-v2.bin")

    fun isReady(context: Context): Boolean = isReadyAt(root(context))

    internal fun isReadyAt(root: File): Boolean {
        val marker = File(root, COMPLETE_MARKER)
        val sha256 = runCatching { marker.readText().trim() }.getOrNull()
        return sha256 == SHA256 && File(root, "$ARCHIVE_ROOT/louds/charID.chid").isFile
    }

    fun availableBytes(context: Context): Long = try {
        StatFs(context.filesDir.absolutePath).availableBytes
    } catch (_: Exception) {
        -1L
    }

    private fun archiveFile(context: Context): File = File(root(context), "$RELEASE_TAG$PART_SUFFIX")

    suspend fun download(context: Context, onProgress: (Progress) -> Unit = {}): Result<File> =
        downloadMutex.withLock {
            withContext(Dispatchers.IO) {
                runCatching {
                    if (isReady(context)) {
                        onProgress(Progress(EXPECTED_BYTES, EXPECTED_BYTES))
                        return@runCatching dictionaryDir(context)
                    }
                    val root = root(context).apply { mkdirs() }
                    val staging = File(root, "$ARCHIVE_ROOT.staging")
                    check(!staging.exists() || staging.deleteRecursively()) { "前回の展開途中のデータを削除できません" }
                    val archive = archiveFile(context)
                    val remaining = (EXPECTED_BYTES - archive.length()).coerceAtLeast(0L)
                    val required = remaining + EXPANDED_BYTES + RESERVE_BYTES
                    val available = availableBytes(context)
                    check(available < 0L || available >= required) {
                        "空き容量が足りません（約${(required + 1_048_575) / 1_048_576}MB必要）"
                    }
                    fetch(archive, onProgress)
                    extract(archive, root, staging, onProgress)
                    archive.delete()
                    check(isReady(context)) { "辞書を展開しましたが、必要なファイルが見つかりません" }
                    onProgress(Progress(EXPECTED_BYTES, EXPECTED_BYTES))
                    dictionaryDir(context)
                }.onFailure { Log.w(TAG, "辞書の取得に失敗しました", it) }
            }
        }

    /** Length of a partial download, for retry reporting. */
    fun partialBytes(context: Context): Long = archiveFile(context).let { if (it.isFile) it.length() else 0L }

    private suspend fun fetch(destination: File, onProgress: (Progress) -> Unit) {
        val existing = if (destination.isFile) destination.length() else 0L
        if (existing == EXPECTED_BYTES && VerifiedModelFile.sha256(destination).equals(SHA256, ignoreCase = true)) {
            onProgress(Progress(EXPECTED_BYTES, EXPECTED_BYTES))
            return
        }
        if (existing >= EXPECTED_BYTES) check(destination.delete()) { "前回の辞書アーカイブを削除できません" }
        val resumeFrom = if (destination.isFile) destination.length() else 0L
        val connection = (URL(DOWNLOAD_URL).openConnection() as HttpURLConnection).apply {
            connectTimeout = 30_000
            readTimeout = 60_000
            requestMethod = "GET"
            instanceFollowRedirects = true
            setRequestProperty("Accept-Encoding", "identity")
            setRequestProperty("User-Agent", "ZeroConvert-IME-Android")
            if (resumeFrom > 0L) setRequestProperty("Range", "bytes=$resumeFrom-")
        }
        try {
            connection.connect()
            val code = connection.responseCode
            check(code == HttpURLConnection.HTTP_OK || code == HttpURLConnection.HTTP_PARTIAL) {
                "ダウンロードに失敗しました (HTTP $code)"
            }
            var append = code == HttpURLConnection.HTTP_PARTIAL && resumeFrom > 0L
            if (!append) destination.delete()
            var written = if (append) resumeFrom else 0L
            var lastReported = -REPORT_STEP_BYTES
            onProgress(Progress(written, EXPECTED_BYTES))
            connection.inputStream.use { input ->
                FileOutputStream(destination, append).use { output ->
                    val buffer = ByteArray(256 * 1024)
                    while (true) {
                        coroutineContext.ensureActive()
                        val read = input.read(buffer)
                        if (read < 0) break
                        output.write(buffer, 0, read)
                        written += read
                        check(written <= EXPECTED_BYTES) { "辞書アセットのサイズが想定を超えました" }
                        if (written - lastReported >= REPORT_STEP_BYTES || written == EXPECTED_BYTES) {
                            lastReported = written
                            onProgress(Progress(written, EXPECTED_BYTES))
                        }
                    }
                }
            }
            check(written == EXPECTED_BYTES) { "ダウンロードが完了していません ($written/$EXPECTED_BYTES)" }
            val actual = VerifiedModelFile.sha256(destination)
            if (!actual.equals(SHA256, ignoreCase = true)) {
                destination.delete()
                error("辞書アセットのSHA-256が一致しません (期待 $SHA256, 実際 $actual)")
            }
        } finally {
            connection.disconnect()
        }
    }

    /**
     * `Dictionary/` 配下のエントリだけをステージングへ展開し、完了後に置き換える。
     * パスは zip 内の表記に関係なく `Dictionary/` 配下に収まることを検証する。
     */
    private fun extract(archive: File, root: File, staging: File, onProgress: (Progress) -> Unit) {
        check(staging.mkdirs() || staging.isDirectory) { "辞書の展開先を作成できません" }

        var count = 0
        var expanded = 0L
        ZipFile(archive, Charsets.UTF_8).use { zip ->
            val entries = zip.entries()
            while (entries.hasMoreElements()) {
                val entry = entries.nextElement()
                if (entry.isDirectory) continue
                val relative = relativePath(entry.name)
                expanded += entry.size
                check(expanded <= MAX_EXPANDED_BYTES) { "辞書の展開サイズが想定を超えました" }
                val destination = File(staging, relative)
                val parent = destination.parentFile
                check(parent == null || parent.isDirectory || parent.mkdirs()) { "辞書の展開先を作成できません" }
                zip.getInputStream(entry).use { input ->
                    FileOutputStream(destination).use { output -> input.copyTo(output) }
                }
                count += 1
                if (count % 256 == 0) onProgress(Progress(count.toLong(), FILE_COUNT.toLong()))
            }
        }
        if (count != FILE_COUNT) {
            staging.deleteRecursively()
            error("辞書のファイル数が一致しません (期待 $FILE_COUNT, 実際 $count)")
        }
        if (!File(staging, "louds/charID.chid").isFile) {
            staging.deleteRecursively()
            error("辞書アセットに louds/charID.chid がありません")
        }
        packConnectionCosts(staging)
        val target = File(root, ARCHIVE_ROOT)
        val marker = File(root, COMPLETE_MARKER)
        check(!marker.exists() || marker.delete()) { "辞書の完了マーカーを更新できません" }
        if (target.exists() && !target.deleteRecursively()) error("既存の辞書を置き換えられません")
        check(staging.renameTo(target)) { "辞書を配置できませんでした" }
        marker.writeText(SHA256)
    }

    /** `CBSP` はネイティブ側が float をそのまま `memcpy` するため little-endian で書く。 */
    private fun DataOutputStream.writeLittleEndianFloat(value: Float) {
        val bits = java.lang.Float.floatToIntBits(value)
        write(bits and 0xFF)
        write((bits shr 8) and 0xFF)
        write((bits shr 16) and 0xFF)
        write((bits shr 24) and 0xFF)
    }

    /**
     * 旧LOUDS経路（`ZenzNative.lookupDict` の形態素解析と、誤入力補正の辞書照合）が
     * 使う `cb_packed.binary` を `cb/<id>.binary` から作る。
     *
     * azooKey 辞書の `cb/<former>.binary` は (Int32 キー, Float 値) の列で、先頭キー `-1`
     * が既定値。旧実装が読む `CBSP` 形式（行数 → 各行 既定値 + 疎な後続キー）へ
     * 詰め替えるだけで、値は変えない。`cb/` が無い辞書では何もしない。
     */
    internal fun packConnectionCosts(dictionary: File) {
        val files = File(dictionary, "cb").listFiles() ?: return
        val rows = HashMap<Int, File>()
        for (file in files) {
            if (!file.isFile || !file.name.endsWith(".binary")) continue
            val index = file.name.removeSuffix(".binary").toIntOrNull() ?: continue
            if (index in 0..MAX_CONNECTION_ROWS) rows[index] = file
        }
        if (rows.isEmpty()) return
        val output = File(dictionary, "cb_packed.binary.part")
        try {
            DataOutputStream(BufferedOutputStream(FileOutputStream(output), 1 shl 16)).use { out ->
                out.write("CBSP".toByteArray(Charsets.US_ASCII))
                val count = rows.keys.max() + 1
                check(count <= MAX_CONNECTION_ROWS + 1) { "連接コスト表の行数が想定を超えました ($count)" }
                out.write(count and 0xFF)
                out.write((count shr 8) and 0xFF)
                for (row in 0 until count) {
                    val file = rows[row]
                    val entries = ArrayList<Pair<Int, Float>>()
                    var default = DEFAULT_CONNECTION_COST
                    if (file != null) {
                        val buffer = ByteBuffer.wrap(file.readBytes()).order(ByteOrder.LITTLE_ENDIAN)
                        while (buffer.remaining() >= 8) {
                            val key = buffer.int
                            val value = buffer.float
                            if (key == -1) default = value else if (key in 0..MAX_CONNECTION_ROWS) entries.add(key to value)
                        }
                    }
                    out.writeLittleEndianFloat(default)
                    out.write(entries.size and 0xFF)
                    out.write((entries.size shr 8) and 0xFF)
                    for ((key, value) in entries) {
                        out.write(key and 0xFF)
                        out.write((key shr 8) and 0xFF)
                        out.writeLittleEndianFloat(value)
                    }
                }
            }
            check(output.renameTo(File(dictionary, "cb_packed.binary"))) {
                "連接コスト表を配置できませんでした"
            }
        } finally {
            output.delete()
        }
    }

    /** `Dictionary/<relative>` 以外、絶対パス、`..` を拒否する。 */
    internal fun relativePath(name: String): String {
        val normalized = name.replace('\\', '/')
        val parts = normalized.split('/')
        require(parts.firstOrNull() == ARCHIVE_ROOT) { "辞書アセットに想定外のエントリがあります: $name" }
        val relative = parts.drop(1).filter { it.isNotEmpty() && it != "." }
        require(relative.isNotEmpty() && relative.none { it == ".." }) { "辞書アセットに不正なパスがあります: $name" }
        return relative.joinToString("/")
    }
}
