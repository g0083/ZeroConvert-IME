package com.zeroconvert.ime

import org.junit.Assert.*
import org.junit.Test

class DictionarySegmentsTest {
    private fun hex(value: String) = value.toByteArray(Charsets.UTF_8).joinToString("") { "%02x".format(it) }
    private fun segment(reading: String, surface: String, lcid: Int = 1, rcid: Int = 2) =
        "${hex(reading)}:${hex(surface)}:$lcid:$rcid:3:12.5"

    @Test fun morphologyCoversTheEntireReadingAndSurface() {
        val encoded = segment("トウキョウ", "東京") + "|" + segment("ニ", "に", 4, 5) + "|" + segment("イク", "行く")
        val parsed = DictionarySegments.parse(encoded, "トウキョウニイク", "東京に行く")
        assertEquals(3, parsed.size)
        assertEquals("東京", parsed[0].surface)
        assertEquals(4, parsed[1].lcid)
        assertEquals(5, parsed[1].rcid)
        assertEquals("イク", parsed[2].reading)
    }

    @Test fun malformedOrMismatchingSpansAreNotUsedForClauseBoundaries() {
        assertTrue(DictionarySegments.parse(segment("トウキョウ", "東京"), "トウキョウニ", "東京").isEmpty())
        assertTrue(DictionarySegments.parse(segment("トウキョウ", "東京"), "トウキョウ", "東亰").isEmpty())
        for (encoded in listOf("zz:ee:1:2:3:4", "ff:ff:1:2:3:4", segment("ア", "亜").replace("12.5", "NaN"), segment("ア", "亜") + "|")) {
            assertTrue(DictionarySegments.parse(encoded, "ア", "亜").isEmpty())
        }
    }

    @Test fun supplementaryCharactersSurviveTheNativeProtocol() {
        val parsed = DictionarySegments.parse(segment("エモジ", "😀"), "エモジ", "😀")
        assertEquals("😀", parsed.single().surface)
    }
}
