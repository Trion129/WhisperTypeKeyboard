package me.trion.whispertype.voice

import com.k2fsa.sherpa.onnx.OfflineCanaryModelConfig
import com.k2fsa.sherpa.onnx.OfflineModelConfig
import com.k2fsa.sherpa.onnx.OfflineRecognizer
import com.k2fsa.sherpa.onnx.OfflineRecognizerConfig

/**
 * CPU greedy decoder for sherpa-onnx NeMo Canary exports.
 *
 * Canary is an encoder-decoder model that also translates: srcLang is the
 * spoken language and tgtLang the written one. Transcription needs both set
 * to the same [language]; sherpa defaults srcLang to "en", which turns
 * Spanish speech into a translation of misheard English. Punctuation and
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
                canary = canaryConfig(encoderPath, decoderPath, language),
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
            stream.acceptWaveform(padToMinimum(samples, sampleRate), sampleRate)
            recognizer.decode(stream)
            return recognizer.getResult(stream).text.trim()
        } finally {
            stream.release()
        }
    }

    override fun release() {
        recognizer.release()
    }

    companion object {
        internal fun canaryConfig(encoderPath: String, decoderPath: String, language: String) =
            OfflineCanaryModelConfig(
                encoder = encoderPath,
                decoder = decoderPath,
                srcLang = language,
                tgtLang = language,
                usePnc = true,
            )

        /**
         * NVIDIA evaluates Canary with clips under one second zero-padded
         * symmetrically to one second (model card); do the same.
         */
        internal fun padToMinimum(samples: FloatArray, sampleRate: Int): FloatArray {
            if (samples.size >= sampleRate) return samples
            val padded = FloatArray(sampleRate)
            samples.copyInto(padded, (sampleRate - samples.size) / 2)
            return padded
        }
    }
}
