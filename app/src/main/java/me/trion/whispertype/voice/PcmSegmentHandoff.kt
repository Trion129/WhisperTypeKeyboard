package me.trion.whispertype.voice

/** Moves each complete bounded PCM segment to its consumer and retains only the current tail. */
class PcmSegmentHandoff(
    maxSegmentMs: Long,
    private val deliver: (ByteArray, SegmentBoundary) -> Unit,
) : SegmentHandoff {
    private val segmenter = PcmSegmenter(maxSegmentMs)
    private val maxBytes = ((maxSegmentMs * SAMPLE_RATE * 2L) / 1000L).toInt() and -2
    private var finished = false
    private var byteCount = 0L

    @Synchronized
    override fun append(pcm: ByteArray, offset: Int, length: Int) {
        if (finished) return
        byteCount += length
        segmenter.append(pcm, offset, length).forEach { segment ->
            // A cut below the hard maximum means the silence window fired.
            deliver(segment, if (segment.size < maxBytes) SegmentBoundary.Pause else SegmentBoundary.Hard)
        }
    }

    @Synchronized
    override fun finish() {
        if (finished) return
        finished = true
        segmenter.finish()?.let { deliver(it, SegmentBoundary.Hard) }
    }

    @Synchronized
    override fun cancel() {
        if (finished) return
        finished = true
        segmenter.discard()
    }

    @Synchronized
    override fun capturedBytes(): Long = byteCount

    private companion object {
        const val SAMPLE_RATE = 16_000
    }
}
