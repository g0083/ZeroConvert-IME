package com.zeroconvert.ime

import org.junit.Assert.*
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream

class MorphologyLearningMemoryTest {
    private val alphabet = (0x30A1..0x30F6).toSet()
    private fun word(reading: String, surface: String = reading, lcid: Int = 1288, rcid: Int = lcid, mid: Int = 501) =
        DictionarySegment(reading, surface, lcid, rcid, mid, -10f)

    @Test fun frequencySaturatesAndRaisesTheLearnedLexicalScore() {
        val memory = MorphologyLearningMemory(alphabet, day = { 100 })
        memory.record(listOf(word("カンジ", "漢字")))
        val first = memory.all().single()
        repeat(300) { memory.record(listOf(word("カンジ", "漢字", mid = 500))) }
        val frequent = memory.all().single()
        assertEquals(255, frequent.count)
        assertEquals(501, frequent.mid) // Same ruby/word/CIDs identify an entry, not MID.
        assertEquals(-1f - 4f / 3f, frequent.score, 0.00001f)
        assertTrue(frequent.score > first.score)
    }

    @Test fun wordClauseAndWholeSelectionUseSwiftClassIdsAndCounts() {
        val memory = MorphologyLearningMemory(alphabet, day = { 100 })
        memory.record(listOf(word("ネコ", "猫"), word("ヲ", "を", 147, 100), word("ミル", "見る", 561, 561, 20)))
        val entries = memory.all()
        assertTrue(entries.any { it.surface == "猫" })
        assertFalse(entries.any { it.surface == "を" })
        val compound = entries.filter { it.surface == "猫を見る" }
        assertEquals(setOf(100, 561), compound.map { it.rcid }.toSet()) // Bigram uses first.rcid, whole uses last.rcid.
        assertTrue(compound.all { it.lcid == 1288 && it.mid == 20 && it.reading == "ネコヲミル" })
        val singleParticle = MorphologyLearningMemory(alphabet)
        singleParticle.record(listOf(word("ヲ", "を", 147)))
        assertEquals(0, singleParticle.size)
    }

    @Test fun adjacentContentWordsTeachBothTheBigramAndTheWholeSelection() {
        val memory = MorphologyLearningMemory(alphabet)
        memory.record(listOf(word("ネコ", "猫"), word("イヌ", "犬")))
        assertEquals(2, memory.all().single { it.surface == "猫犬" }.count)
    }

    @Test fun previousPartialCommitIsContextAndDoesNotRelearnItsSingleWord() {
        val memory = MorphologyLearningMemory(alphabet)
        memory.record(listOf(word("イヌ", "犬")), previous = word("ネコ", "猫"))
        assertFalse(memory.all().any { it.surface == "猫" })
        assertEquals(1, memory.all().single { it.surface == "犬" }.count)
        assertEquals(2, memory.all().single { it.surface == "猫犬" }.count)
    }

    @Test fun decayUsesStrict32DayBoundaryAndExpiresAt128UnusedDays() {
        val memory = MorphologyLearningMemory(alphabet, day = { 100 })
        repeat(4) { memory.record(listOf(word("ネコ", "猫"))) }
        memory.age(132)
        assertEquals(4, memory.all().single().count)
        memory.age(133)
        assertEquals(2, memory.all().single().count)
        memory.age(164)
        assertEquals(2, memory.all().single().count)
        memory.age(165)
        assertEquals(1, memory.all().single().count)
        memory.age(228)
        assertEquals(0, memory.size)
    }

    @Test fun rollbackResetsMetadataAndUnsupportedOrCorrectedInputIsNotRecorded() {
        val memory = MorphologyLearningMemory(alphabet, day = { 100 })
        repeat(4) { memory.record(listOf(word("ネコ", "猫"))) }
        memory.age(90)
        assertEquals(1, memory.all().single().count)
        assertEquals(90, memory.all().single().lastUsedDay)
        memory.record(listOf(word("ABC")))
        memory.record(listOf(word("イヌ").copy(correctionKind = 3)))
        memory.record(listOf(word("イヌ").copy(lcid = 9999)))
        assertEquals(1, memory.size)
        memory.record(listOf(word("ABC"), word("イヌ", "犬")))
        assertTrue(memory.all().any { it.surface == "犬" })
        assertFalse(memory.all().any { it.reading.contains("ABC") })
    }

    @Test fun indexFindsInternalSpansAndCapacityRetainsShorterReadings() {
        val memory = MorphologyLearningMemory(alphabet, capacity = 2)
        memory.record(listOf(word("ア".repeat(20), "長")))
        memory.record(listOf(word("イ", "短")))
        memory.record(listOf(word("ウウ", "中")))
        assertEquals(setOf("短", "中"), memory.all().map { it.surface }.toSet())
        assertEquals(listOf("短"), memory.matching("ネコイヌ").map { it.surface })
        assertTrue(memory.matching("エエ").isEmpty())
    }

    @Test fun binaryRoundTripPreservesMorphologyAndCorruptionCannotReplaceLiveData() {
        val memory = MorphologyLearningMemory(alphabet, day = { 100 })
        memory.record(listOf(word("ネコ", "猫", 1288, 1289, 12), word("イヌ", "犬")))
        val encoded = ByteArrayOutputStream().also { memory.writeTo(it) }.toByteArray()
        val restored = MorphologyLearningMemory(alphabet)
        restored.readFrom(ByteArrayInputStream(encoded))
        assertEquals(memory.all(), restored.all())
        for (bad in listOf(encoded.copyOf(encoded.size - 1), encoded.clone().also { it[16] = (it[16].toInt() xor 1).toByte() })) {
            try { restored.readFrom(ByteArrayInputStream(bad)); fail("Corrupt history was accepted") }
            catch (_: IllegalArgumentException) { }
            assertEquals(memory.all(), restored.all())
        }
    }
}
