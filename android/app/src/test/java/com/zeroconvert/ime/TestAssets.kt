package com.zeroconvert.ime

import java.io.File

/**
 * Locates the shared assets that live in `app/`.
 *
 * `app/` is not a Gradle module, so a test cannot assume the module directory is
 * the asset root: the working directory is `android/rebuilt`. Walk up from the
 * working directory instead of hard-coding a relative path.
 */
object TestAssets {
    private val root: File by lazy {
        generateSequence(File("").absoluteFile) { it.parentFile }
            .map { File(it, "app/src/main/assets") }
            .firstOrNull { it.isDirectory }
            ?: error("Could not locate app/src/main/assets from ${File("").absolutePath}")
    }

    fun file(relative: String): File = File(root, relative)
}
