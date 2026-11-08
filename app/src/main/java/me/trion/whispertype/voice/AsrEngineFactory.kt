package me.trion.whispertype.voice

object AsrEngineFactory {

    fun create(model: ResolvedModel, requestedLanguage: String, numThreads: Int = 2): AsrEngine {
        val language = model.spec.languagePolicy.effectiveLanguage(requestedLanguage)
        return when (val layout = model.spec.layout) {
            is ModelLayout.Whisper -> SherpaWhisperEngine(
                encoderPath = model.file(layout.encoder).absolutePath,
                decoderPath = model.file(layout.decoder).absolutePath,
                tokensPath = model.file(layout.tokens).absolutePath,
                numThreads = numThreads,
                language = language,
            )
            is ModelLayout.NemoCtc -> SherpaNemoCtcEngine(
                modelPath = model.file(layout.model).absolutePath,
                tokensPath = model.file(layout.tokens).absolutePath,
                numThreads = numThreads,
            )
            is ModelLayout.Canary -> SherpaCanaryEngine(
                encoderPath = model.file(layout.encoder).absolutePath,
                decoderPath = model.file(layout.decoder).absolutePath,
                tokensPath = model.file(layout.tokens).absolutePath,
                numThreads = numThreads,
                language = language,
            )
        }
    }
}
