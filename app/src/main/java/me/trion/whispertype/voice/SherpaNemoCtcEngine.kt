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
            stream.acceptWaveform(withLeadIn(samples, sampleRate), sampleRate)
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
        /**
         * NeMo CTC exports misread the first frames of a part: on 50 real
         * Hindi clips a 0.2 s silence lead drops character error from 4.3%
         * to 2.2% and the worst clip from 62% to 22%. English Parakeet is
         * unchanged (9.2% -> 9.7%, worst clip the same).
         */
        internal const val LEAD_IN_MS = 200L

        internal fun withLeadIn(samples: FloatArray, sampleRate: Int): FloatArray {
            val lead = (LEAD_IN_MS * sampleRate / 1000).toInt()
            if (samples.isEmpty()) return samples
            return FloatArray(lead + samples.size).also { samples.copyInto(it, lead) }
        }
    }
}
