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
    private var samplesSinceCut = 0L
    private var lastCutWasPause = false
    private var finished = false
    private var byteCount = 0L

    @Synchronized
    override fun append(pcm: ByteArray, offset: Int, length: Int) {
        if (finished) return
        byteCount += length
        val samples = toFloats(pcm, offset, length)
        detector.accept(samples)
        if (detector.isSpeechDetected()) samplesSinceCut += samples.size else samplesSinceCut = 0
        drain(natural = true)
        if (samplesSinceCut >= maxSamples) {
            detector.flush()
            drain(natural = false)
            detector.reset()
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
            val samples = detector.takeSegment()
            detector.pop()
            if (samples.isEmpty()) continue
            planner.add(toPcm16(samples), lastCutWasPause)
            // Silero can end a segment on its own speech cap, not on a pause.
            lastCutWasPause = natural && samples.size < maxSamples - sampleRate
            samplesSinceCut = 0
        }
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
            val value = (samples[i] * 32767f).toInt().coerceIn(-32768, 32767)
            pcm[2 * i] = (value and 0xff).toByte()
            pcm[2 * i + 1] = ((value shr 8) and 0xff).toByte()
        }
        return pcm
    }

    companion object {
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
