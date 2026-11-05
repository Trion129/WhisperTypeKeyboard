package me.trion.whispertype.voice

/** How the cut after a delivered part was made. */
enum class SegmentBoundary { Pause, Hard }

/**
 * Packs VAD speech segments into model-bounded parts.
 *
 * A part is delivered at the 28 s hard limit or near the 24 s target. The
 * boundary tells the joiner whether the next part starts after a speech pause.
 */
class SegmentPlanner(
    maxSegmentMs: Long,
    private val sampleRate: Int = 16_000,
    private val deliver: (ByteArray, SegmentBoundary) -> Unit,
) {
    private val maxSamples = maxSegmentMs * sampleRate / 1000L
    private val targetSamples = TARGET_MS * sampleRate / 1000L
    private val minSamples = MIN_MS * sampleRate / 1000L
    private val current = ArrayList<ByteArray>()
    private var samples = 0L

    /** Adds one complete VAD segment. [pausedBefore] is true when a pause ended the previous segment. */
    fun add(pcm: ByteArray, pausedBefore: Boolean) {
        if (pcm.isEmpty()) return
        val last = (pcm.size / 2).toLong()
        current += pcm
        samples += last
        if (samples > maxSamples) {
            if (samples - last == 0L) {
                emit(listOf(pcm), SegmentBoundary.Hard)
                clear()
                return
            }
            dropLast(pcm)
            // The hard limit outranks the 8 s minimum when one VAD segment is large.
            deliverCurrent(boundary(pausedBefore))
            current += pcm
            samples = last
            if (last > maxSamples) {
                emit(current.toList(), SegmentBoundary.Hard)
                clear()
            } else if (last >= targetSamples) {
                deliverCurrent(SegmentBoundary.Pause)
            }
        } else if (samples >= targetSamples) {
            if (samples - last >= minSamples) {
                dropLast(pcm)
                deliverCurrent(boundary(pausedBefore))
                current += pcm
                samples = last
            } else {
                deliverCurrent(SegmentBoundary.Pause)
            }
        }
    }

    /** Delivers the remaining part. [endedOnPause] is true when a pause ended the last segment. */
    fun finish(endedOnPause: Boolean) {
        if (samples > 0) deliverCurrent(boundary(endedOnPause))
    }

    private fun boundary(pausedBefore: Boolean) =
        if (pausedBefore) SegmentBoundary.Pause else SegmentBoundary.Hard

    private fun deliverCurrent(boundary: SegmentBoundary) {
        emit(current.toList(), boundary)
        clear()
    }

    private fun clear() {
        current.clear()
        samples = 0
    }

    private fun dropLast(pcm: ByteArray) {
        current.removeAt(current.size - 1)
        samples -= pcm.size / 2
    }

    private fun emit(parts: List<ByteArray>, boundary: SegmentBoundary) {
        val size = parts.sumOf { it.size }
        val pcm = ByteArray(size)
        var offset = 0
        for (part in parts) {
            System.arraycopy(part, 0, pcm, offset, part.size)
            offset += part.size
        }
        deliver.invoke(pcm, boundary)
    }

    private companion object {
        const val TARGET_MS = 24_000L
        const val MIN_MS = 8_000L
    }
}
