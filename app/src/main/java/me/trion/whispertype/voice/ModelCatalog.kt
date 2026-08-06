package me.trion.whispertype.voice

data class ModelSpec(
    val id: String,
    val title: String,
    val approxSizeMb: Int,
) {
    val encoderFileName: String get() = "$id-encoder.int8.onnx"
    val decoderFileName: String get() = "$id-decoder.int8.onnx"
    val tokensFileName: String get() = "$id-tokens.txt"
    fun baseUrl(): String =
        "https://huggingface.co/csukuangfj/sherpa-onnx-whisper-$id/resolve/main"
    fun encoderUrl() = "${baseUrl()}/$encoderFileName"
    fun decoderUrl() = "${baseUrl()}/$decoderFileName"
    fun tokensUrl() = "${baseUrl()}/$tokensFileName"
}

object ModelCatalog {
    const val DEFAULT_ID = "base.en"
    const val IMPORT_ID = "import"
    const val LEGACY_FOLDER = "whisper_small_int8"

    val entries: List<ModelSpec> = listOf(
        ModelSpec("tiny.en", "Whisper Tiny EN", 104),
        ModelSpec("base.en", "Whisper Base EN", 161),
        ModelSpec("small.en", "Whisper Small EN", 375),
    )

    fun byId(id: String): ModelSpec? = entries.find { it.id == id }
}
