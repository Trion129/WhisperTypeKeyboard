package me.trion.whispertype.voice


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

    val entries: List<ModelSpec> by lazy {
        listOf(
        whisper("tiny.en", "Whisper Tiny EN", 104, multilingual = false),
        whisper("base.en", "Whisper Base EN", 161, multilingual = false),
        whisper("small.en", "Whisper Small EN", 375, multilingual = false),
        whisper("small", "Whisper Small Multilingual", 375, multilingual = true),
        ModelSpec(
            id = "oriserve-swift",
            title = "Oriserve Swift Hindi2Hinglish",
            approxSizeMb = 161,
            layout = ModelLayout.Whisper(
                encoder = "hi-hinglish-swift/encoder.int8.onnx",
                decoder = "hi-hinglish-swift/decoder.int8.onnx",
                tokens = "hi-hinglish-swift/tokens.txt",
            ),
            languagePolicy = LanguagePolicy.Fixed("hi", "Hindi / Hinglish (Romanized)"),
            artifacts = listOf(
                ModelArtifact("hi-hinglish-swift/encoder.int8.onnx", "https://huggingface.co/parismitaglobalsolutions/indicconformer-sherpa-onnx/resolve/9721eb71eea141fae0982cfcdb9dd2e3d4953c4a/hi-hinglish-swift/encoder.int8.onnx", 29122029L, "817f10919051ba8c784935a54da22683fceefd45a8c3547e68369204fc36c5ff"),
                ModelArtifact("hi-hinglish-swift/decoder.int8.onnx", "https://huggingface.co/parismitaglobalsolutions/indicconformer-sherpa-onnx/resolve/9721eb71eea141fae0982cfcdb9dd2e3d4953c4a/hi-hinglish-swift/decoder.int8.onnx", 130781044L, "19e88530a38a00af1744bc2c9340e72d4687f017e19b37b1b6c69c2b5128fc12"),
                ModelArtifact("hi-hinglish-swift/tokens.txt", "https://huggingface.co/parismitaglobalsolutions/indicconformer-sherpa-onnx/resolve/9721eb71eea141fae0982cfcdb9dd2e3d4953c4a/hi-hinglish-swift/tokens.txt", 816730L, "b34b360dbb493e781e479794586d661700670d65564001f23024971d1f2fa126"),
            ),
            description = "Hindi and Hinglish transcription in Romanized script; no Devanagari transliteration.",
            sourceUrl = "https://huggingface.co/Oriserve/Whisper-Hindi2Hinglish-Swift",
            license = "Apache-2.0",
            revision = "9721eb71eea141fae0982cfcdb9dd2e3d4953c4a",
        ),
        ModelSpec(
            id = "indicconformer-hi",
            title = "IndicConformer Hindi",
            approxSizeMb = 198,
            layout = ModelLayout.NemoCtc(
                model = "model.int8.onnx",
                tokens = "tokens.txt",
            ),
            // AI4Bharat IndicConformer is Hindi only; no language picker needed.
            languagePolicy = LanguagePolicy.Fixed("hi", "Hindi (Devanagari)"),
            artifacts = listOf(
                ModelArtifact(
                    "model.int8.onnx",
                    "https://huggingface.co/parismitaglobalsolutions/indicconformer-sherpa-onnx/resolve/9721eb71eea141fae0982cfcdb9dd2e3d4953c4a/hi/model.int8.onnx",
                    197595593L,
                    "915c71e04dd7e5378a4057fdebb252b3a587188e4e99db6d7ce0909ad5ad05fa",
                ),
                ModelArtifact(
                    "tokens.txt",
                    "https://huggingface.co/parismitaglobalsolutions/indicconformer-sherpa-onnx/resolve/9721eb71eea141fae0982cfcdb9dd2e3d4953c4a/tokens.txt",
                    67605L,
                    "ee60967630213f31951817ac8b402b92ec18cce80718a24a49b388e56672dfb2",
                ),
            ),
            description = "Hindi in Devanagari script, measured 3% character error on real FLEURS speech; plain words without punctuation or capitals.",
            sourceUrl = "https://huggingface.co/ai4bharat/indicconformer_stt_hi_hybrid_ctc_rnnt_large",
            license = "MIT",
            revision = "9721eb71eea141fae0982cfcdb9dd2e3d4953c4a",
        ),
        ModelSpec(
            id = "parakeet-ctc-110m",
            title = "Parakeet TDT CTC 110M English",
            approxSizeMb = 132,
            layout = ModelLayout.NemoCtc(
                model = "model.int8.onnx",
                tokens = "tokens.txt",
            ),
            languagePolicy = LanguagePolicy.Fixed("en", "English"),
            artifacts = listOf(
                ModelArtifact("model.int8.onnx", "https://huggingface.co/VocaHQ/sherpa-onnx-nemo-parakeet-tdt-ctc-110m-en-int8/resolve/43869f73831659dd8b0ccee617ee42474b7aa974/model.int8.onnx", 131652171L, "9177a9146cf32ee0cc8152276ef95116f312018d316be37ccf57f7efea81fc1a"),
                ModelArtifact("tokens.txt", "https://huggingface.co/VocaHQ/sherpa-onnx-nemo-parakeet-tdt-ctc-110m-en-int8/resolve/43869f73831659dd8b0ccee617ee42474b7aa974/tokens.txt", 9953L, "450e56bd2f036fe5b6aa821865838cc5aa9d8b0106134ce9a9ba0664abe6cd10"),
            ),
            description = "English NeMo CTC model with casing and punctuation.",
            sourceUrl = "https://huggingface.co/VocaHQ/sherpa-onnx-nemo-parakeet-tdt-ctc-110m-en-int8",
            license = "CC-BY-4.0",
            revision = "43869f73831659dd8b0ccee617ee42474b7aa974",
        ),
        ModelSpec(
            id = "canary-180m-flash",
            title = "Canary 180M Flash EN/ES/DE/FR",
            approxSizeMb = 207,
            layout = ModelLayout.Canary(
                encoder = "encoder.int8.onnx",
                decoder = "decoder.int8.onnx",
                tokens = "tokens.txt",
            ),
            // The forced output language must stay inside the trained set; never auto.
            languagePolicy = LanguagePolicy.Selectable(listOf("en", "es", "de", "fr"), defaultCode = "en"),
            artifacts = listOf(
                ModelArtifact("encoder.int8.onnx", "https://huggingface.co/csukuangfj/sherpa-onnx-nemo-canary-180m-flash-en-es-de-fr-int8/resolve/main/encoder.int8.onnx", 132678643L, "7a75b4e2a5857a6dcc0819503bbe3fad66943db4a3ccf21d3f27c633667d303f"),
                ModelArtifact("decoder.int8.onnx", "https://huggingface.co/csukuangfj/sherpa-onnx-nemo-canary-180m-flash-en-es-de-fr-int8/resolve/main/decoder.int8.onnx", 74437848L, "e41a2ab9c0c2fe81a1e8ade5a45fb02a74bc4db7d1f91b89a54a25e2cf79cba2"),
                ModelArtifact("tokens.txt", "https://huggingface.co/csukuangfj/sherpa-onnx-nemo-canary-180m-flash-en-es-de-fr-int8/resolve/main/tokens.txt", 53555L, "2dae6fc7815f9640645e0c765522b278ee0cef49b482d91f6913e334628d3e77"),
            ),
            description = "Multilingual English, Spanish, German, and French transcription with punctuation and casing; fast non-autoregressive decoder.",
            sourceUrl = "https://huggingface.co/csukuangfj/sherpa-onnx-nemo-canary-180m-flash-en-es-de-fr-int8",
            license = "CC-BY-4.0",
            revision = "9077164e0d3dd1d5353743e89ceaa1d3a770838c",
        ),
        )
    }

    private fun whisper(id: String, title: String, sizeMb: Int, multilingual: Boolean): ModelSpec {
        val encoder = "$id-encoder.int8.onnx"
        val decoder = "$id-decoder.int8.onnx"
        val tokens = "$id-tokens.txt"
        val base = "https://huggingface.co/csukuangfj/sherpa-onnx-whisper-$id/resolve/main"
        return ModelSpec(
            id = id,
            title = title,
            approxSizeMb = sizeMb,
            layout = ModelLayout.Whisper(encoder, decoder, tokens),
            languagePolicy = if (multilingual) {
                LanguagePolicy.Selectable(languageOptions.map { it.code })
            } else {
                LanguagePolicy.Fixed(ENGLISH_LANGUAGE, "English")
            },
            artifacts = listOf(
                artifact(encoder, "$base/$encoder"),
                artifact(decoder, "$base/$decoder"),
                artifact(tokens, "$base/$tokens"),
            ),
            description = if (multilingual) "Multilingual transcription in native scripts." else "English transcription.",
            sourceUrl = "https://huggingface.co/csukuangfj/sherpa-onnx-whisper-$id",
            license = "MIT",
        )
    }

    private fun artifact(path: String, url: String) = ModelArtifact(path, url)

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


}
