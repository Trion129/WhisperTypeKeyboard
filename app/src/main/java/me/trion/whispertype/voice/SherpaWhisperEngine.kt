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

    /** What one [transcribe] call did, in decoder terms. Contains no text. */
    data class Trace(
        val sampleCount: Int,
        val sampleRate: Int,
        val firstPassTokens: Int,
        val tokenCap: Int,
        val hitTokenCap: Boolean,
        val firstDecodeMs: Long,
        val retryDecodeMs: Long,
    )

    /**
     * Optional trace sink for the temporary local-only diagnostics path
     * (AsrDiagnostics) and the pipeline replay test. Null when nobody listens.
     */
    var onTrace: ((Trace) -> Unit)? = null

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
     * provably hit that cap, re-run the same audio padded to sherpa's maximum
     * input (~29.5 s), where the cap is large enough for those scripts. The
     * retry wins only when it produced more tokens: an untruncated decode is
     * always longer than a capped one, while a shorter retry means padding
     * made the model stop early. English and other low-token scripts never
     * hit the cap, so they keep the faster single-pass path.
     */
    fun transcribe(samples: FloatArray, sampleRate: Int = 16000): String {
        val firstStart = System.nanoTime()
        val first = decode(samples, sampleRate)
        val firstDecodeMs = (System.nanoTime() - firstStart) / 1_000_000L
        val retry = shouldRetryInFullWindow(first, samples.size, sampleRate)
        var retryDecodeMs = 0L
        val text = if (retry) {
            val retryStart = System.nanoTime()
            val retried = decode(padToWhisperWindow(samples, sampleRate), sampleRate)
            retryDecodeMs = (System.nanoTime() - retryStart) / 1_000_000L
            preferLonger(first, retried).text
        } else {
            first.text
        }
        onTrace?.invoke(
            Trace(
                sampleCount = samples.size,
                sampleRate = sampleRate,
                firstPassTokens = first.tokens.size,
                tokenCap = decoderTokenCap(samples.size, sampleRate),
                hitTokenCap = retry,
                firstDecodeMs = firstDecodeMs,
                retryDecodeMs = retryDecodeMs,
            )
        )
        return correctText(text)
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
        /**
         * sherpa-onnx clamps input to 2950 feature frames (29.5 s of 10 ms
         * hops) and logs "Only waves less than 30 seconds are supported" once
         * the input reaches that many frames. See
         * offline-recognizer-whisper-impl.h (`max_num_frames - 50`).
         */
        private const val MAX_FRAMES = 2950

        /**
         * Longest span sherpa decodes without truncating or warning, in
         * samples: one frame under [MAX_FRAMES] (29.49 s).
         */
        internal fun whisperWindowSamples(sampleRate: Int): Int =
            (MAX_FRAMES - 1) * sampleRate / 100

        /**
         * sherpa-onnx decodes at most `num_frames / 100.0 * 6` tokens
         * (offline-whisper-greedy-search-decoder.cc: "assume at most 6 tokens
         * per second"). The Whisper fbank yields round-half-up(samples / hop)
         * 10 ms frames (kaldi-native-fbank WhisperFeatureOptions), clamped to
         * [MAX_FRAMES]. Using raw seconds instead overshoots by one token for
         * lengths just past a frame boundary, and the retry never fires there.
         */
        internal fun decoderTokenCap(sampleCount: Int, sampleRate: Int): Int {
            val frames = (sampleCount.toLong() * 100 + sampleRate / 2) / sampleRate
            return (minOf(frames, MAX_FRAMES.toLong()) * 6 / 100).toInt()
        }

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
            if (sampleCount >= whisperWindowSamples(sampleRate)) return false
            return result.tokens.size >= decoderTokenCap(sampleCount, sampleRate)
        }

        /**
         * Picks the padded [retried] result only when it decoded more tokens
         * than the capped [first] pass; otherwise the longer first pass stays.
         */
        internal fun preferLonger(
            first: OfflineRecognizerResult,
            retried: OfflineRecognizerResult,
        ): OfflineRecognizerResult =
            if (retried.tokens.size > first.tokens.size) retried else first

        /** Extends [samples] with trailing silence up to [whisperWindowSamples]. */
        internal fun padToWhisperWindow(samples: FloatArray, sampleRate: Int): FloatArray {
            val window = whisperWindowSamples(sampleRate)
            return if (samples.size >= window) samples else samples.copyOf(window)
        }
    }
}
