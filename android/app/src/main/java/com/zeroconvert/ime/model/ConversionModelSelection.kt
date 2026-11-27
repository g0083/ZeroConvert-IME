package com.zeroconvert.ime.model

/** Nano / Standard v4.2b の量子化済みダウンロード対象。ファイル名と
 *  移行用の既存選択肢だけを保持する（内容検証・取得は ModelManager 側）。 */
object ConversionModelSelection {
    const val IQ4 = "zerocon-nano-v4.2b-iq4nl-emb8.gguf"
    const val Q8 = "zerocon-nano-v4.2b-q8_0.gguf"
    const val STANDARD_IQ4 = "zerocon-standard-v4.2b-iq4nl-emb8.gguf"
    const val STANDARD_Q8 = "zerocon-standard-v4.2b-q8_0.gguf"
    val retiredFiles = setOf(
        "zenz.gguf", "zenz-v3.2-xsmall-q4km.gguf", "zenz-v3.2-small-q4km.gguf",
        "zerocon-model-nano.gguf", "zerocon-model-standard.gguf",
        "zerocon-nano-v4-iq4nl-emb8.gguf", "zerocon-nano-v4-q8_0.gguf",
        "zerocon-nano-v4.1-iq4nl-emb8.gguf", "zerocon-nano-v4.1-q8_0.gguf",
        "zerocon-standard-v4.1-iq4nl-emb8.gguf", "zerocon-standard-v4.1-q8_0.gguf",
        "zerocon-nano-v4.2-iq4nl-emb8.gguf", "zerocon-nano-v4.2-q8_0.gguf",
        "zerocon-standard-v4.2-iq4nl-emb8.gguf", "zerocon-standard-v4.2-q8_0.gguf",
        "nano-v4-cap-nam-r3-iq4nl-emb8.gguf", "nano-v4-cap-nam-r3-q8_0.gguf",
        "standard-v4-cap-nam-r3-iq4nl-emb8.gguf", "standard-v4-cap-nam-r3-q8_0.gguf",
        "nano-v4-cap-nam-r4-iq4nl-emb8.gguf", "nano-v4-cap-nam-r4-q8_0.gguf",
        "standard-v4-cap-nam-r4-iq4nl-emb8.gguf", "standard-v4-cap-nam-r4-q8_0.gguf",
        "nano-v4-cap-nam-r6-iq4nl-emb8.gguf", "nano-v4-cap-nam-r6-q8_0.gguf",
        "standard-v4-cap-nam-r6-iq4nl-emb8.gguf", "standard-v4-cap-nam-r6-q8_0.gguf"
    )

    fun replacement(fileName: String): String? = when (fileName) {
        "zenz-v3.2-small-q4km.gguf", "zerocon-model-standard.gguf", "zerocon-nano-v4-q8_0.gguf",
        "zerocon-nano-v4.1-q8_0.gguf", "zerocon-nano-v4.2-q8_0.gguf",
        "nano-v4-cap-nam-r3-q8_0.gguf", "nano-v4-cap-nam-r4-q8_0.gguf",
        "nano-v4-cap-nam-r6-q8_0.gguf" -> Q8
        "zenz.gguf", "zenz-v3.2-xsmall-q4km.gguf", "zerocon-model-nano.gguf", "zerocon-nano-v4-iq4nl-emb8.gguf",
        "zerocon-nano-v4.1-iq4nl-emb8.gguf", "zerocon-nano-v4.2-iq4nl-emb8.gguf",
        "nano-v4-cap-nam-r3-iq4nl-emb8.gguf", "nano-v4-cap-nam-r4-iq4nl-emb8.gguf",
        "nano-v4-cap-nam-r6-iq4nl-emb8.gguf" -> IQ4
        "standard-v4-cap-nam-r3-iq4nl-emb8.gguf", "zerocon-standard-v4.1-iq4nl-emb8.gguf",
        "zerocon-standard-v4.2-iq4nl-emb8.gguf",
        "standard-v4-cap-nam-r4-iq4nl-emb8.gguf", "standard-v4-cap-nam-r6-iq4nl-emb8.gguf" -> STANDARD_IQ4
        "standard-v4-cap-nam-r3-q8_0.gguf", "zerocon-standard-v4.1-q8_0.gguf",
        "zerocon-standard-v4.2-q8_0.gguf",
        "standard-v4-cap-nam-r4-q8_0.gguf", "standard-v4-cap-nam-r6-q8_0.gguf" -> STANDARD_Q8
        IQ4, Q8, STANDARD_IQ4, STANDARD_Q8 -> fileName
        else -> null
    }
}
