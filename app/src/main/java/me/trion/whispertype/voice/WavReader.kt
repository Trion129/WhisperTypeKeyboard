package me.trion.whispertype.voice

import java.io.File
import java.io.InputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder

data class WavData(val samples: FloatArray, val sampleRate: Int)

object WavReader {
    fun read(file: File): WavData {
        return read(file.readBytes())
    }

    fun read(bytes: ByteArray): WavData {
        val buf = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)

        val riff = readString(buf, 4)
        require(riff == "RIFF") { "Not a valid RIFF file" }
        buf.getInt()
        val wave = readString(buf, 4)
        require(wave == "WAVE") { "Not a valid WAVE file" }

        var sampleRate = 0
        var bitsPerSample = 16
        var channels = 1
        var data: ByteArray? = null

        while (buf.remaining() >= 8) {
            val chunkId = readString(buf, 4)
            val chunkSize = buf.getInt()

            when (chunkId) {
                "fmt " -> {
                    val fmtBuf = ByteBuffer.wrap(bytes, buf.position(), chunkSize).order(ByteOrder.LITTLE_ENDIAN)
                    val audioFormat = fmtBuf.getShort()
                    require(audioFormat.toInt() == 1) { "Only PCM WAV supported (format=$audioFormat)" }
                    channels = fmtBuf.getShort().toInt()
                    sampleRate = fmtBuf.getInt()
                    fmtBuf.getInt()
                    fmtBuf.getShort()
                    bitsPerSample = fmtBuf.getShort().toInt()
                    buf.position(buf.position() + chunkSize)
                }
                "data" -> {
                    data = ByteArray(chunkSize)
                    buf.get(data)
                }
                else -> {
                    buf.position(minOf(buf.position() + chunkSize, buf.limit()))
                }
            }
        }

        val rawData = requireNotNull(data) { "No data chunk found" }
        require(sampleRate > 0) { "No fmt chunk found" }

        val samples = preprocess(decodePcm(rawData, bitsPerSample, channels), sampleRate)
        return WavData(samples, TARGET_SAMPLE_RATE)
    }

    private fun decodePcm(data: ByteArray, bitsPerSample: Int, channels: Int): FloatArray {
        val bytesPerSample = bitsPerSample / 8
        val totalFrames = data.size / (bytesPerSample * channels)
        val mono = FloatArray(totalFrames)

        for (i in 0 until totalFrames) {
            var sum = 0.0
            for (ch in 0 until channels) {
                val offset = (i * channels + ch) * bytesPerSample
                val sample = when (bitsPerSample) {
                    8 -> (data[offset].toInt() and 0xFF) - 128
                    16 -> {
                        var s = data[offset].toInt() and 0xFF
                        s = s or (data[offset + 1].toInt() shl 8)
                        s.toShort().toInt()
                    }
                    else -> throw IllegalArgumentException("Unsupported bits per sample: $bitsPerSample")
                }
                sum += sample
            }
            mono[i] = (sum / channels).toFloat() / if (bitsPerSample == 8) 128f else 32768f
        }
        return mono
    }

    private fun preprocess(input: FloatArray, sampleRate: Int): FloatArray {
        if (input.isEmpty()) return input

        val resampled = if (sampleRate == TARGET_SAMPLE_RATE) input else resample(input, sampleRate)
        var peak = 0f
        for (sample in resampled) peak = maxOf(peak, kotlin.math.abs(sample))
        if (peak == 0f) return resampled

        // Match the reference RTranslator/WhisperIMEplus preprocessing:
        // preserve the complete recording and normalize by its absolute peak.
        return FloatArray(resampled.size) { i -> resampled[i] / peak }
    }

    private fun resample(input: FloatArray, sourceRate: Int): FloatArray {
        val outputSize = (input.size.toLong() * TARGET_SAMPLE_RATE / sourceRate).toInt()
        val output = FloatArray(outputSize)
        val ratio = sourceRate.toDouble() / TARGET_SAMPLE_RATE
        for (i in output.indices) {
            val source = i * ratio
            val left = source.toInt().coerceIn(0, input.lastIndex)
            val right = (left + 1).coerceAtMost(input.lastIndex)
            val fraction = source - left
            output[i] = (input[left] * (1.0 - fraction) + input[right] * fraction).toFloat()
        }
        return output
    }

    private fun readString(buf: ByteBuffer, length: Int): String {
        val bytes = ByteArray(length)
        buf.get(bytes)
        return String(bytes, Charsets.US_ASCII)
    }

    private const val TARGET_SAMPLE_RATE = 16_000
}
