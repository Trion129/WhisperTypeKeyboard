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
class SherpaWhisperEngine internal constructor(
    private val language: String,
    private val backend: DecodeBackend,
) : AsrEngine {

    constructor(
        encoderPath: String,
        decoderPath: String,
        tokensPath: String,
        numThreads: Int = 2,
        language: String = ModelCatalog.ENGLISH_LANGUAGE,
    ) : this(
        language,
        SherpaBackend(encoderPath, decoderPath, tokensPath, numThreads, language),
    )

    /** What one [transcribe] call did, in decoder terms. Contains no text. */
    data class Trace(
        val sampleCount: Int,
        val sampleRate: Int,
        val firstPassTokens: Int,
        val tokenCap: Int,
        val hitTokenCap: Boolean,
        val usedRetry: Boolean,
        val paddedUpFront: Boolean,
        /** Language the decoder reported for the chosen text, "" when unknown. */
        val detectedLanguage: String,
        val firstDecodeMs: Long,
        val retryDecodeMs: Long,
        /**
         * Tokens the runtime returned as empty strings. sherpa-onnx 1.13.x
         * dropped Whisper byte-fallback tokens this way, which silently
         * deleted every character outside the standalone-token set (most of
         * Devanagari, CJK, Arabic). Must stay 0; see the v2.0.1 runtime.
         */
        val emptyTokenCount: Int = 0,
        /** Extra padded decodes spent on halves after a decode filled the window cap. */
        val splitDecodes: Int = 0,
    )

    /**
     * Optional trace sink for the temporary local-only diagnostics path
     * (AsrDiagnostics) and the pipeline replay test. Null when nobody listens.
     */
    var onTrace: ((Trace) -> Unit)? = null

    private val denseHint = isDenseLanguage(language)
    private val autoDetect = language.isEmpty()

    /** Set once an auto-detect decode in this recording reported a dense script. */
    private var detectedDense = false

    /**
     * Starts a new recording. Clears the detection memo so the next part
     * decodes at its real length again; the configured language still applies.
     */
    fun onRecordingStart() {
        detectedDense = false
    }

    /**
     * Transcribes [samples].
     *
     * sherpa-onnx stops the Whisper decoder once it has produced six tokens
     * per second of audio, which cuts off scripts that need more than that
     * (Devanagari, Tamil, Chinese, ...). When the decode provably hit that
     * cap, re-run the same audio padded to sherpa's maximum input (~29.5 s),
     * where the cap is large enough for those scripts. The retry wins only
     * when it produced more tokens: an untruncated decode is always longer
     * than a capped one, while a shorter retry means padding made the model
     * stop early. English and other low-token scripts never hit the cap, so
     * they keep the faster single-pass path.
     *
     * For a known dense language, and for later parts of an auto-detect
     * recording that detected one, the part is padded up front instead and
     * decoded once, which avoids the wasted real-length pass.
     */
    override fun transcribe(samples: FloatArray, sampleRate: Int): String {
        val padUpFront = denseHint || detectedDense
        val input = if (padUpFront) padToWhisperWindow(samples, sampleRate) else samples
        val firstStart = System.nanoTime()
        val first = backend.decode(input, sampleRate)
        val firstDecodeMs = (System.nanoTime() - firstStart) / 1_000_000L
        observeLanguage(first)
        var retryDecodeMs = 0L
        val retry = !padUpFront && shouldRetryInFullWindow(first, samples.size, sampleRate)
        val chosen = if (retry) {
            val retryStart = System.nanoTime()
            val retried = backend.decode(padToWhisperWindow(samples, sampleRate), sampleRate)
            retryDecodeMs = (System.nanoTime() - retryStart) / 1_000_000L
            observeLanguage(retried)
            preferLonger(first, retried)
        } else {
            first
        }
        val chosenPadded = padUpFront || (retry && chosen !== first)
        splitDecodes = 0
        val text = if (chosenPadded && filledWindow(chosen, samples.size, sampleRate)) {
            decodeSplit(samples, sampleRate, depth = 1)
        } else {
            chosen.text.trim()
        }
        onTrace?.invoke(
            Trace(
                sampleCount = samples.size,
                sampleRate = sampleRate,
                firstPassTokens = first.tokens.size,
                tokenCap = decoderTokenCap(input.size, sampleRate),
                hitTokenCap = retry,
                usedRetry = retry,
                paddedUpFront = padUpFront,
                detectedLanguage = chosen.lang,
                firstDecodeMs = firstDecodeMs,
                retryDecodeMs = retryDecodeMs,
                emptyTokenCount = emptyTokenCount(chosen),
                splitDecodes = splitDecodes,
            )
        )
        return text
    }

    private var splitDecodes = 0

    /**
     * Even the padded window allows only 176 tokens, which a long part of
     * fast Devanagari speech can fill. Cuts the part at its quietest point
     * near the middle and decodes each half in its own padded window.
     */
    private fun decodeSplit(samples: FloatArray, sampleRate: Int, depth: Int): String {
        val cut = quietestCut(samples, sampleRate)
        return joinTranscriptSegments(
            listOf(
                decodeHalf(samples.copyOfRange(0, cut), sampleRate, depth),
                decodeHalf(samples.copyOfRange(cut, samples.size), sampleRate, depth),
            )
        )
    }

    private fun decodeHalf(samples: FloatArray, sampleRate: Int, depth: Int): String {
        val result = backend.decode(padToWhisperWindow(samples, sampleRate), sampleRate)
        splitDecodes++
        observeLanguage(result)
        return if (depth < MAX_SPLIT_DEPTH && filledWindow(result, samples.size, sampleRate)) {
            decodeSplit(samples, sampleRate, depth + 1)
        } else {
            result.text.trim()
        }
    }

    /** Remembers a dense detection from either decode of an auto recording. */
    private fun observeLanguage(result: OfflineRecognizerResult) {
        if (autoDetect && isDenseLanguage(result.lang)) detectedDense = true
    }

    override fun release() = backend.release()

    /** Decoder seam so padding and retry decisions run without the JNI. */
    internal interface DecodeBackend {
        fun decode(samples: FloatArray, sampleRate: Int): OfflineRecognizerResult
        fun release()
    }

    private class SherpaBackend(
        encoderPath: String,
        decoderPath: String,
        tokensPath: String,
        numThreads: Int,
        language: String,
    ) : DecodeBackend {
        private val recognizer = OfflineRecognizer(
            assetManager = null,
            config = OfflineRecognizerConfig(
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
            ),
        )

        override fun decode(samples: FloatArray, sampleRate: Int): OfflineRecognizerResult {
            val stream = recognizer.createStream()
            try {
                stream.acceptWaveform(samples, sampleRate)
                recognizer.decode(stream)
                return recognizer.getResult(stream)
            } finally {
                stream.release()
            }
        }

        override fun release() = recognizer.release()
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
         * Counts tokens the runtime could not convert to text. Non-zero means
         * the byte-fallback path is broken and the transcript is missing
         * characters; the whisper.cpp/Whisper BPE vocabularies store most
         * non-Latin characters as several raw-byte tokens.
         */
        internal fun emptyTokenCount(result: OfflineRecognizerResult): Int =
            result.tokens.count { it.isEmpty() }

        /**
         * Picks the padded [retried] result only when it decoded more tokens
         * than the capped [first] pass; otherwise the longer first pass stays.
         */
        internal fun preferLonger(
            first: OfflineRecognizerResult,
            retried: OfflineRecognizerResult,
        ): OfflineRecognizerResult =
            if (retried.tokens.size > first.tokens.size) retried else first

        /** Two split levels: at most 1 + 2 + 4 decodes for one part. */
        private const val MAX_SPLIT_DEPTH = 2
        private const val MIN_SPLIT_SECONDS = 4
        private const val LOOP_WINDOW = 24
        private const val LOOP_MAX_DISTINCT = 4

        /**
         * True when a padded decode used the whole window budget on real
         * speech. A repetition loop (wrong forced language) also fills the
         * budget, but splitting it only multiplies the loop, so it is kept.
         */
        internal fun filledWindow(
            result: OfflineRecognizerResult,
            sampleCount: Int,
            sampleRate: Int,
        ): Boolean =
            sampleCount >= MIN_SPLIT_SECONDS * sampleRate &&
                result.tokens.size >= decoderTokenCap(whisperWindowSamples(sampleRate), sampleRate) &&
                !endsInLoop(result.tokens)

        internal fun endsInLoop(tokens: Array<String>): Boolean =
            tokens.size >= LOOP_WINDOW &&
                tokens.takeLast(LOOP_WINDOW).distinct().size <= LOOP_MAX_DISTINCT

        /** Start of the quietest 100 ms window in the middle third of [samples]. */
        internal fun quietestCut(samples: FloatArray, sampleRate: Int): Int {
            val window = sampleRate / 10
            val start = samples.size / 3
            val end = samples.size * 2 / 3
            var best = samples.size / 2
            var bestEnergy = Double.MAX_VALUE
            var i = start
            while (i + window <= end) {
                var energy = 0.0
                for (j in i until i + window) energy += samples[j] * samples[j]
                if (energy < bestEnergy) {
                    bestEnergy = energy
                    best = i + window / 2
                }
                i += window
            }
            return best
        }

        /** Extends [samples] with trailing silence up to [whisperWindowSamples]. */
        internal fun padToWhisperWindow(samples: FloatArray, sampleRate: Int): FloatArray {
            val window = whisperWindowSamples(sampleRate)
            return if (samples.size >= window) samples else samples.copyOf(window)
        }
    }
}
