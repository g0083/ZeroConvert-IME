package com.zeroconvert.ime

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class LicenseNoticesTest {

    private fun shared(path: String): File {
        val candidates = listOf(
            File(path),
            File("../$path"),
            File("../../$path")
        )
        return candidates.firstOrNull { it.exists() }
            ?: error("license source not found: $path")
    }

    // The APK copy is produced by the sharedHostAssets Gradle task; this reads the canonical file.
    private fun sharedDocument(): String = shared("licenses/third-party.json").readText()

    @Test
    fun androidEntriesComeFromTheSharedDocument() {
        val entries = LicenseNotices.parse(sharedDocument())
        assertTrue(entries.isNotEmpty())
        assertEquals(entries.size, entries.map { it.id }.toSet().size)
        assertTrue(entries.any { it.id == "androidx-compose" })
        assertTrue(entries.any { it.id == "kotlin" })
        assertTrue(entries.none { it.id == "frontend-ui" || it.id == "rust-crates" })
        assertTrue(entries.all { it.title.isNotBlank() })
        assertTrue(entries.all { it.description.isNotBlank() })
        assertTrue(entries.all { it.license.isNotBlank() || it.description.isNotBlank() })
    }

    @Test
    fun windowsOnlyEntriesAreFilteredOut() {
        val json = """
            {"entries":[
              {"id":"both","title":"Both","credit":"c","license":"MIT","platforms":["android","windows"]},
              {"id":"desktop","title":"Desktop","credit":"c","license":"MIT","platforms":["windows"]},
              {"id":"mobile","title":"Mobile","credit":"c","license":"Apache-2.0","platforms":["android"]}
            ]}
        """.trimIndent()
        val entries = LicenseNotices.parse(json)
        assertEquals(listOf("both", "mobile"), entries.map { it.id })
    }

    @Test
    fun platformNoteOverridesTheSharedNote() {
        val json = """
            {"entries":[
              {"id":"x","title":"X","credit":"c","license":"MIT","note":"generic",
               "notes":{"android":"android only text","windows":"windows only text"},"platforms":["android","windows"]}
            ]}
        """.trimIndent()
        assertEquals("android only text", LicenseNotices.parse(json).single().description)
    }
}
