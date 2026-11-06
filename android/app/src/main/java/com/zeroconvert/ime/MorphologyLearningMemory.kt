package com.zeroconvert.ime

import java.io.*
import java.util.zip.CRC32
import kotlin.math.pow

/** Swift LearningManager's word, clause-bigram and whole-selection memory. No Android APIs. */
internal class MorphologyLearningMemory(
    private val alphabet: Set<Int>,
    private val capacity: Int = 65536,
    private val day: () -> Int = { (System.currentTimeMillis() / 86400000L - 19000).toInt().coerceIn(0, 65535) }
) {
    data class Entry(val reading: String, val surface: String, val lcid: Int, val rcid: Int, val mid: Int,
                     val count: Int, val lastUsedDay: Int, val lastUpdatedDay: Int) {
        val score: Float get() = (-1.0 - 4.0 / reading.codePointCount(0, reading.length) -
            3.0 * (1.0 - count / 255.0).pow(3)).toFloat()
        fun segment() = DictionarySegment(reading, surface, lcid, rcid, mid, score)
    }
    private data class Key(val surface: String, val lcid: Int, val rcid: Int)
    private val byReading = linkedMapOf<String, LinkedHashMap<Key, Entry>>()
    // Prefix index over learned readings, rebuilt lazily after mutations (query is a prefix of the key).
    private val byReadingPrefix = java.util.TreeMap<String, MutableList<Entry>>()
    private var prefixIndexDirty = true
    val size: Int get() = byReading.values.sumOf { it.size }
    fun all(): List<Entry> = byReading.values.flatMap { it.values }

    private fun validText(value: String, maxCodePoints: Int): Boolean =
        value.codePointCount(0, value.length) in 1..maxCodePoints &&
            value.none { it.isISOControl() || it in '\uEE00'..'\uEE0F' } &&
            value.toByteArray(Charsets.UTF_8).toString(Charsets.UTF_8) == value

    private fun validShape(entry: Entry): Boolean = validText(entry.reading, 512) && validText(entry.surface, 4096) &&
        entry.lcid in 0..1319 && entry.rcid in 0..1319 &&
        entry.mid in 0..502 && entry.count in 1..255 && entry.lastUsedDay in 0..65535 && entry.lastUpdatedDay in 0..65535
    private fun valid(entry: Entry): Boolean = validShape(entry) && entry.reading.codePoints().allMatch { it in alphabet }

    private fun put(entry: Entry) {
        byReading.getOrPut(entry.reading) { linkedMapOf() }[Key(entry.surface, entry.lcid, entry.rcid)] = entry
        prefixIndexDirty = true
    }

    /** Bounded substring-index lookup; never scan all history on a keystroke. */
    fun matching(reading: String): List<Entry> {
        val points = reading.codePoints().toArray()
        if (points.size !in 1..512) return emptyList()
        val found = linkedSetOf<String>()
        val result = mutableListOf<Entry>()
        for (start in points.indices) for (length in 1..minOf(20, points.size - start)) {
            val key = String(points, start, length)
            if (found.add(key)) byReading[key]?.values?.let { result.addAll(it) }
        }
        return result
    }

    /** Learned readings that start with the query (continuation candidates), most frequent first. */
    fun prefixMatching(reading: String, limit: Int = 8): List<Entry> {
        if (reading.isEmpty() || reading.length > 512) return emptyList()
        if (prefixIndexDirty) {
            byReadingPrefix.clear()
            for (entries in byReading.values) for (entry in entries.values) {
                byReadingPrefix.getOrPut(entry.reading) { mutableListOf() }.add(entry)
            }
            prefixIndexDirty = false
        }
        val endExclusive = reading.dropLast(1) + (reading.last() + 1)
        return byReadingPrefix.subMap(reading, endExclusive).values
            .asSequence().flatten()
            .sortedWith(compareByDescending<Entry> { it.count }.thenByDescending { it.lastUsedDay })
            .take(limit)
            .toList()
    }

    fun age(today: Int = day()) {
        val old = all()
        byReading.clear()
        for (entry in old) {
            if (today < entry.lastUsedDay || today < entry.lastUpdatedDay) {
                put(entry.copy(count = 1, lastUsedDay = today, lastUpdatedDay = today))
                continue
            }
            if (today - entry.lastUsedDay >= 128) continue
            var count = entry.count
            var updated = entry.lastUpdatedDay
            while (today - updated > 32) { count = count shr 1; updated += 32 }
            if (count > 0) put(entry.copy(count = count, lastUpdatedDay = updated))
        }
    }

    private fun memorize(segment: DictionarySegment, today: Int) {
        val new = Entry(segment.reading, segment.surface, segment.lcid, segment.rcid, segment.mid, 1, today, today)
        if (!valid(new)) return
        val previous = byReading[new.reading]?.get(Key(new.surface, new.lcid, new.rcid))
        if (previous == null && (byReading[new.reading]?.size ?: 0) >= 255) return
        put(if (previous == null) new else previous.copy(count = minOf(255, previous.count + 1),
            lastUsedDay = today, lastUpdatedDay = today))
    }

    private fun append(first: DictionarySegment, next: DictionarySegment) = first.copy(
        reading = first.reading + next.reading, surface = first.surface + next.surface, rcid = next.rcid,
        mid = if (includesMeaning(next)) next.mid else first.mid, cost = first.cost + next.cost)

    // Swift's bigram constructor deliberately uses first.rcid, including the final pair.
    private fun bigram(first: DictionarySegment, second: DictionarySegment) = first.copy(
        reading = first.reading + second.reading, surface = first.surface + second.surface,
        mid = second.mid, cost = first.cost + second.cost)

    fun record(segments: List<DictionarySegment>, previous: DictionarySegment? = null) {
        if (segments.isEmpty() || segments.size > 512 || segments.any {
            it.correctionKind != 0 || !it.cost.isFinite() || !validShape(Entry(it.reading, it.surface, it.lcid, it.rcid, it.mid, 1, 0, 0))
        }) return
        val today = day()
        age(today)
        for (segment in segments) if (needsWordMemory(segment.lcid)) memorize(segment, today)
        val data = listOfNotNull(previous) + segments
        if (data.size != 1) {
            var first: DictionarySegment? = null
            var second: DictionarySegment? = null
            for (datum in data) {
                val f = first
                val s = second
                if (f == null) first = datum
                else if (s == null) {
                    if (isClause(f.rcid, datum.lcid)) second = datum else first = append(f, datum)
                } else if (isClause(f.rcid, datum.lcid)) {
                    memorize(bigram(f, s), today)
                    first = s
                    second = datum
                } else second = append(s, datum)
            }
            if (first != null && second != null) memorize(bigram(first, second), today)
            memorize(DictionarySegment(data.joinToString("") { it.reading }, data.joinToString("") { it.surface },
                data.first().lcid, data.last().rcid, data.last().mid, data.sumOf { it.cost.toDouble() }.toFloat()), today)
        }
        if (size > capacity) {
            // Swift's LOUDS traversal retains shorter readings before longer ones.
            val retained = all().sortedBy { it.reading.codePointCount(0, it.reading.length) }.take(capacity)
            byReading.clear()
            retained.forEach(::put)
        }
    }

    fun clear() {
        byReading.clear()
        prefixIndexDirty = true
    }

    fun writeTo(output: OutputStream) {
        val bytes = ByteArrayOutputStream()
        DataOutputStream(bytes).use { data ->
            data.writeInt(0x5A434C4D) // ZCLM
            data.writeInt(1)
            data.writeInt(size)
            for (entry in all()) {
                for (text in listOf(entry.reading, entry.surface)) {
                    val encoded = text.toByteArray(Charsets.UTF_8)
                    data.writeInt(encoded.size)
                    data.write(encoded)
                }
                data.writeShort(entry.lcid); data.writeShort(entry.rcid); data.writeShort(entry.mid)
                data.writeByte(entry.count); data.writeShort(entry.lastUsedDay); data.writeShort(entry.lastUpdatedDay)
            }
        }
        require(bytes.size() <= MAX_BYTES - 8)
        val payload = bytes.toByteArray()
        val data = DataOutputStream(output)
        data.write(payload)
        data.writeLong(CRC32().apply { update(payload) }.value)
        data.flush() // AtomicFile owns the stream.
    }

    /** Decode completely before changing live memory, including CRC and duplicate validation. */
    fun readFrom(input: InputStream) {
        val bytes = input.readBytesBounded(MAX_BYTES)
        require(bytes.size >= 20)
        val checksum = DataInputStream(ByteArrayInputStream(bytes, bytes.size - 8, 8)).readLong()
        require(CRC32().apply { update(bytes, 0, bytes.size - 8) }.value == checksum)
        val incoming = mutableListOf<Entry>()
        val unique = hashSetOf<Pair<String, Key>>()
        DataInputStream(ByteArrayInputStream(bytes, 0, bytes.size - 8)).use { data ->
            require(data.readInt() == 0x5A434C4D && data.readInt() == 1)
            val count = data.readInt()
            require(count in 0..capacity)
            fun text(): String {
                val length = data.readInt()
                require(length in 1..16384)
                val encoded = ByteArray(length).also { data.readFully(it) }
                val value = encoded.toString(Charsets.UTF_8)
                require(value.toByteArray(Charsets.UTF_8).contentEquals(encoded))
                return value
            }
            repeat(count) {
                val entry = Entry(text(), text(), data.readUnsignedShort(), data.readUnsignedShort(), data.readUnsignedShort(),
                    data.readUnsignedByte(), data.readUnsignedShort(), data.readUnsignedShort())
                require(valid(entry) && unique.add(entry.reading to Key(entry.surface, entry.lcid, entry.rcid)))
                incoming.add(entry)
            }
            require(data.read() == -1)
        }
        byReading.clear()
        incoming.forEach(::put)
    }

    companion object {
        private const val MAX_BYTES = 64 * 1024 * 1024
        private fun InputStream.readBytesBounded(limit: Int): ByteArray {
            val output = ByteArrayOutputStream()
            val buffer = ByteArray(8192)
            while (true) {
                val count = read(buffer)
                if (count < 0) break
                require(output.size().toLong() + count <= limit)
                output.write(buffer, 0, count)
            }
            return output.toByteArray()
        }
        fun needsWordMemory(cid: Int) = cid !in 147..554 && cid !in 557..560 && cid !in 1297..1305 && cid !in 6..9 && cid != 0 && cid != 1316
        fun wordType(cid: Int): Int = when (cid) {
            0, 1316 -> 3
            1315, 6, 557, 558, 559, 560 -> 0
            in 561..867, in 1283..1296, in 1306..1309, in 11..52, in 555..556, in 1281..1282,
            1314, 3, 2, 4, 5, 1, 9 -> 1
            else -> 2
        }
        fun isClause(former: Int, latter: Int): Boolean = wordType(former) != 3 && wordType(latter) != 3 &&
            wordType(latter) in 0..1 && wordType(former) != 0
        private fun includesMeaning(segment: DictionarySegment) =
            segment.lcid in 895..1280 || segment.rcid in 895..1280 || segment.lcid in 1297..1305 || segment.rcid in 1297..1305 ||
                wordType(segment.lcid) == 1 || wordType(segment.rcid) == 1
    }
}
