package com.zeroconvert.ime

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class EmojiCatalogTest {
    private val catalog = EmojiCatalog.parse(
        sequenceOf(
            "# comment",
            "smileys\t😀\tえがお,かお,笑顔\t",
            "smileys\t😢\tなみだ,泣く\t",
            "people\t👍\tいいね,賛成\t👍🏻|👍🏼|👍🏽|👍🏾|👍🏿",
            "flags\t🇯🇵\tにほん,はた\t"
        )
    )

    @Test fun categoriesKeepTheDeclaredOrderAndLabels() {
        assertEquals(listOf("smileys", "people", "flags"), catalog.map { it.id })
        assertEquals("笑顔", catalog[0].label)
        assertEquals("😀", catalog[0].icon)
        assertEquals(2, catalog[0].entries.size)
    }

    @Test fun skinToneVariantsStayOnTheirBaseEntry() {
        val thumbsUp = catalog.single { it.id == "people" }.entries.single()
        assertTrue(thumbsUp.hasSkinTones)
        assertEquals(5, thumbsUp.skinTones.size)
        assertEquals("👍🏿", thumbsUp.skinTones.last())
        assertFalse(catalog[0].entries.first().hasSkinTones)
    }

    @Test fun searchMatchesJapaneseKeywordsAndTheEmojiItself() {
        assertEquals(listOf("😢"), EmojiCatalog.search(catalog, "なみだ").map { it.emoji })
        assertEquals(listOf("😀"), EmojiCatalog.search(catalog, "笑顔").map { it.emoji })
        assertEquals(listOf("👍"), EmojiCatalog.search(catalog, "👍").map { it.emoji })
        assertTrue(EmojiCatalog.search(catalog, "  ").isEmpty())
        assertTrue(EmojiCatalog.search(catalog, "存在しない").isEmpty())
    }

    @Test fun recentListIsBoundedUniqueAndMostRecentFirst() {
        var recent = emptyList<String>()
        for (index in 0 until EmojiCatalog.RECENT_LIMIT + 5) {
            recent = EmojiCatalog.pushRecent(recent, "e$index")
        }
        assertEquals(EmojiCatalog.RECENT_LIMIT, recent.size)
        assertEquals("e${EmojiCatalog.RECENT_LIMIT + 4}", recent.first())

        val repeated = EmojiCatalog.pushRecent(listOf("😀", "😢", "😀"), "😢")
        assertEquals(listOf("😢", "😀"), repeated)
    }

    @Test fun recentEntriesResolveToneVariantsBackToTheirBaseEntry() {
        val entries = EmojiCatalog.recentEntries(listOf("👍🏽", "😀", "unknown"), catalog)
        assertEquals(listOf("👍🏽", "😀", "unknown"), entries.map { it.emoji })
        // The tone variant keeps the base entry's tone picker available.
        assertTrue(entries.first().hasSkinTones)
        assertTrue(entries.last().keywords.isEmpty())
    }

    @Test fun generatedCatalogCoversEveryCategoryWithSearchableKeywords() {
        val generated = EmojiCatalog.parse(
            TestAssets.file("emoji_catalog.tsv").bufferedReader(Charsets.UTF_8).readLines().asSequence()
        )
        assertEquals(
            listOf("smileys", "people", "nature", "food", "travel", "activities", "objects", "symbols", "flags"),
            generated.map { it.id }
        )
        val total = generated.sumOf { it.entries.size }
        assertTrue("expected the full Unicode palette, got $total", total > 1900)
        assertTrue(generated.all { category -> category.entries.all { it.keywords.isNotEmpty() } })
        assertTrue(generated.sumOf { category -> category.entries.count { it.hasSkinTones } } > 300)
        assertTrue(EmojiCatalog.search(generated, "おやゆび").any { it.emoji == "👍" })
    }
}
