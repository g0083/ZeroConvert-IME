package com.zeroconvert.ime

import android.content.Context
import android.net.Uri
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject

private data class ImportPreview(
    val parsed: DictionaryImport,
    val merge: DictionaryMerge,
    val replacement: DictionaryMerge
)

@Composable
internal fun UserDictionarySettings(palette: ZeroConPalette) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var codec by remember { mutableStateOf<UserDictionaryFile?>(null) }
    var entries by remember { mutableStateOf(emptyList<UserDictEntry>()) }
    var busy by remember { mutableStateOf(false) }
    var managing by rememberSaveable { mutableStateOf(false) }
    var editing by remember { mutableStateOf(false) }
    var original by remember { mutableStateOf<UserDictEntry?>(null) }
    var editError by remember { mutableStateOf<String?>(null) }
    var exportOpen by rememberSaveable { mutableStateOf(false) }
    var exportFormat by rememberSaveable { mutableStateOf(DictionaryExportFormat.JSON) }
    var preview by remember { mutableStateOf<ImportPreview?>(null) }
    var replace by remember { mutableStateOf(false) }

    fun notice(message: String) = Toast.makeText(context, message, Toast.LENGTH_LONG).show()
    fun runFileOperation(operation: suspend () -> Unit) {
        busy = true
        scope.launch {
            try {
                operation()
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                notice(error.localizedMessage ?: "辞書ファイルを処理できませんでした。")
            } finally {
                busy = false
            }
        }
    }

    LaunchedEffect(context) {
        val loaded = withContext(Dispatchers.IO) {
            loadDictionaryCodec(context) to KanaKanjiConverter.getUserDictionary(context)
        }
        codec = loaded.first
        entries = loaded.second
    }

    val openDocument = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) runFileOperation {
            preview = withContext(Dispatchers.IO) {
                val text = context.contentResolver.openInputStream(uri)?.use(::readDictionaryText)
                    ?: error("選んだファイルを開けませんでした。")
                val parsed = (codec ?: loadDictionaryCodec(context)).parse(text)
                val current = KanaKanjiConverter.getUserDictionary(context)
                ImportPreview(parsed, mergeDictionaryEntries(current, parsed.entries),
                    mergeDictionaryEntries(emptyList(), parsed.entries))
            }
            replace = false
        }
    }
    fun export(uri: Uri?, format: DictionaryExportFormat) {
        if (uri != null) runFileOperation {
            val count = withContext(Dispatchers.IO) {
                val dictionary = KanaKanjiConverter.getUserDictionary(context).exportableEntries(format)
                val bytes = (codec ?: loadDictionaryCodec(context)).encode(dictionary, format)
                context.contentResolver.openOutputStream(uri, "wt")?.use { it.write(bytes) }
                    ?: error("保存先を開けませんでした。")
                dictionary.size
            }
            notice("${count}件を書き出しました。")
        }
    }
    val createJson = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument(DictionaryExportFormat.JSON.mimeType)) { uri ->
        export(uri, DictionaryExportFormat.JSON)
    }
    val createText = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument(DictionaryExportFormat.MOZC.mimeType)) { uri ->
        export(uri, exportFormat)
    }

    val file = codec
    val ready = file != null && !busy
    SettingsSection("ユーザー辞書", palette) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("登録数 ${entries.size}件", color = palette.textSecondary)
            Text(
                "Google 日本語入力 / Mozc と Microsoft IME の辞書を、品詞付きで読み込み・書き出しできます。ATOK のテキスト辞書も読み込めます。",
                color = palette.textSecondary
            )
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                SettingsPrimaryButton("単語を追加", palette, {
                    original = null
                    editError = null
                    editing = true
                }, Modifier.weight(1f), enabled = ready && entries.size < MAX_USER_DICTIONARY_ENTRIES)
                SettingsSecondaryButton("登録語を編集", palette, { managing = true }, Modifier.weight(1f),
                    enabled = ready && entries.isNotEmpty())
            }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                SettingsSecondaryButton("読み込み", palette, { openDocument.launch(arrayOf("*/*")) },
                    Modifier.weight(1f), enabled = ready)
                SettingsSecondaryButton("書き出し", palette, { exportOpen = true }, Modifier.weight(1f),
                    enabled = ready && entries.isNotEmpty())
            }
        }
    }

    if (file != null && managing && !editing) {
        DictionaryEntriesDialog(entries, file, palette, { managing = false }) {
            original = it
            editError = null
            editing = true
        }
    }
    if (file != null && editing) {
        val old = original
        DictionaryEntryDialog(old, file, palette, busy, editError, { editing = false },
            onSave = { draft ->
                busy = true
                scope.launch {
                    val (error, saved) = withContext(Dispatchers.IO) {
                        KanaKanjiConverter.saveUserDictEntry(context, draft, old) to KanaKanjiConverter.getUserDictionary(context)
                    }
                    entries = saved
                    editError = error
                    busy = false
                    if (error == null) editing = false
                }
            },
            onDelete = {
                if (old != null) runFileOperation {
                    entries = withContext(Dispatchers.IO) {
                        KanaKanjiConverter.removeUserDictEntry(context, old)
                        KanaKanjiConverter.getUserDictionary(context)
                    }
                    editing = false
                }
            }
        )
    }
    if (exportOpen) {
        val exportCount = remember(entries, exportFormat) { entries.exportableEntries(exportFormat).size }
        AlertDialog(
            onDismissRequest = { exportOpen = false },
            title = { Text("ユーザー辞書を書き出す") },
            text = {
                Column {
                    Text("読み・単語・品詞を書き出します。移行先の IME に合わせて形式を選んでください。")
                    for (format in DictionaryExportFormat.entries) {
                        DictionaryRadioOption(format.label, format == exportFormat) { exportFormat = format }
                    }
                    Text(when (exportFormat) {
                        DictionaryExportFormat.JSON -> "Windows と Android の間で品詞をそのまま引き継げます。"
                        DictionaryExportFormat.MOZC -> "Google 日本語入力 / Mozc の品詞名で書き出します。サ変名詞は「名詞サ変」になります。"
                        DictionaryExportFormat.MSIME -> "Microsoft IME の品詞名で書き出します。組織は「固有名詞」、記号・顔文字は「顔文字」になります。"
                    })
                    if (exportCount < entries.size) {
                        Text("この形式で表現できない行を ${entries.size - exportCount} 件除外します。")
                    }
                }
            },
            confirmButton = {
                TextButton(enabled = exportCount > 0, onClick = {
                    exportOpen = false
                    val name = "zeroconvert-user-dictionary.${exportFormat.extension}"
                    if (exportFormat == DictionaryExportFormat.JSON) createJson.launch(name) else createText.launch(name)
                }) { Text("書き出す") }
            },
            dismissButton = { TextButton(onClick = { exportOpen = false }) { Text("キャンセル") } }
        )
    }
    preview?.let { pending ->
        val result = if (replace) pending.replacement else pending.merge
        AlertDialog(
            onDismissRequest = { if (!busy) preview = null },
            title = { Text("ユーザー辞書を読み込む") },
            text = {
                Column {
                    Text("読み・単語・品詞を取り込みます。他の IME の品詞名は ZeroConvert の品詞に変換します。")
                    DictionaryRadioOption("追加する", !replace) { replace = false }
                    DictionaryRadioOption("現在の辞書をすべて置き換える", replace) { replace = true }
                    Text(buildString {
                        append(if (replace) "置き換え後 ${result.added}件" else "追加 ${result.added}件")
                        if (result.duplicates > 0) append("\n重複 ${result.duplicates}件")
                        if (result.overflow > 0) append("\n上限超過 ${result.overflow}件")
                        if (pending.parsed.skipped > 0) append("\n使えない行 ${pending.parsed.skipped}件")
                        if (pending.parsed.mapped > 0) append("\n未対応の品詞 ${pending.parsed.mapped}件は一般名詞として登録")
                    })
                }
            },
            confirmButton = {
                TextButton(enabled = !busy && result.added > 0, onClick = {
                    val replaceExisting = replace
                    runFileOperation {
                        val saved = withContext(Dispatchers.IO) {
                            KanaKanjiConverter.importUserDictEntries(context, pending.parsed.entries, replaceExisting)
                        }
                        entries = saved.entries
                        preview = null
                        notice(if (replaceExisting) "${saved.added}件で置き換えました。" else "${saved.added}件を追加しました。")
                    }
                }) { Text(if (replace) "置き換える" else "追加する") }
            },
            dismissButton = { TextButton(enabled = !busy, onClick = { preview = null }) { Text("キャンセル") } }
        )
    }
}

