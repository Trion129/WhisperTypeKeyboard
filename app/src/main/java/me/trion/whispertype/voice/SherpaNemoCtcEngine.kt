package me.trion.whispertype.voice

import com.k2fsa.sherpa.onnx.OfflineModelConfig
import com.k2fsa.sherpa.onnx.OfflineNemoEncDecCtcModelConfig
import com.k2fsa.sherpa.onnx.OfflineRecognizer
import com.k2fsa.sherpa.onnx.OfflineRecognizerConfig

/** CPU greedy decoder for sherpa-onnx NeMo CTC exports. */
class SherpaNemoCtcEngine(
    modelPath: String,
    tokensPath: String,
    numThreads: Int = 2,
) : AsrEngine {
    private val recognizer = OfflineRecognizer(
        assetManager = null,
        config = OfflineRecognizerConfig(
            modelConfig = OfflineModelConfig(
                nemo = OfflineNemoEncDecCtcModelConfig(model = modelPath),
                tokens = tokensPath,
                numThreads = numThreads,
                provider = "cpu",
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
