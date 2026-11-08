package me.trion.whispertype.voice

import org.junit.Assert.assertTrue
import org.junit.Assume
import org.junit.Test
import java.io.File
import java.util.Locale

/**
 * Host-JVM replay of the production VAD handoff and the adapter factory.
 * Opt-in: needs the model, the fixtures and the Linux JNI libraries.
 */
class VadHostPipelineReplayTest {
    @Test
    fun replayFixturesWithVad() {
        val modelId = System.getProperty("whispertype.host.model").orEmpty()
        val modelPath = System.getProperty("whispertype.host.modelDir").orEmpty()
        val fixturesPath = System.getProperty("whispertype.host.fixtures").orEmpty()
        Assume.assumeTrue(
            "host replay not configured",
            modelId.isNotEmpty() && modelPath.isNotEmpty() && fixturesPath.isNotEmpty(),
        )
        val vadModel = File("src/main/assets/vad/silero_vad.onnx")
        Assume.assumeTrue("VAD model is missing", vadModel.isFile)

        val spec = requireNotNull(ModelCatalog.byId(modelId)) { "Unknown catalog model: $modelId" }
        val directory = File(modelPath)
        val model = requireNotNull(ModelDownloader.resolveModelDirectory(directory, spec)) {
            "Model files do not match ${spec.id} descriptor at $modelPath"
        }

        val requested = System.getProperty("whispertype.host.language").orEmpty()
        val language = spec.languagePolicy.effectiveLanguage(requested)
        val out = File(System.getProperty("whispertype.host.out").orEmpty())
        val fixtures = File(fixturesPath)
        val only = System.getenv("WHISPERTYPE_HOST_VAD_FILE").orEmpty()
        val wavs = fixtures.listFiles { f -> f.isFile && f.name.endsWith(".wav") }
            ?.filter { only.isEmpty() || it.name == only }
            ?.sortedBy { it.name }
            .orEmpty()
        require(wavs.isNotEmpty()) { "No WAV files in $fixtures" }

        val maxSamples = spec.maxSegmentMs * 16_000L / 1000L
        val engine = AsrEngineFactory.create(model, requested)
        try {
            out.absoluteFile.parentFile?.mkdirs()
            out.bufferedWriter(Charsets.UTF_8).use { writer ->
                for (wav in wavs) {
                    // Each file is one recording, as LocalAsrEngine.onRecordingStart marks it.
                    (engine as? SherpaWhisperEngine)?.onRecordingStart()
                    val t0 = System.nanoTime()
                    val input = WavReader.read(wav)
                    val pcm = toPcm16(input.samples)
                    val segments = mutableListOf<Pair<ByteArray, SegmentBoundary>>()
                    val handoff = VadSegmentHandoff.forModel(
                        modelFile = vadModel,
                        maxSegmentMs = spec.maxSegmentMs,
                        sampleRate = input.sampleRate,
                    ) { pcm, boundary -> segments += pcm to boundary }
                    var offset = 0
                    while (offset < pcm.size) {
                        val count = minOf(3_200, pcm.size - offset)
                        handoff.append(pcm, offset, count)
                        offset += count
                    }
                    handoff.finish()
                    val t1 = System.nanoTime()

                    assertTrue("no speech segment in ${wav.name}", segments.isNotEmpty())
                    var totalSamples = 0L
                    val segmentSeconds = ArrayList<Double>()
                    val texts = ArrayList<TranscriptPart>()
                    var startsAfterPause = false
                    for ((segment, boundary) in segments) {
                        // The phone writes each part to a WAV and LocalAsrEngine reads it
                        // back through WavReader, which peak-normalizes the part.
                        val samples = WavReader.read(wavBytes(segment, input.sampleRate)).samples
                        totalSamples += samples.size
                        assertTrue(
                            "segment longer than the model limit in ${wav.name}",
                            samples.size <= maxSamples,
                        )
                        segmentSeconds += samples.size / input.sampleRate.toDouble()
                        texts += TranscriptPart(
                            engine.transcribe(samples, input.sampleRate),
                            startsAfterPause,
                        )
                        startsAfterPause = boundary == SegmentBoundary.Pause
                    }
                    val text = joinTranscriptSegments(texts)
                    val t2 = System.nanoTime()
                    val row = buildString {
                        append('{')
                        append("\"file\":").append(json(wav.name))
                        append(",\"text\":").append(json(text))
                        append(",\"segments\":").append(segments.size)
                        append(",\"segment_seconds\":[").append(
                            segmentSeconds.joinToString(",") {
                                String.format(Locale.ROOT, "%.3f", it)
                            },
                        ).append(']')
                        append(",\"duration_s\":").append(
                            String.format(Locale.ROOT, "%.3f", input.samples.size / input.sampleRate.toDouble()),
                        )
                        append(",\"speech_s\":").append(
                            String.format(Locale.ROOT, "%.3f", totalSamples / input.sampleRate.toDouble()),
                        )
                        append(",\"segments_ms\":").append((t1 - t0) / 1_000_000)
                        append(",\"transcribe_ms\":").append((t2 - t1) / 1_000_000)
                        append(",\"model_id\":").append(json(spec.id))
                        append(",\"language\":").append(json(requested))
                        append(",\"resolved_language\":").append(json(language))
                        append('}')
                    }
                    writer.write(row)
                    writer.write("\n")
                    println("vad-replay ${wav.name} segments=${segments.size} text=$text")
                }
            }
        } finally {
            engine.release()
        }
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

    private fun wavBytes(pcm: ByteArray, sampleRate: Int): ByteArray =
        java.nio.ByteBuffer.allocate(44 + pcm.size).order(java.nio.ByteOrder.LITTLE_ENDIAN)
            .put("RIFF".toByteArray(Charsets.US_ASCII)).putInt(pcm.size + 36)
            .put("WAVE".toByteArray(Charsets.US_ASCII)).put("fmt ".toByteArray(Charsets.US_ASCII))
            .putInt(16).putShort(1).putShort(1).putInt(sampleRate).putInt(sampleRate * 2)
            .putShort(2).putShort(16).put("data".toByteArray(Charsets.US_ASCII)).putInt(pcm.size)
            .put(pcm).array()

    private fun json(s: String): String = buildString(s.length + 2) {
        append('"')
        for (c in s) when (c) {
            '"' -> append("\\\"")
            '\\' -> append("\\\\")
            '\n' -> append("\\n")
            '\r' -> append("\\r")
            '\t' -> append("\\t")
            else -> if (c.code < 0x20) append("\\u%04x".format(c.code)) else append(c)
        }
        append('"')
    }
}
