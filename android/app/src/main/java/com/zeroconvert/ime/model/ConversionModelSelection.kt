package com.zeroconvert.ime.model

/** Selection migration only: retired bundled files are retained on disk. */
object ConversionModelSelection {
    const val IQ4 = "zerocon-nano-v4-iq4nl-emb8.gguf"
    const val Q8 = "zerocon-nano-v4-q8_0.gguf"
    val retiredFiles = setOf(
        "zenz.gguf", "zenz-v3.2-xsmall-q4km.gguf", "zenz-v3.2-small-q4km.gguf",
        "zerocon-model-nano.gguf", "zerocon-model-standard.gguf"
    )

    fun replacement(fileName: String): String? = when (fileName) {
        "zenz-v3.2-small-q4km.gguf", "zerocon-model-standard.gguf" -> Q8
        "zenz.gguf", "zenz-v3.2-xsmall-q4km.gguf", "zerocon-model-nano.gguf" -> IQ4
        IQ4, Q8 -> fileName
        else -> null
    }
}
