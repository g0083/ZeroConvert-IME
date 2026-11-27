package com.zeroconvert.ime.theme

import android.content.Context
import android.content.SharedPreferences
import com.zeroconvert.ime.ZeroConvertInputMethodService
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.UUID

class CustomThemeRepository(private val rootDir: File) {

    fun generateCustomThemeId(): String =
        "custom_${UUID.randomUUID().toString().replace("-", "").take(12)}"

    fun sanitizeThemeId(raw: String): String {
        val cleaned = raw.trim().replace(Regex("[^A-Za-z0-9_-]"), "")
        return if (cleaned.isEmpty() || cleaned == "." || cleaned == "..") "custom_theme" else cleaned
    }

    private fun themeDir(themeId: String): File = File(rootDir, sanitizeThemeId(themeId))

    fun specFile(themeId: String): File = File(themeDir(themeId), SPEC_FILE)

    fun sourceImageFile(themeId: String): File = File(themeDir(themeId), SOURCE_IMAGE_FILE)

    fun renderedImageFile(themeId: String, fileName: String = RENDERED_IMAGE_FILE): File {
        val safeName = File(fileName).name.ifBlank { RENDERED_IMAGE_FILE }
        return File(themeDir(themeId), safeName)
    }

    fun listThemes(): List<KeyboardThemeSpec> {
        if (!rootDir.exists() || !rootDir.isDirectory) return emptyList()
        val indexedIds = readIndexIds()
        val seen = HashSet<String>()
        val result = ArrayList<KeyboardThemeSpec>()

        for (id in indexedIds) {
            val safeId = sanitizeThemeId(id)
            if (seen.add(safeId)) {
                loadTheme(safeId)?.let { result.add(it) }
            }
        }

        val dirs = rootDir.listFiles()?.filter { it.isDirectory }?.sortedByDescending { it.lastModified() }.orEmpty()
        for (dir in dirs) {
            val safeId = sanitizeThemeId(dir.name)
            if (seen.add(safeId)) {
                loadTheme(safeId)?.let { result.add(it) }
            }
        }
        return result
    }

    fun loadTheme(themeId: String): KeyboardThemeSpec? {
        val file = specFile(themeId)
        if (!file.isFile) return null
        return try {
            val raw = file.readText(Charsets.UTF_8)
            KeyboardThemeJson.decode(raw)?.copy(
                id = sanitizeThemeId(themeId),
                isBuiltIn = false,
                category = ThemeCategory.CUSTOM
            )
        } catch (_: Exception) {
            null
        }
    }

    fun saveTheme(
        spec: KeyboardThemeSpec,
        sourceImageBytes: ByteArray? = null,
        renderedImageBytes: ByteArray? = null,
        clearImages: Boolean = false
    ): KeyboardThemeSpec {
        if (!rootDir.exists()) rootDir.mkdirs()
        val safeId = sanitizeThemeId(spec.id)
        val normalized = spec.copy(
            id = safeId,
            category = ThemeCategory.CUSTOM,
            isBuiltIn = false
        ).sanitized()
        val dir = themeDir(safeId)
        if (!dir.exists()) dir.mkdirs()

        writeBytesAtomic(specFile(safeId), KeyboardThemeJson.encode(normalized).toByteArray(Charsets.UTF_8))

        if (clearImages) {
            sourceImageFile(safeId).delete()
            renderedImageFile(safeId).delete()
        } else {
            if (sourceImageBytes != null) {
                writeBytesAtomic(sourceImageFile(safeId), sourceImageBytes)
            }
            if (renderedImageBytes != null) {
                val targetName = normalized.background.imageFileName ?: RENDERED_IMAGE_FILE
                writeBytesAtomic(renderedImageFile(safeId, targetName), renderedImageBytes)
            }
        }

        val currentIds = readIndexIds().map(::sanitizeThemeId).filter { it != safeId }.toMutableList()
        currentIds.add(0, safeId)
        val trimmed = if (currentIds.size > MAX_CUSTOM_THEMES) {
            val overflow = currentIds.subList(MAX_CUSTOM_THEMES, currentIds.size).toList()
            overflow.forEach { oldId -> themeDir(oldId).deleteRecursively() }
            currentIds.take(MAX_CUSTOM_THEMES)
        } else {
            currentIds
        }
        writeIndexIds(trimmed)
        return normalized
    }

