package me.trion.whispertype.ime

data class RecordingTick(val elapsedMs: Long)

/** Converts cumulative PCM16 byte count to elapsed time without a total-duration clamp. */
class RecordingLimiter {
    fun onBytes(pcmBytes: Int, sampleRate: Int = 16_000): RecordingTick =
        onBytes(pcmBytes.toLong(), sampleRate)

    fun onBytes(pcmBytes: Long, sampleRate: Int = 16_000): RecordingTick {
        val bytesPerSecond = sampleRate.toLong() * 2L
        val elapsed = if (bytesPerSecond <= 0L) 0L else pcmBytes.coerceAtLeast(0L) * 1000L / bytesPerSecond
        return RecordingTick(elapsed)
    }
}
