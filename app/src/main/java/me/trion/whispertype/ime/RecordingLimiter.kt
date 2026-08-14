package me.trion.whispertype.ime

enum class RecordingPhase { LISTENING, WARN, LIMIT }

data class RecordingTick(
    val elapsedMs: Long,
    val remainingMs: Long,
    val phase: RecordingPhase,
)

class RecordingLimiter(
    val maxMs: Long = 30_000,
    val warnMs: Long = 25_000,
) {
    fun onBytes(pcmBytes: Int, sampleRate: Int = 16_000): RecordingTick {
        val bytesPerMs = sampleRate.toLong() * 2L
        val elapsed = if (bytesPerMs <= 0L) {
            0L
        } else {
            (pcmBytes.toLong() * 1000L) / bytesPerMs
        }
        val clamped = elapsed.coerceAtMost(maxMs)
        val remaining = (maxMs - clamped).coerceAtLeast(0L)
        val phase = when {
            elapsed >= maxMs -> RecordingPhase.LIMIT
            elapsed >= warnMs -> RecordingPhase.WARN
            else -> RecordingPhase.LISTENING
        }
        return RecordingTick(elapsedMs = clamped, remainingMs = remaining, phase = phase)
    }
}
