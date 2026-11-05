package me.trion.whispertype.voice

import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.util.Log
import java.io.File
import java.nio.ByteBuffer
import java.io.FileOutputStream
import java.util.concurrent.atomic.AtomicLong
import java.nio.ByteOrder

/** One persisted WAV part and whether a speech pause came before it. */
data class SegmentFile(val file: File, val startsAfterPause: Boolean)

/** Records mono PCM16 and writes only completed, model-bounded segments to temporary WAVs. */
class AudioRecorder {
    private var recorder: AudioRecord? = null
    private var recordingThread: Thread? = null
    @Volatile private var isRecording = false
    @Volatile private var discardCapture = false
    @Volatile private var handoff: SegmentHandoff? = null
    @Volatile private var segmentDirectory: File? = null
    @Volatile private var previousBoundary: SegmentBoundary? = null
    @Volatile var onBytes: ((Long) -> Unit)? = null
    @Volatile var onSegmentFile: ((SegmentFile) -> Unit)? = null
    @Volatile var onError: ((String) -> Unit)? = null

    fun start(maxSegmentMs: Long, cacheDirectory: File, vadModel: File? = null): Boolean {
        if (isRecording) return true
        if (!cacheDirectory.exists() && !cacheDirectory.mkdirs()) return false

        val sampleRate = SAMPLE_RATE
        val minBuffer = AudioRecord.getMinBufferSize(
            sampleRate,
            AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT,
        )
        if (minBuffer == AudioRecord.ERROR || minBuffer == AudioRecord.ERROR_BAD_VALUE) {
            Log.e(TAG, "AudioRecord.getMinBufferSize failed: $minBuffer")
            return false
        }
        val bufferSize = minBuffer * 2
        val audioRecord = try {
            AudioRecord(
                MediaRecorder.AudioSource.VOICE_RECOGNITION,
                sampleRate,
                AudioFormat.CHANNEL_IN_MONO,
                AudioFormat.ENCODING_PCM_16BIT,
                bufferSize,
            )
        } catch (_: SecurityException) {
            Log.e(TAG, "AudioRecord SecurityException")
            return false
        }
        if (audioRecord.state != AudioRecord.STATE_INITIALIZED) {
            Log.e(TAG, "AudioRecord not initialized, state=${audioRecord.state}")
            audioRecord.release()
            return false
        }

        val totalBytes = AtomicLong()
        discardCapture = false
        previousBoundary = null
        segmentDirectory = cacheDirectory
        val deliver: (ByteArray, SegmentBoundary) -> Unit = { pcm, boundary ->
            persistSegment(pcm, boundary)
        }
        handoff = if (vadModel != null && vadModel.isFile) {
            try {
                VadSegmentHandoff.forModel(vadModel, maxSegmentMs, SAMPLE_RATE, deliver)
            } catch (e: Throwable) {
                Log.e(TAG, "VAD unavailable, using fixed segments", e)
                PcmSegmentHandoff(maxSegmentMs, deliver)
            }
        } else {
            PcmSegmentHandoff(maxSegmentMs, deliver)
        }
        recorder = audioRecord
        isRecording = true
        try {
            audioRecord.startRecording()
        } catch (e: Exception) {
            isRecording = false
            handoff?.cancel()
            handoff = null
            recorder = null
            audioRecord.release()
            Log.e(TAG, "AudioRecord start failed", e)
            return false
        }
        recordingThread = Thread({
            val buffer = ByteArray(bufferSize)
            while (isRecording) {
                val read = audioRecord.read(buffer, 0, buffer.size)
                if (read > 0 && !discardCapture) {
                    try {
                        handoff?.append(buffer, 0, read)
                    } catch (e: Exception) {
                        Log.e(TAG, "Audio processing failed", e)
                        onError?.invoke("Audio processing failed")
                        break
                    } catch (e: LinkageError) {
                        Log.e(TAG, "Audio processing failed", e)
                        onError?.invoke("Audio processing failed")
                        break
                    }
                    val count = totalBytes.addAndGet(read.toLong())
                    onBytes?.invoke(count)
                } else if (read < 0 && isRecording) {
                    Log.e(TAG, "AudioRecord.read failed: $read")
                    onError?.invoke("Microphone recording failed")
                    break
                }
            }
        }, "WhisperType-AudioRecorder").also { it.start() }
        return true
    }

