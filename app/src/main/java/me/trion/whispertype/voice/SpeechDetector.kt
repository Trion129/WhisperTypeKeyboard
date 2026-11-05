package me.trion.whispertype.voice

import com.k2fsa.sherpa.onnx.SileroVadModelConfig
import com.k2fsa.sherpa.onnx.Vad
import com.k2fsa.sherpa.onnx.VadModelConfig
import java.io.File

/**
 * Finds speech in a live stream and returns complete speech segments.
 *
 * The production implementation is [SileroSpeechDetector]; tests use a fake.
 */
interface SpeechDetector {
    fun accept(samples: FloatArray)
    fun hasSegment(): Boolean

    /** Returns the next complete speech segment. Call [pop] after this. */
    fun takeSegment(): FloatArray
    fun pop()
    fun flush()
    fun reset()
    fun release()
    fun isSpeechDetected(): Boolean
}

/**
 * Silero VAD v4 through sherpa-onnx.
 *
 * The detector keeps approximately 314 ms of audio before the speech start
 * (two windows plus the minimum speech duration) and removes the silence
 * after the speech end (minimum silence duration).
 */
class SileroSpeechDetector(
    modelFile: File,
    maxSpeechSeconds: Float,
    sampleRate: Int = 16_000,
) : SpeechDetector {
    private val vad = Vad(
        assetManager = null,
        config = VadModelConfig(
            sileroVadModelConfig = SileroVadModelConfig(
                model = modelFile.absolutePath,
                threshold = THRESHOLD,
                minSilenceDuration = MIN_SILENCE_SECONDS,
                minSpeechDuration = MIN_SPEECH_SECONDS,
                windowSize = WINDOW_SAMPLES,
                maxSpeechDuration = maxSpeechSeconds,
            ),
            sampleRate = sampleRate,
            numThreads = 1,
            provider = "cpu",
        ),
    )

    override fun accept(samples: FloatArray) = vad.acceptWaveform(samples)
    override fun hasSegment(): Boolean = !vad.empty()
    override fun takeSegment(): FloatArray = vad.front().samples
    override fun pop() = vad.pop()
    override fun flush() = vad.flush()
    override fun reset() = vad.reset()
    override fun release() = vad.release()
    override fun isSpeechDetected(): Boolean = vad.isSpeechDetected()

    companion object {
        /** A pause this long ends the current segment. Handy uses 450 ms. */
        const val MIN_SILENCE_SECONDS = 0.45f
        const val MIN_SPEECH_SECONDS = 0.25f
        const val THRESHOLD = 0.5f
        const val WINDOW_SAMPLES = 512
    }
}
