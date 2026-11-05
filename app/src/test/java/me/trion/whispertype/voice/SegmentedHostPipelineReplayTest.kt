package me.trion.whispertype.voice

import org.junit.Assume
import org.junit.Test
import java.io.File
import java.util.Locale

/** Replays a long WAV through the production descriptor, adapter factory and PCM segmenter. */
class SegmentedHostPipelineReplayTest {
    @Test
    fun replayLongWavAsBoundedSegments() {
        val modelId = System.getProperty("whispertype.host.model").orEmpty()
        val modelPath = System.getProperty("whispertype.host.modelDir").orEmpty()
        val fixturesPath = System.getProperty("whispertype.host.fixtures").orEmpty()
        Assume.assumeTrue(modelId.isNotBlank() && modelPath.isNotBlank() && fixturesPath.isNotBlank())

        val spec = requireNotNull(ModelCatalog.byId(modelId)) { "Unknown catalog model: $modelId" }
        val modelDirectory = File(modelPath)
        if (System.getenv("WHISPERTYPE_FETCH_ARTIFACTS") == "1") {
            modelDirectory.mkdirs()
            ModelDownloader.fetchArtifacts(spec, modelDirectory)
        }
        val model = requireNotNull(ModelDownloader.resolveModelDirectory(modelDirectory, spec)) {
            "Model files do not match ${spec.id} descriptor at $modelPath"
        }
        val wavFile = File(fixturesPath, "segmented-over-60s.wav")
        require(wavFile.isFile) { "Missing long replay WAV: $wavFile" }

        val language = System.getProperty("whispertype.host.language").orEmpty()
        val input = WavReader.read(wavFile)
        val engine = AsrEngineFactory.create(model, language)
        val segmenter = PcmSegmenter(spec.maxSegmentMs)
        var samplesSeen = 0L
        var transcriptChars = 0
        var segmentIndex = 0
        val transcripts = ArrayList<String>()

        fun transcribe(pcm: ByteArray) {
            val samples = FloatArray(pcm.size / 2)
            var offset = 0
            for (index in samples.indices) {
                val value = ((pcm[offset + 1].toInt() shl 8) or (pcm[offset].toInt() and 0xff)).toShort().toInt()
                samples[index] = value / 32768f
                offset += 2
            }
            val start = samplesSeen
            samplesSeen += samples.size
            val text = engine.transcribe(samples, input.sampleRate).trim()
            transcripts += text
            transcriptChars += text.length
            segmentIndex++
            println(
                "segmented-host model=$modelId segment=$segmentIndex " +
                    "samples=$start..$samplesSeen duration_s=${String.format(Locale.ROOT, "%.3f", samples.size.toDouble() / input.sampleRate)} " +
                    "transcript_chars=${text.length} text=$text",
            )
        }

        try {
            var sampleOffset = 0
            while (sampleOffset < input.samples.size) {
                val count = minOf(4_096, input.samples.size - sampleOffset)
                val chunk = ByteArray(count * 2)
                for (index in 0 until count) {
                    val sample = (input.samples[sampleOffset + index].coerceIn(-1f, 1f) * 32767f).toInt()
                    chunk[index * 2] = sample.toByte()
                    chunk[index * 2 + 1] = (sample shr 8).toByte()
                }
                segmenter.append(chunk).forEach(::transcribe)
                sampleOffset += count
            }
            segmenter.finish()?.let(::transcribe)
            val durationSeconds = input.samples.size.toDouble() / input.sampleRate
            require(durationSeconds > 60.0) { "Replay WAV must exceed 60 seconds; got $durationSeconds" }
            println("segmented-host-joined model=$modelId text=${joinTranscriptSegments(transcripts)}")
            println(
                "segmented-host-summary model=$modelId duration_s=${String.format(Locale.ROOT, "%.3f", durationSeconds)} " +
                    "segments=$segmentIndex transcript_chars=$transcriptChars nonempty=${transcripts.count(String::isNotEmpty)}",
            )
        } finally {
            engine.release()
        }
    }
}
