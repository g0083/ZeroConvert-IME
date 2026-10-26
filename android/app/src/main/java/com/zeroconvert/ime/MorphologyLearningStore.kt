package com.zeroconvert.ime

import android.util.AtomicFile
import android.util.Log
import java.io.File

/** Owned by the converter's worker-side lock. Corrupt history is preserved until explicit clear. */
internal class MorphologyLearningStore(file: File, alphabet: Set<Int>) {
    val memory = MorphologyLearningMemory(alphabet)
    private val atomic = AtomicFile(file)
    var available = true
        private set

    init {
        if (file.exists() || File(file.path + ".bak").exists()) {
            try { atomic.openRead().use { memory.readFrom(it) } }
            catch (failure: Exception) {
                available = false
                Log.e("MorphologyLearning", "History could not be decoded; preserving the file", failure)
            }
        }
    }

    fun save(): Boolean {
        if (!available) return false
        var stream: java.io.FileOutputStream? = null
        return try {
            stream = atomic.startWrite()
            memory.writeTo(stream)
            atomic.finishWrite(stream)
            true
        } catch (failure: Exception) {
            stream?.let { atomic.failWrite(it) }
            Log.e("MorphologyLearning", "Could not save history; keeping memory and prior file", failure)
            false
        }
    }

    fun clear() {
        atomic.delete()
        memory.clear()
        available = true
    }
}
