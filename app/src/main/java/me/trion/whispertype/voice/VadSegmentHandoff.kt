package me.trion.whispertype.voice

import java.io.File

/**
 * Cuts the microphone stream at speech pauses with a VAD, packs the detected
 * speech into model-bounded parts and delivers each part as PCM16.
 *
 * A hard cap flushes the current segment when the speaker does not pause, so
 * one VAD segment never exceeds the model limit.
 */
class VadSegmentHandoff(
    private val detector: SpeechDetector,
    maxSegmentMs: Long,
    private val sampleRate: Int = 16_000,
    private val deliver: (ByteArray, SegmentBoundary) -> Unit,
) : SegmentHandoff {
    private val maxSamples = maxSegmentMs * sampleRate / 1000L
    private val planner = SegmentPlanner(maxSegmentMs, sampleRate, deliver)
    // Raw audio the detector has seen, so a segment can be delivered with real context around it.
    private val ring = FloatArray(RING_SECONDS * sampleRate)
    private var written = 0L
    /** Stream position where the detector's sample count is zero (moves on reset). */
    private var detectorOrigin = 0L
    /** Stream position where the last delivered segment ended. */
    private var deliveredEnd = 0L
    private var samplesSinceCut = 0L
    private var lastCutWasPause = false
    private var finished = false
    private var byteCount = 0L

    @Synchronized
    override fun append(pcm: ByteArray, offset: Int, length: Int) {
        if (finished) return
        byteCount += length
        val samples = toFloats(pcm, offset, length)
        remember(samples)
        detector.accept(samples)
        if (detector.isSpeechDetected()) samplesSinceCut += samples.size else samplesSinceCut = 0
        drain(natural = true)
        if (samplesSinceCut >= maxSamples) {
            detector.flush()
            drain(natural = false)
            detector.reset()
            detectorOrigin = written
            samplesSinceCut = 0
        }
    }

    @Synchronized
    override fun finish() {
        if (finished) return
        finished = true
        try {
            detector.flush()
            drain(natural = false)
            planner.finish(lastCutWasPause)
        } finally {
            detector.release()
        }
    }

    @Synchronized
    override fun cancel() {
        if (finished) return
        finished = true
        detector.release()
    }

    @Synchronized
    override fun capturedBytes(): Long = byteCount

    private fun drain(natural: Boolean) {
        while (detector.hasSegment()) {
            val speech = detector.takeSegment()
            val start = detector.segmentStart()
            detector.pop()
            if (speech.isEmpty()) continue
            planner.add(toPcm16(withContext(speech, start)), lastCutWasPause)
            // Silero can end a segment on its own speech cap, not on a pause.
            lastCutWasPause = natural && speech.size < maxSamples - sampleRate
            samplesSinceCut = 0
        }
    }

    private fun remember(samples: FloatArray) {
        for (sample in samples) {
            ring[(written % ring.size).toInt()] = sample
            written++
        }
    }

    /**
     * Silero starts a segment on the first window it scores as speech and ends
     * it right after the last one, so soft first syllables are cut and the
     * model hears speech start with no lead-in; on real Spanish speech that
     * dropped the first word of 14 of 75 clips. Deliver the recorded audio
     * [PRE_CONTEXT_MS] before and [POST_CONTEXT_MS] after the segment instead,
     * never overlapping the previous segment and never above the model limit.
     */
    private fun withContext(speech: FloatArray, detectorStart: Long): FloatArray {
        if (detectorStart < 0) return speech
        val speechStart = detectorOrigin + detectorStart
        val speechEnd = speechStart + speech.size
        if (speechEnd > written) return speech
        val floor = maxOf(deliveredEnd, written - ring.size)
        var from = maxOf(speechStart - PRE_CONTEXT_MS * sampleRate / 1000, floor)
        var to = minOf(speechEnd + POST_CONTEXT_MS * sampleRate / 1000, written)
        val excess = (to - from) - maxSamples
        if (excess > 0) {
            val trimPost = minOf(excess, maxOf(0L, to - speechEnd))
            to -= trimPost
            from += excess - trimPost
        }
        // The previous segment's trailing context may already hold this one's
        // start: continue where it stopped, so no audio is lost or repeated.
        if (from >= to) return FloatArray(0)
        deliveredEnd = to
        return FloatArray((to - from).toInt()) { ring[((from + it) % ring.size).toInt()] }
    }

    private fun toFloats(pcm: ByteArray, offset: Int, length: Int): FloatArray {
        val count = length / 2
        val samples = FloatArray(count)
        var i = 0
        while (i < count) {
            val sample = ((pcm[offset + 2 * i + 1].toInt() shl 8) or
                (pcm[offset + 2 * i].toInt() and 0xff)).toShort().toInt()
            samples[i] = sample / 32768f
            i++
        }
        return samples
    }

    private fun toPcm16(samples: FloatArray): ByteArray {
        val pcm = ByteArray(samples.size * 2)
        for (i in samples.indices) {
            // Exact inverse of toFloats, so a sample survives the round trip unchanged.
            val value = Math.round(samples[i] * 32768f).coerceIn(-32768, 32767)
            pcm[2 * i] = (value and 0xff).toByte()
            pcm[2 * i + 1] = ((value shr 8) and 0xff).toByte()
        }
        return pcm
    }

    companion object {
        const val PRE_CONTEXT_MS = 500L
        const val POST_CONTEXT_MS = 300L
        private const val RING_SECONDS = 40

        fun forModel(
            modelFile: File,
            maxSegmentMs: Long,
            sampleRate: Int = 16_000,
            deliver: (ByteArray, SegmentBoundary) -> Unit,
        ): VadSegmentHandoff = VadSegmentHandoff(
            detector = SileroSpeechDetector(
                modelFile = modelFile,
                maxSpeechSeconds = (maxSegmentMs / 1000f) - 1f,
                sampleRate = sampleRate,
            ),
            maxSegmentMs = maxSegmentMs,
            sampleRate = sampleRate,
            deliver = deliver,
        )
    }
}