    /** Stops capture and emits the final partial segment, if it contains speech. */
    fun stop() {
        if (!isRecording) return
        isRecording = false
        stopAudioRecord()
        recordingThread?.join(2_000)
        recordingThread = null
        recorder?.release()
        recorder = null
        try {
            handoff?.finish()
        } catch (e: Exception) {
            Log.e(TAG, "Failed to finish audio processing", e)
            onError?.invoke("Audio processing failed")
        } catch (e: LinkageError) {
            Log.e(TAG, "Failed to finish audio processing", e)
            onError?.invoke("Audio processing failed")
        }
        handoff = null
        segmentDirectory = null
    }

    /** Discards the current PCM tail; already emitted files are owned by the session consumer. */
    fun cancel() {
        discardCapture = true
        isRecording = false
        stopAudioRecord()
        recordingThread?.join(2_000)
        recordingThread = null
        recorder?.release()
        recorder = null
        try {
            handoff?.cancel()
        } catch (e: Exception) {
            Log.e(TAG, "Failed to cancel audio processing", e)
        } catch (e: LinkageError) {
            Log.e(TAG, "Failed to cancel audio processing", e)
        }
        handoff = null
        segmentDirectory = null
    }

    private fun stopAudioRecord() {
        try {
            recorder?.stop()
        } catch (_: Exception) {
        }
    }

    private fun persistSegment(pcm: ByteArray, boundary: SegmentBoundary) {
        if (discardCapture || isSilent(pcm)) return
        val directory = segmentDirectory ?: return
        val wav = try {
            File.createTempFile("whisper_segment_", ".wav", directory)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to create audio segment", e)
            onError?.invoke("Could not save recorded audio")
            return
        }
        try {
            writeWav(wav, pcm)
        } catch (e: Exception) {
            wav.delete()
            Log.e(TAG, "Failed to persist audio segment", e)
            onError?.invoke("Could not save recorded audio")
            return
        }
        if (discardCapture) {
            wav.delete()
            return
        }
        // The boundary ends this part, so it describes where the next part starts.
        val file = SegmentFile(wav, startsAfterPause = previousBoundary == SegmentBoundary.Pause)
        previousBoundary = boundary
        try {
            onSegmentFile?.invoke(file)
        } catch (e: Exception) {
            wav.delete()
            Log.e(TAG, "Failed to hand off audio segment", e)
            onError?.invoke("Could not queue recorded audio")
        }
    }

    private fun isSilent(pcm: ByteArray): Boolean {
        if (pcm.size < 4) return true
        var sum = 0L
        var count = 0
        var i = 0
        while (i + 1 < pcm.size) {
            val sample = ((pcm[i + 1].toInt() shl 8) or (pcm[i].toInt() and 0xff)).toShort().toInt()
            sum += kotlin.math.abs(sample)
            count++
            i += 2
        }
        return count == 0 || sum / count < SILENCE_THRESHOLD
    }

    private fun writeWav(file: File, pcm: ByteArray) {
        val byteRate = SAMPLE_RATE * 2
        val header = ByteBuffer.allocate(44).order(ByteOrder.LITTLE_ENDIAN)
            .put("RIFF".toByteArray(Charsets.US_ASCII))
            .putInt(pcm.size + 36)
            .put("WAVE".toByteArray(Charsets.US_ASCII))
            .put("fmt ".toByteArray(Charsets.US_ASCII))
            .putInt(16)
            .putShort(1)
            .putShort(1)
            .putInt(SAMPLE_RATE)
            .putInt(byteRate)
            .putShort(2)
            .putShort(16)
            .put("data".toByteArray(Charsets.US_ASCII))
            .putInt(pcm.size)
            .array()
        FileOutputStream(file).use { out ->
            out.write(header)
            out.write(pcm)
        }
    }

    companion object {
        private const val TAG = "WhisperType"
        private const val SAMPLE_RATE = 16_000
        private const val SILENCE_THRESHOLD = 50
    }
}
