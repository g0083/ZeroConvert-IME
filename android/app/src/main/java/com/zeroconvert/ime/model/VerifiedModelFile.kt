package com.zeroconvert.ime.model

import java.io.File
import java.io.InputStream
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.nio.file.AtomicMoveNotSupportedException
import java.security.MessageDigest

internal object VerifiedModelFile {
    fun sha256(file: File): String = file.inputStream().use { input ->
        val digest = MessageDigest.getInstance("SHA-256")
        val buffer = ByteArray(65536)
        while (true) {
            val size = input.read(buffer)
            if (size < 0) break
            digest.update(buffer, 0, size)
        }
        digest.digest().joinToString("") { "%02x".format(it) }
    }

    /** Verify existing bytes as well as freshly extracted bytes before replacing a model. */
    @Synchronized
    fun ensure(destination: File, size: Long, checksum: String, source: () -> InputStream): File {
        require(checksum.matches(Regex("[0-9a-f]{64}")) && size > 0)
        if (destination.isFile && destination.length() == size && sha256(destination) == checksum) return destination
        val parent = destination.absoluteFile.parentFile!!
        check(parent.isDirectory || parent.mkdirs())
        val temporary = File.createTempFile("model-", ".tmp", parent)
        try {
            source().use { input -> temporary.outputStream().use { input.copyTo(it) } }
            check(temporary.length() == size && sha256(temporary) == checksum) { "Model checksum or size mismatch" }
            try {
                Files.move(temporary.toPath(), destination.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
            } catch (_: AtomicMoveNotSupportedException) {
                Files.move(temporary.toPath(), destination.toPath(), StandardCopyOption.REPLACE_EXISTING)
            }
            return destination
        } finally { temporary.delete() }
    }
}
