package me.trion.whispertype.voice

import com.k2fsa.sherpa.onnx.OfflineCanaryModelConfig
import com.k2fsa.sherpa.onnx.OfflineModelConfig
import com.k2fsa.sherpa.onnx.OfflineRecognizer
import com.k2fsa.sherpa.onnx.OfflineRecognizerConfig

/**
 * CPU greedy decoder for sherpa-onnx NeMo Canary exports.
 *
 * Canary is a non-autoregressive TDT model, so there is no Whisper-style token
 * cap and no padding. [language] is the forced output language; punctuation and
 * casing come from the model (usePnc), so the text is only trimmed here.
 */
class SherpaCanaryEngine(
    encoderPath: String,
    decoderPath: String,
    tokensPath: String,
    numThreads: Int = 2,
    language: String = ModelCatalog.ENGLISH_LANGUAGE,
) : AsrEngine {
    private val recognizer = OfflineRecognizer(
        assetManager = null,
        config = OfflineRecognizerConfig(
            modelConfig = OfflineModelConfig(
                canary = OfflineCanaryModelConfig(
                    encoder = encoderPath,
                    decoder = decoderPath,
                    tgtLang = language,
                    usePnc = true,
                ),
                tokens = tokensPath,
                numThreads = numThreads,
                provider = "cpu",
                modelType = "canary",
            ),
            decodingMethod = "greedy_search",
        ),
    )

    override fun transcribe(samples: FloatArray, sampleRate: Int): String {
        val stream = recognizer.createStream()
        try {
            stream.acceptWaveform(samples, sampleRate)
            recognizer.decode(stream)
            return recognizer.getResult(stream).text.trim()
        } finally {
            stream.release()
        }
    }

    override fun release() {
        recognizer.release()
    }
}
