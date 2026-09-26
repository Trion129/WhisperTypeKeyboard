package me.trion.whispertype.voice

import com.k2fsa.sherpa.onnx.OfflineModelConfig
import com.k2fsa.sherpa.onnx.OfflineRecognizer
import com.k2fsa.sherpa.onnx.OfflineRecognizerConfig
import com.k2fsa.sherpa.onnx.OfflineRecognizerResult
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
    language: String = ModelCatalog.ENGLISH_LANGUAGE,
) {
    private val recognizer: OfflineRecognizer

    init {
        val config = OfflineRecognizerConfig(
            modelConfig = OfflineModelConfig(
                whisper = OfflineWhisperModelConfig(
                    encoder = encoderPath,
                    decoder = decoderPath,
                    language = language,
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

    /**
     * Transcribes [samples].
     *
     * sherpa-onnx stops the Whisper decoder once it has produced six tokens
     * per second of audio, which cuts off scripts that need more than that
     * (Devanagari, Tamil, Chinese, ...) mid-sentence. When the decode
     * provably hit that cap, re-run the same audio padded to Whisper's full
     * 30 s window, where the cap is large enough for those scripts. English
     * and other low-token scripts never hit the cap, so they keep the faster
     * single-pass path.
     */
    fun transcribe(samples: FloatArray, sampleRate: Int = 16000): String {
        val first = decode(samples, sampleRate)
        if (!shouldRetryInFullWindow(first, samples.size, sampleRate)) {
            return correctText(first.text)
        }
        val retried = decode(padToWhisperWindow(samples, sampleRate), sampleRate)
        return correctText(if (retried.text.isBlank()) first.text else retried.text)
    }

    private fun decode(samples: FloatArray, sampleRate: Int): OfflineRecognizerResult {
        val stream = recognizer.createStream()
        try {
            stream.acceptWaveform(samples, sampleRate)
            recognizer.decode(stream)
            return recognizer.getResult(stream)
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

    companion object {
        /** Whisper's native window: the longest span sherpa decodes at once. */
        private const val WINDOW_SECONDS = 30

        /**
         * sherpa-onnx decodes at most `frames / 100 * 6` tokens
         * (offline-whisper-greedy-search-decoder.cc: "assume at most 6 tokens
         * per second"). With 10 ms feature hops `frames / 100` is just the
         * audio duration in seconds.
         */
        internal fun decoderTokenCap(sampleCount: Int, sampleRate: Int): Int =
            (sampleCount.toDouble() / sampleRate * 6).toInt()

        /**
         * True when the transcript stopped because the decoder ran out of
         * token budget rather than emitting the end-of-transcript token, and
         * padding the audio can still raise that budget.
         */
        internal fun shouldRetryInFullWindow(
            result: OfflineRecognizerResult,
            sampleCount: Int,
            sampleRate: Int,
        ): Boolean {
            if (result.tokens.isEmpty()) return false
            if (sampleCount >= WINDOW_SECONDS * sampleRate) return false
            return result.tokens.size >= decoderTokenCap(sampleCount, sampleRate)
        }

        /** Extends [samples] with trailing silence up to Whisper's window. */
        internal fun padToWhisperWindow(samples: FloatArray, sampleRate: Int): FloatArray {
            val window = WINDOW_SECONDS * sampleRate
            return if (samples.size >= window) samples else samples.copyOf(window)
        }
    }
}
