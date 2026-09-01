package me.trion.whispertype.voice

data class ModelSpec(
    val id: String,
    val title: String,
    val approxSizeMb: Int,
    val isMultilingual: Boolean = false,
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

data class LanguageOption(
    val code: String,
    val title: String,
)

object ModelCatalog {
    const val DEFAULT_ID = "base.en"
    const val IMPORT_ID = "import"
    const val LEGACY_FOLDER = "whisper_small_int8"
    const val AUTO_LANGUAGE = ""
    const val ENGLISH_LANGUAGE = "en"
    const val FRENCH_LANGUAGE = "fr"

    val languageOptions: List<LanguageOption> = listOf(
        LanguageOption(AUTO_LANGUAGE, "Auto-detect"),
        LanguageOption(ENGLISH_LANGUAGE, "English"),
        LanguageOption(FRENCH_LANGUAGE, "French"),
    )

    val entries: List<ModelSpec> = listOf(
        ModelSpec("tiny.en", "Whisper Tiny EN", 104),
        ModelSpec("base.en", "Whisper Base EN", 161),
        ModelSpec("small.en", "Whisper Small EN", 375),
        ModelSpec("tiny", "Whisper Tiny Multilingual", 104, isMultilingual = true),
        ModelSpec("base", "Whisper Base Multilingual", 161, isMultilingual = true),
        ModelSpec("small", "Whisper Small Multilingual", 375, isMultilingual = true),
    )

    fun byId(id: String): ModelSpec? = entries.find { it.id == id }

    fun normalizeLanguage(language: String): String =
        languageOptions.firstOrNull { it.code == language }?.code ?: AUTO_LANGUAGE

    fun effectiveLanguage(modelId: String, requestedLanguage: String): String {
        val normalized = normalizeLanguage(requestedLanguage)
        return if (byId(modelId)?.isMultilingual == true) {
            normalized
        } else {
            ENGLISH_LANGUAGE
        }
    }
}
