package com.zeroconvert.ime

import com.zeroconvert.ime.model.DictionaryDownloadManager
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class DictionaryConnectionCostTest {
    private fun writeRow(file: File, defaultValue: Float, entries: List<Pair<Int, Float>>) {
        val buffer = ByteBuffer.allocate((1 + entries.size) * 8).order(ByteOrder.LITTLE_ENDIAN)
        buffer.putInt(-1).putFloat(defaultValue)
        for ((key, value) in entries) buffer.putInt(key).putFloat(value)
        file.writeBytes(buffer.array())
    }

    @Test fun packsAzureCostRowsIntoTheLegacyBinaryLayout() {
        val root = createTempDir("cb-pack")
        try {
            val cb = File(root, "cb").apply { mkdirs() }
            // 0 行目は疎、2 行目だけ値あり。1 行目はファイル無し（既定値の行になる）。
            writeRow(File(cb, "0.binary"), -20.0f, listOf(1 to -1.5f, 3 to 2.5f))
            writeRow(File(cb, "2.binary"), -22.0f, listOf(0 to -7.5f))

            DictionaryDownloadManager.packConnectionCosts(root)

            val bytes = File(root, "cb_packed.binary").readBytes()
            val buffer = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
            assertEquals("CBSP", String(bytes, 0, 4, Charsets.US_ASCII))
            assertEquals(3, buffer.getShort(4).toInt())
            var offset = 6
            fun row(): Pair<Float, List<Pair<Int, Float>>> {
                val default = buffer.getFloat(offset)
                val count = buffer.getShort(offset + 4).toInt()
                offset += 6
                val entries = ArrayList<Pair<Int, Float>>(count)
                repeat(count) {
                    entries.add(buffer.getShort(offset).toInt() to buffer.getFloat(offset + 2))
                    offset += 6
                }
                return default to entries
            }
            assertEquals(-20.0f to listOf(1 to -1.5f, 3 to 2.5f), row())
            assertEquals(-25.0f to emptyList<Pair<Int, Float>>(), row())
            assertEquals(-22.0f to listOf(0 to -7.5f), row())
            assertEquals(bytes.size, offset)
        } finally {
            root.deleteRecursively()
        }
    }

    @Test fun leavesDictionariesWithoutConnectionCostsUntouched() {
        val root = createTempDir("cb-none")
        try {
            assertEquals(false, File(root, "cb").exists())
            DictionaryDownloadManager.packConnectionCosts(root)
            assertTrue(!File(root, "cb_packed.binary").exists())
        } finally {
            root.deleteRecursively()
        }
    }
}
