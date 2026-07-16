package com.whispertype.keyboard.voice

import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
class AudioRecorder {
    private var recorder: AudioRecord? = null
    private var recordingThread: Thread? = null
    @Volatile private var isRecording = false
    private val pcmBuffer = ByteArrayOutputStream()

    fun start(): Boolean {
        if (isRecording) return true

        val sampleRate = SAMPLE_RATE
        val channelConfig = AudioFormat.CHANNEL_IN_MONO
        val audioFormat = AudioFormat.ENCODING_PCM_16BIT
        val minBuffer = AudioRecord.getMinBufferSize(sampleRate, channelConfig, audioFormat)
        if (minBuffer == AudioRecord.ERROR || minBuffer == AudioRecord.ERROR_BAD_VALUE) {
            return false
        }

        val bufferSize = minBuffer * 2
        val audioRecord = try {
            AudioRecord(
                MediaRecorder.AudioSource.VOICE_RECOGNITION,
                sampleRate,
                channelConfig,
                audioFormat,
                bufferSize
            )
        } catch (_: SecurityException) {
            return false
        }

        if (audioRecord.state != AudioRecord.STATE_INITIALIZED) {
            audioRecord.release()
            return false
        }

        pcmBuffer.reset()
        recorder = audioRecord
        isRecording = true
        audioRecord.startRecording()

        recordingThread = Thread({
            val buffer = ByteArray(bufferSize)
            while (isRecording) {
                val read = audioRecord.read(buffer, 0, buffer.size)
                if (read > 0) {
                    synchronized(pcmBuffer) {
                        pcmBuffer.write(buffer, 0, read)
                    }
                }
            }
        }, "WhisperType-AudioRecorder").also {
            it.start()
        }
        return true
    }

    fun stopToWav(target: File): File? {
        if (!isRecording && pcmBuffer.size() == 0) return null

        isRecording = false
        recordingThread?.join(1500)
        recordingThread = null

        try {
            recorder?.stop()
        } catch (_: Exception) {
        }
        recorder?.release()
        recorder = null

        val pcm: ByteArray
        synchronized(pcmBuffer) {
            pcm = pcmBuffer.toByteArray()
            pcmBuffer.reset()
        }

        if (pcm.isEmpty() || isSilent(pcm)) {
            return null
        }

        writeWav(target, pcm, SAMPLE_RATE)
        return target
    }

    fun cancel() {
        isRecording = false
        recordingThread?.join(1000)
        recordingThread = null
        try {
            recorder?.stop()
        } catch (_: Exception) {
        }
        recorder?.release()
        recorder = null
        synchronized(pcmBuffer) {
            pcmBuffer.reset()
        }
    }

    private fun isSilent(pcm: ByteArray): Boolean {
        if (pcm.size < 4) return true
        var sum = 0L
        var count = 0
        var i = 0
        while (i + 1 < pcm.size) {
            val sample = ((pcm[i + 1].toInt() shl 8) or (pcm[i].toInt() and 0xFF)).toShort()
            sum += kotlin.math.abs(sample.toInt())
            count++
            i += 2
        }
        if (count == 0) return true
        return (sum / count) < SILENCE_THRESHOLD
    }

    private fun writeWav(file: File, pcm: ByteArray, sampleRate: Int) {
        val channels = 1
        val bitsPerSample = 16
        val byteRate = sampleRate * channels * bitsPerSample / 8
        val totalDataLen = pcm.size + 36
        val header = ByteBuffer.allocate(44).order(ByteOrder.LITTLE_ENDIAN)

        header.put("RIFF".toByteArray(Charsets.US_ASCII))
        header.putInt(totalDataLen)
        header.put("WAVE".toByteArray(Charsets.US_ASCII))
        header.put("fmt ".toByteArray(Charsets.US_ASCII))
        header.putInt(16)
        header.putShort(1)
        header.putShort(channels.toShort())
        header.putInt(sampleRate)
        header.putInt(byteRate)
        header.putShort((channels * bitsPerSample / 8).toShort())
        header.putShort(bitsPerSample.toShort())
        header.put("data".toByteArray(Charsets.US_ASCII))
        header.putInt(pcm.size)

        FileOutputStream(file).use { out ->
            out.write(header.array())
            out.write(pcm)
        }
    }

    companion object {
        private const val SAMPLE_RATE = 16_000
        private const val SILENCE_THRESHOLD = 80
    }
}
