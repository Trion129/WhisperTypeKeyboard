package me.trion.whispertype.voice

import kotlin.math.abs

/** Sample-aligned PCM16 mono segmenting with a bounded current-segment buffer. */
class PcmSegmenter(
    maxSegmentMs: Long,
    private val sampleRate: Int = 16_000,
    private val silenceThreshold: Int = 450,
    private val silenceWindowMs: Int = 120,
) {
    private val maxBytes = ((maxSegmentMs * sampleRate * 2L) / 1000L).toInt() and -2
    private val softBytes = ((maxSegmentMs - 3_000L).coerceAtLeast(1_000L) * sampleRate * 2L / 1000L).toInt() and -2
    private val windowBytes = (sampleRate * 2 * silenceWindowMs / 1000) and -2
    private val pending = ByteArray(maxBytes)
    private var pendingSize = 0
    private var nextSilenceWindow = softBytes

    init {
        require(maxSegmentMs in MIN_SEGMENT_MS..MAX_SEGMENT_MS)
        require(sampleRate > 0)
        require(silenceWindowMs > 0 && windowBytes > 0)
    }

    @Synchronized
    fun append(pcm: ByteArray, offset: Int = 0, length: Int = pcm.size - offset): List<ByteArray> {
        require(offset >= 0 && length >= 0 && offset + length <= pcm.size)
        require(offset % 2 == 0) { "PCM16 input offset must be sample-aligned" }
        require(length % 2 == 0) { "PCM16 input length must be sample-aligned" }
        val emitted = ArrayList<ByteArray>()
        var source = offset
        val sourceEnd = offset + length
        while (source < sourceEnd) {
            val copyCount = minOf(maxBytes - pendingSize, sourceEnd - source) and -2
            if (copyCount == 0) break
            System.arraycopy(pcm, source, pending, pendingSize, copyCount)
            pendingSize += copyCount
            source += copyCount

            val silenceCut = findSilenceCut()
            val cut = if (silenceCut > 0) silenceCut else if (pendingSize == maxBytes) maxBytes else 0
            if (cut > 0) emit(cut, emitted)
        }
        return emitted
    }

    @Synchronized
    fun finish(): ByteArray? {
        if (pendingSize == 0) return null
        val segment = pending.copyOf(pendingSize)
        clear()
        return segment
    }

    @Synchronized
    fun discard() = clear()

    private fun findSilenceCut(): Int {
        while (nextSilenceWindow + windowBytes <= pendingSize) {
            val start = nextSilenceWindow
            val end = start + windowBytes
            var sum = 0L
            var i = start
            while (i < end) {
                val sample = ((pending[i + 1].toInt() shl 8) or (pending[i].toInt() and 0xff)).toShort().toInt()
                sum += abs(sample)
                i += 2
            }
            nextSilenceWindow = end
            if (sum / (windowBytes / 2) < silenceThreshold) return end
        }
        return 0
    }

    private fun emit(cut: Int, output: MutableList<ByteArray>) {
        output += pending.copyOfRange(0, cut)
        pendingSize -= cut
        if (pendingSize > 0) System.arraycopy(pending, cut, pending, 0, pendingSize)
        nextSilenceWindow = maxOf(softBytes, nextSilenceWindow - cut)
    }

    private fun clear() {
        pendingSize = 0
        nextSilenceWindow = softBytes
    }
}

/** One decoded part and whether a speech pause came before it. */
data class TranscriptPart(val text: String, val startsAfterPause: Boolean)

/**
 * Joins decoded parts, adding a space only at a pause cut between spaced
 * scripts. Applies sentence capitalization once and skips empty parts.
 */
@JvmName("joinTranscriptParts")
internal fun joinTranscriptSegments(parts: List<TranscriptPart>): String {
    val out = StringBuilder()
    for (part in parts) {
        val text = part.text.trim()
        if (text.isEmpty()) continue
        if (out.isNotEmpty() && part.startsAfterPause &&
            !isUnspaced(out[out.length - 1]) && !isUnspaced(text[0])
        ) {
            out.append(' ')
        }
        out.append(text)
    }
    return formatTranscript(out.toString())
}

internal fun joinTranscriptSegments(segments: List<String>): String =
    joinTranscriptSegments(segments.map { TranscriptPart(it, startsAfterPause = true) })

/** Scripts that do not put a space between words. */
private fun isUnspaced(ch: Char): Boolean = when (ch.code) {
    in 0x4E00..0x9FFF, // Han
    in 0x3040..0x30FF, // Hiragana, Katakana
    in 0x3100..0x312F, in 0x31A0..0x31BF, // Bopomofo
    in 0x0E00..0x0E7F, // Thai
    in 0x0E80..0x0EFF, // Lao
    in 0x1780..0x17FF, // Khmer
    in 0x1000..0x109F, // Myanmar
    in 0x3000..0x303F, // CJK punctuation
    in 0xFF01..0xFF65, // fullwidth forms
    -> true
    else -> false
}