private fun loadDictionaryCodec(context: Context): UserDictionaryFile =
    context.assets.open("user-dictionary-pos.json").bufferedReader().use { UserDictionaryFile(JSONObject(it.readText())) }

@Composable
private fun DictionaryEntriesDialog(
    entries: List<UserDictEntry>,
    file: UserDictionaryFile,
    palette: ZeroConPalette,
    close: () -> Unit,
    edit: (UserDictEntry) -> Unit
) {
    var query by rememberSaveable { mutableStateOf("") }
    val visible = remember(entries, query) {
        entries.filter { it.reading.contains(query, ignoreCase = true) || it.word.contains(query, ignoreCase = true) }
    }
    AlertDialog(
        onDismissRequest = close,
        title = { Text("登録語を編集") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(query, { query = it }, label = { Text("読み・単語で検索") },
                    singleLine = true, colors = settingsTextFieldColors(palette))
                LazyColumn(Modifier.heightIn(max = 320.dp)) {
                    items(visible) { entry ->
                        TextButton(onClick = { edit(entry) }, modifier = Modifier.fillMaxWidth()) {
                            Column(Modifier.fillMaxWidth()) {
                                Text(entry.word, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                Text("${entry.reading} ／ ${file.label(entry.pos)}", color = palette.textSecondary)
                            }
                        }
                    }
                }
                if (visible.isEmpty()) Text("該当する単語がありません。")
            }
        },
        confirmButton = { TextButton(onClick = close) { Text("閉じる") } }
    )
}