    fun duplicateTheme(source: KeyboardThemeSpec, newName: String): KeyboardThemeSpec {
        val newId = generateCustomThemeId()
        val sourceId = sanitizeThemeId(source.id)
        val sourceBytes = if (!source.isBuiltIn) {
            sourceImageFile(sourceId).takeIf { it.isFile }?.let { runCatching { it.readBytes() }.getOrNull() }
        } else {
            null
        }
        val renderedName = source.background.imageFileName ?: RENDERED_IMAGE_FILE
        val renderedBytes = if (!source.isBuiltIn && source.background.kind == BackgroundKind.IMAGE) {
            renderedImageFile(sourceId, renderedName).takeIf { it.isFile }?.let { runCatching { it.readBytes() }.getOrNull() }
        } else {
            null
        }
        val duplicated = source.copy(
            id = newId,
            name = newName.trim().ifEmpty { "${source.name} コピー" },
            category = ThemeCategory.CUSTOM,
            isBuiltIn = false
        )
        return saveTheme(
            spec = duplicated,
            sourceImageBytes = sourceBytes,
            renderedImageBytes = renderedBytes,
            clearImages = source.background.kind != BackgroundKind.IMAGE
        )
    }

    fun deleteTheme(themeId: String): Boolean {
        val safeId = sanitizeThemeId(themeId)
        val dir = themeDir(safeId)
        val existed = dir.exists()
        if (existed) {
            dir.deleteRecursively()
        }
        val updated = readIndexIds().map(::sanitizeThemeId).filter { it != safeId }
        writeIndexIds(updated)
        return existed
    }

    fun bumpRevision(prefs: SharedPreferences): Long {
        val next = prefs.getLong(ZeroConvertInputMethodService.KEY_CUSTOM_THEME_REVISION, 0L) + 1L
        prefs.edit().putLong(ZeroConvertInputMethodService.KEY_CUSTOM_THEME_REVISION, next).apply()
        return next
    }

    private fun readIndexIds(): List<String> {
        val indexFile = File(rootDir, INDEX_FILE)
        if (!indexFile.isFile) return emptyList()
        return try {
            val obj = JSONObject(indexFile.readText(Charsets.UTF_8))
            val arr = obj.optJSONArray("themes") ?: return emptyList()
            buildList(arr.length()) {
                for (i in 0 until arr.length()) {
                    val id = arr.optString(i, "").trim()
                    if (id.isNotEmpty()) add(id)
                }
            }
        } catch (_: Exception) {
            emptyList()
        }
    }

    private fun writeIndexIds(ids: List<String>) {
        if (!rootDir.exists()) rootDir.mkdirs()
        val arr = JSONArray()
        ids.forEach { arr.put(it) }
        val root = JSONObject().put("themes", arr)
        writeBytesAtomic(File(rootDir, INDEX_FILE), root.toString(2).toByteArray(Charsets.UTF_8))
    }

    private fun writeBytesAtomic(target: File, bytes: ByteArray) {
        val parent = target.parentFile
        if (parent != null && !parent.exists()) parent.mkdirs()
        val tmp = File(parent, "${target.name}.tmp")
        tmp.writeBytes(bytes)
        if (target.exists() && !tmp.renameTo(target)) {
            target.writeBytes(bytes)
            tmp.delete()
        } else if (!target.exists() && !tmp.renameTo(target)) {
            target.writeBytes(bytes)
            tmp.delete()
        }
    }

    companion object {
        const val MAX_CUSTOM_THEMES = 30
        const val SOURCE_IMAGE_FILE = "bg_source.webp"
        const val RENDERED_IMAGE_FILE = "bg_rendered.webp"
        private const val SPEC_FILE = "spec.json"
        private const val INDEX_FILE = "index.json"

        fun forContext(context: Context): CustomThemeRepository =
            CustomThemeRepository(File(context.filesDir, "themes"))
    }
}
