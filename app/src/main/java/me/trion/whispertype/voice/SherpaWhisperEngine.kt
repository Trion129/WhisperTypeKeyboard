package me.trion.whispertype.voice

import com.k2fsa.sherpa.onnx.OfflineModelConfig
import com.k2fsa.sherpa.onnx.OfflineRecognizer
import com.k2fsa.sherpa.onnx.OfflineRecognizerConfig
import com.k2fsa.sherpa.onnx.OfflineWhisperModelConfig

/**
 * Thin wrapper around the sherpa-onnx offline Whisper pipeline.
 * System.loadLibrary("sherpa-onnx-jni") happens in the OfflineRecognizer
 * companion, so no explicit load is needed here.
 */
class SherpaWhisperEngine(
    encoderPath: String,
    decoderPath: String,
    tokensPath: String,
    numThreads: Int = 2,
) {
    private val recognizer: OfflineRecognizer

    init {
        val config = OfflineRecognizerConfig(
            modelConfig = OfflineModelConfig(
                whisper = OfflineWhisperModelConfig(
                    encoder = encoderPath,
                    decoder = decoderPath,
                    language = "en",
                    task = "transcribe",
                ),
                tokens = tokensPath,
                numThreads = numThreads,
                provider = "cpu",
                modelType = "whisper",
            ),
            decodingMethod = "greedy_search",
        )
        recognizer = OfflineRecognizer(assetManager = null, config = config)
    }

    fun transcribe(samples: FloatArray, sampleRate: Int = 16000): String {
        val stream = recognizer.createStream()
        try {
            stream.acceptWaveform(samples, sampleRate)
            recognizer.decode(stream)
            return correctText(recognizer.getResult(stream).text)
        } finally {
            stream.release()
        }
    }

    fun release() {
        recognizer.release()
    }

    private fun correctText(text: String): String {
        var t = text.trim()
        if (t.length >= 2 && t[0].isLowerCase()) {
            t = t.replaceFirstChar { it.uppercaseChar() }
        }
        return t
    }
}
