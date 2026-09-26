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

    val entries: List<ModelSpec> = listOf(
        ModelSpec("tiny.en", "Whisper Tiny EN", 104),
        ModelSpec("base.en", "Whisper Base EN", 161),
        ModelSpec("small.en", "Whisper Small EN", 375),
        ModelSpec("tiny", "Whisper Tiny Multilingual", 104, isMultilingual = true),
        ModelSpec("base", "Whisper Base Multilingual", 161, isMultilingual = true),
        ModelSpec("small", "Whisper Small Multilingual", 375, isMultilingual = true),
    )

    /**
     * Languages the shipped multilingual Whisper models accept: the 99 codes of
     * the `all_language_codes` ONNX metadata of the exported models, sorted by
     * display name after auto-detect. `yue` (Cantonese) is large-v3-only and is
     * rejected by tiny/base/small, so it is deliberately absent; it stays
     * reachable through auto-detect on models that support it.
     */
    val languageOptions: List<LanguageOption> = listOf(
        LanguageOption(AUTO_LANGUAGE, "Auto-detect"),
        LanguageOption("af", "Afrikaans"),
        LanguageOption("sq", "Albanian"),
        LanguageOption("am", "Amharic"),
        LanguageOption("ar", "Arabic"),
        LanguageOption("hy", "Armenian"),
        LanguageOption("as", "Assamese"),
        LanguageOption("az", "Azerbaijani"),
        LanguageOption("ba", "Bashkir"),
        LanguageOption("eu", "Basque"),
        LanguageOption("be", "Belarusian"),
        LanguageOption("bn", "Bengali"),
        LanguageOption("bs", "Bosnian"),
        LanguageOption("br", "Breton"),
        LanguageOption("bg", "Bulgarian"),
        LanguageOption("ca", "Catalan"),
        LanguageOption("zh", "Chinese"),
        LanguageOption("hr", "Croatian"),
        LanguageOption("cs", "Czech"),
        LanguageOption("da", "Danish"),
        LanguageOption("nl", "Dutch"),
        LanguageOption("en", "English"),
        LanguageOption("et", "Estonian"),
        LanguageOption("fo", "Faroese"),
        LanguageOption("fi", "Finnish"),
        LanguageOption("fr", "French"),
        LanguageOption("gl", "Galician"),
        LanguageOption("ka", "Georgian"),
        LanguageOption("de", "German"),
        LanguageOption("el", "Greek"),
        LanguageOption("gu", "Gujarati"),
        LanguageOption("ht", "Haitian Creole"),
        LanguageOption("ha", "Hausa"),
        LanguageOption("haw", "Hawaiian"),
        LanguageOption("he", "Hebrew"),
        LanguageOption("hi", "Hindi"),
        LanguageOption("hu", "Hungarian"),
        LanguageOption("is", "Icelandic"),
        LanguageOption("id", "Indonesian"),
        LanguageOption("it", "Italian"),
        LanguageOption("ja", "Japanese"),
        LanguageOption("jw", "Javanese"),
        LanguageOption("kn", "Kannada"),
        LanguageOption("kk", "Kazakh"),
        LanguageOption("km", "Khmer"),
        LanguageOption("ko", "Korean"),
        LanguageOption("lo", "Lao"),
        LanguageOption("la", "Latin"),
        LanguageOption("lv", "Latvian"),
        LanguageOption("ln", "Lingala"),
        LanguageOption("lt", "Lithuanian"),
        LanguageOption("lb", "Luxembourgish"),
        LanguageOption("mk", "Macedonian"),
        LanguageOption("mg", "Malagasy"),
        LanguageOption("ms", "Malay"),
        LanguageOption("ml", "Malayalam"),
        LanguageOption("mt", "Maltese"),
        LanguageOption("mi", "Maori"),
        LanguageOption("mr", "Marathi"),
        LanguageOption("mn", "Mongolian"),
        LanguageOption("my", "Myanmar"),
        LanguageOption("ne", "Nepali"),
        LanguageOption("no", "Norwegian"),
        LanguageOption("nn", "Nynorsk"),
        LanguageOption("oc", "Occitan"),
        LanguageOption("ps", "Pashto"),
        LanguageOption("fa", "Persian"),
        LanguageOption("pl", "Polish"),
        LanguageOption("pt", "Portuguese"),
        LanguageOption("pa", "Punjabi"),
        LanguageOption("ro", "Romanian"),
        LanguageOption("ru", "Russian"),
        LanguageOption("sa", "Sanskrit"),
        LanguageOption("sr", "Serbian"),
        LanguageOption("sn", "Shona"),
        LanguageOption("sd", "Sindhi"),
        LanguageOption("si", "Sinhala"),
        LanguageOption("sk", "Slovak"),
        LanguageOption("sl", "Slovenian"),
        LanguageOption("so", "Somali"),
        LanguageOption("es", "Spanish"),
        LanguageOption("su", "Sundanese"),
        LanguageOption("sw", "Swahili"),
        LanguageOption("sv", "Swedish"),
        LanguageOption("tl", "Tagalog"),
        LanguageOption("tg", "Tajik"),
        LanguageOption("ta", "Tamil"),
        LanguageOption("tt", "Tatar"),
        LanguageOption("te", "Telugu"),
        LanguageOption("th", "Thai"),
        LanguageOption("bo", "Tibetan"),
        LanguageOption("tr", "Turkish"),
        LanguageOption("tk", "Turkmen"),
        LanguageOption("uk", "Ukrainian"),
        LanguageOption("ur", "Urdu"),
        LanguageOption("uz", "Uzbek"),
        LanguageOption("vi", "Vietnamese"),
        LanguageOption("cy", "Welsh"),
        LanguageOption("yi", "Yiddish"),
        LanguageOption("yo", "Yoruba"),
    )

    fun byId(id: String): ModelSpec? = entries.find { it.id == id }

    fun normalizeLanguage(language: String): String =
        languageOptions.firstOrNull { it.code == language }?.code ?: AUTO_LANGUAGE

    /**
     * The language handed to sherpa for [modelId]: the requested one when the
     * model transcribes multiple languages, English otherwise. Catalog models
     * answer from the catalog; [IMPORT_ID] depends on [installedMultilingual],
     * which callers read from the installed files (see ModelDownloader).
     */
    fun effectiveLanguage(
        modelId: String,
        requestedLanguage: String,
        installedMultilingual: Boolean = false,
    ): String {
        val multilingual = byId(modelId)?.isMultilingual == true ||
            (modelId == IMPORT_ID && installedMultilingual)
        return if (multilingual) normalizeLanguage(requestedLanguage) else ENGLISH_LANGUAGE
    }
}