@Composable
private fun DictionaryEntryDialog(
    entry: UserDictEntry?,
    file: UserDictionaryFile,
    palette: ZeroConPalette,
    busy: Boolean,
    error: String?,
    close: () -> Unit,
    onSave: (UserDictEntry) -> Unit,
    onDelete: () -> Unit
) {
    var reading by rememberSaveable { mutableStateOf(entry?.reading.orEmpty()) }
    var word by rememberSaveable { mutableStateOf(entry?.word.orEmpty()) }
    var pos by rememberSaveable { mutableStateOf(entry?.pos ?: UserDictionaryPos.COMMON_NOUN) }
    var choosingPos by remember { mutableStateOf(false) }
    AlertDialog(
        onDismissRequest = { if (!busy) close() },
        title = { Text(if (entry == null) "単語を追加" else "単語を編集") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(reading, { reading = it }, label = { Text("読み") }, singleLine = true,
                    colors = settingsTextFieldColors(palette))
                OutlinedTextField(word, { word = it }, label = { Text("単語") }, singleLine = true,
                    colors = settingsTextFieldColors(palette))
                Box {
                    TextButton(enabled = !busy, onClick = { choosingPos = true }) { Text("品詞  ${file.label(pos)} ▾") }
                    DropdownMenu(expanded = choosingPos, onDismissRequest = { choosingPos = false }) {
                        for (option in file.positions) {
                            DropdownMenuItem(text = { Text(file.label(option)) }, onClick = {
                                pos = option
                                choosingPos = false
                            })
                        }
                    }
                }
                if (error != null) Text(error, color = palette.danger)
                if (entry != null) TextButton(enabled = !busy, onClick = onDelete) { Text("削除", color = palette.danger) }
            }
        },
        confirmButton = {
            TextButton(enabled = !busy && reading.isNotBlank() && word.isNotBlank(),
                onClick = { onSave(UserDictEntry(reading, word, pos)) }) { Text("保存") }
        },
        dismissButton = { TextButton(enabled = !busy, onClick = close) { Text("キャンセル") } }
    )
}

@Composable
private fun DictionaryRadioOption(label: String, selected: Boolean, select: () -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        RadioButton(selected = selected, onClick = select)
        TextButton(onClick = select) { Text(label) }
    }
}
