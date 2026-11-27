package com.zeroconvert.ime.theme

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class CustomThemeRepositoryTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    @Test
    fun saveLoadAndListThemes_ordersMostRecentlySavedFirst() {
        val repo = CustomThemeRepository(File(tempFolder.root, "themes"))
        val first = KeyboardThemeCatalog.defaultDark.copy(
            id = "custom_first",
            name = "First Theme",
            category = ThemeCategory.STANDARD,
            isBuiltIn = true
        )
        val second = KeyboardThemeCatalog.defaultLight.copy(
            id = "custom_second",
            name = "Second Theme",
            category = ThemeCategory.OS_STYLE,
            isBuiltIn = true
        )

        val savedFirst = repo.saveTheme(first)
        val savedSecond = repo.saveTheme(second)

        assertFalse(savedFirst.isBuiltIn)
        assertEquals(ThemeCategory.CUSTOM, savedFirst.category)
        assertEquals(listOf("custom_second", "custom_first"), repo.listThemes().map { it.id })

        val loaded = repo.loadTheme("custom_first")
        assertNotNull(loaded)
        assertEquals("First Theme", loaded!!.name)
        assertEquals(savedSecond.id, repo.listThemes().first().id)
    }

    @Test
    fun saveTheme_writesAndClearsImageFiles() {
        val repo = CustomThemeRepository(File(tempFolder.root, "themes"))
        val photoSpec = KeyboardThemeCatalog.defaultDark.copy(
            id = "custom_photo",
            name = "Photo Skin",
            background = KeyboardThemeCatalog.defaultDark.background.copy(
                kind = BackgroundKind.IMAGE,
                imageFileName = CustomThemeRepository.RENDERED_IMAGE_FILE
            )
        )
        val sourceBytes = byteArrayOf(1, 2, 3, 4)
        val renderedBytes = byteArrayOf(5, 6, 7, 8)

        repo.saveTheme(
            spec = photoSpec,
            sourceImageBytes = sourceBytes,
            renderedImageBytes = renderedBytes,
            clearImages = false
        )

        assertTrue(repo.sourceImageFile("custom_photo").isFile)
        assertTrue(repo.renderedImageFile("custom_photo").isFile)
        assertArrayEquals(sourceBytes, repo.sourceImageFile("custom_photo").readBytes())
        assertArrayEquals(renderedBytes, repo.renderedImageFile("custom_photo").readBytes())

        repo.saveTheme(
            spec = photoSpec.copy(background = photoSpec.background.copy(kind = BackgroundKind.SOLID, imageFileName = null)),
            clearImages = true
        )
        assertFalse(repo.sourceImageFile("custom_photo").exists())
        assertFalse(repo.renderedImageFile("custom_photo").exists())
    }

    @Test
    fun duplicateTheme_createsIndependentCopyWithImages() {
        val repo = CustomThemeRepository(File(tempFolder.root, "themes"))
        val sourceSpec = KeyboardThemeCatalog.defaultDark.copy(
            id = "custom_src",
            name = "Original Photo",
            background = KeyboardThemeCatalog.defaultDark.background.copy(
                kind = BackgroundKind.IMAGE,
                imageFileName = CustomThemeRepository.RENDERED_IMAGE_FILE
            )
        )
        val savedSource = repo.saveTheme(
            spec = sourceSpec,
            sourceImageBytes = byteArrayOf(9, 8, 7),
            renderedImageBytes = byteArrayOf(4, 3, 2),
            clearImages = false
        )

        val copy = repo.duplicateTheme(savedSource, "Original Copy")
        assertNotEquals(savedSource.id, copy.id)
        assertEquals("Original Copy", copy.name)
        assertFalse(copy.isBuiltIn)
        assertEquals(ThemeCategory.CUSTOM, copy.category)
        assertArrayEquals(byteArrayOf(9, 8, 7), repo.sourceImageFile(copy.id).readBytes())
        assertArrayEquals(byteArrayOf(4, 3, 2), repo.renderedImageFile(copy.id).readBytes())
    }

    @Test
    fun deleteTheme_removesFilesAndIndexEntry() {
        val repo = CustomThemeRepository(File(tempFolder.root, "themes"))
        val spec = KeyboardThemeCatalog.defaultDark.copy(id = "custom_del", name = "Delete Me")
        repo.saveTheme(spec)

        assertTrue(repo.deleteTheme("custom_del"))
        assertFalse(repo.deleteTheme("custom_del"))
        assertNull(repo.loadTheme("custom_del"))
        assertTrue(repo.listThemes().isEmpty())
    }

    @Test
    fun sanitizeThemeId_andCorruptedJson_areHandledSafely() {
        val repo = CustomThemeRepository(File(tempFolder.root, "themes"))
        assertEquals("custom_theme", repo.sanitizeThemeId(".."))
        assertEquals("custom_theme", repo.sanitizeThemeId("   "))
        assertEquals("evilpath", repo.sanitizeThemeId("../evil/path"))

        val valid = repo.saveTheme(KeyboardThemeCatalog.defaultLight.copy(id = "valid_id", name = "Valid"))
        val brokenDir = File(File(tempFolder.root, "themes"), "broken_id").apply { mkdirs() }
        File(brokenDir, "spec.json").writeText("{not valid json")

        assertNull(repo.loadTheme("broken_id"))
        val listed = repo.listThemes()
        assertEquals(listOf(valid.id), listed.map { it.id })
    }
}
