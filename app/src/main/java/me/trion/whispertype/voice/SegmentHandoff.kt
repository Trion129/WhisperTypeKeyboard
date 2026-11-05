package me.trion.whispertype.voice

/** Splits the microphone stream into bounded PCM16 segments. */
interface SegmentHandoff {
    fun append(pcm: ByteArray, offset: Int = 0, length: Int = pcm.size - offset)
    fun finish()
    fun cancel()
    fun capturedBytes(): Long
}
