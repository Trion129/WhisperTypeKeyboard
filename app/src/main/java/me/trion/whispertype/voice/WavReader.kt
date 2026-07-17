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
                }
                "data" -> {
                    data = ByteArray(chunkSize)
                    buf.get(data)
                }
                else -> {
                    buf.position(buf.position() + chunkSize)
                }
            }
        }

        val rawData = requireNotNull(data) { "No data chunk found" }
        require(sampleRate > 0) { "No fmt chunk found" }

        val samples = decodePcm(rawData, bitsPerSample, channels)
        return WavData(samples, sampleRate)
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

    private fun readString(buf: ByteBuffer, length: Int): String {
        val bytes = ByteArray(length)
        buf.get(bytes)
        return String(bytes, Charsets.US_ASCII)
    }
}
