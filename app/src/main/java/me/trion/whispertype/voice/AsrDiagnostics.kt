package me.trion.whispertype.voice

import android.content.ContentValues
import android.content.Context
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import android.provider.Settings
import android.util.Log
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * TEMPORARY, LOCAL-ONLY diagnostics for the ASR quality baseline
 * (plan step 1.3, see docs/quality/plan-status.md). REMOVE BEFORE RELEASE.
 *
 * Switched on only from adb, never from the UI:
 *
 * ```
 * adb shell settings put global whispertype_diagnostics 1    # on
 * adb shell settings delete global whispertype_diagnostics   # off
 * ```
 *
 * While enabled, one dictation produces:
 *  - a logcat line (tag `WhisperTypeDiag`) with capture metrics (duration,
 *    RMS, peak, clipping, silence, speech extents), the effective model and
 *    language, and the decoder trace (token counts, cap, retry, timings);
 *  - a copy of the recorded wav in `Download/WhisperTypeDiagnostics/` so the
 *    exact same audio can be replayed through bench/harness.py.
 *
 * No transcription text is ever written or logged, nothing leaves the
 * device, and disabled (the default) the whole path is inert.
 */
object AsrDiagnostics {
    private const val TAG = "WhisperTypeDiag"
    private const val SETTING = "whispertype_diagnostics"
    private const val CLIP_THRESHOLD = (32767 - 2).toShort()
    private const val SILENCE_THRESHOLD = 50.0 / 32768.0
    private const val VOICED_THRESHOLD = 0.01

    fun enabled(context: Context): Boolean =
        runCatching {
            Settings.Global.getInt(context.contentResolver, SETTING, 0) == 1
        }.getOrDefault(false)

    /**
     * Called by [LocalAsrEngine.transcribeWav] right after the wav is written:
     * logs capture metrics + effective model/language and copies the
     * recording to public storage for later desktop replay.
     */
    fun onRecordingCaptured(context: Context, wavFile: File, modelId: String?, language: String?) {
        runCatching {
            val metrics = pcm16Metrics(wavFile)
            Log.i(TAG, "capture file=${wavFile.name} model=$modelId language=$language $metrics")
            copyRecording(context, wavFile)
        }
    }

    /** Trace sink for [SherpaWhisperEngine.onTrace]. */
    fun onEngineTrace(trace: SherpaWhisperEngine.Trace) {
        Log.i(
            TAG,
            "decode samples=${trace.sampleCount} firstPassTokens=${trace.firstPassTokens} " +
                "tokenCap=${trace.tokenCap} hitTokenCap=${trace.hitTokenCap} " +
                "retriedPadded=${trace.retriedPadded} firstDecodeMs=${trace.firstDecodeMs} " +
                "retryDecodeMs=${trace.retryDecodeMs}"
        )
    }

    /** Capture-quality metrics of a 16-bit PCM wav. No text involved. */
    fun pcm16Metrics(file: File): Metrics {
        val bytes = file.readBytes()
        val buffer = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
        require(bytes.size >= 12 && String(bytes, 0, 4, Charsets.US_ASCII) == "RIFF") {
            "Not a RIFF file"
        }
        var data: ByteArray? = null
        var sampleRate = 0
        var channels = 1
        var pos = 12
        while (pos + 8 <= bytes.size) {
            val id = String(bytes, pos, 4, Charsets.US_ASCII)
            val size = buffer.getInt(pos + 4)
            when (id) {
                "fmt " -> {
                    channels = buffer.getShort(pos + 10).toInt()
                    sampleRate = buffer.getInt(pos + 12)
                }
                "data" -> data = bytes.copyOfRange(pos + 8, (pos + 8 + size).coerceAtMost(bytes.size))
            }
            pos += 8 + size + (size and 1)
        }
        val pcm = requireNotNull(data) { "No data chunk" }
        val shorts = ShortArray(pcm.size / 2)
        ByteBuffer.wrap(pcm).order(ByteOrder.LITTLE_ENDIAN).asShortBuffer().get(shorts)

        var sumSquares = 0.0
        var peak = 0
        var clipped = 0
        var silent = 0
        var firstVoiced = -1
        var lastVoiced = -1
        for (i in shorts.indices) {
            val v = shorts[i].toInt()
            val a = kotlin.math.abs(v)
            val aFloat = a / 32768.0
            sumSquares += aFloat * aFloat
            if (a > peak) peak = a
            if (a >= CLIP_THRESHOLD) clipped++
            if (aFloat * 32768.0 < 2.0) silent++
            if (aFloat > VOICED_THRESHOLD) {
                if (firstVoiced < 0) firstVoiced = i
                lastVoiced = i
            }
        }
        val n = shorts.size.coerceAtLeast(1)
        return Metrics(
            durationS = shorts.size / sampleRate.toDouble().coerceAtLeast(1.0),
            rms = kotlin.math.sqrt(sumSquares / n),
            peak = peak / 32768.0,
            clippingRatio = clipped / n.toDouble(),
            silenceRatio = silent / n.toDouble(),
            firstVoicedS = if (firstVoiced < 0) null else firstVoiced / sampleRate.toDouble(),
            lastVoicedS = if (lastVoiced < 0) null else lastVoiced / sampleRate.toDouble(),
            sampleRate = sampleRate,
            channels = channels,
        )
    }

    data class Metrics(
        val durationS: Double,
        val rms: Double,
        val peak: Double,
        val clippingRatio: Double,
        val silenceRatio: Double,
        val firstVoicedS: Double?,
        val lastVoicedS: Double?,
        val sampleRate: Int,
        val channels: Int,
    ) {
        override fun toString(): String =
            "duration=${"%.2f".format(durationS)}s rms=${"%.4f".format(rms)} " +
                "peak=${"%.4f".format(peak)} clip=${"%.5f".format(clippingRatio)} " +
                "silence=${"%.3f".format(silenceRatio)} " +
                "speech=${firstVoicedS?.let { "%.2f".format(it) }}..${lastVoicedS?.let { "%.2f".format(it) }}s " +
                "sr=$sampleRate ch=$channels"
    }

    /** Copies the wav to Download/WhisperTypeDiagnostics/ (no permission needed). */
    private fun copyRecording(context: Context, wavFile: File) {
        // MediaStore scoped-storage writes (RELATIVE_PATH, IS_PENDING) are
        // API 29+. Below that the capture stays log-only, which is fine: the
        // diagnostics path targets the Android 13 phone.
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return
        val values = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, wavFile.name)
            put(MediaStore.MediaColumns.MIME_TYPE, "audio/wav")
            put(
                MediaStore.MediaColumns.RELATIVE_PATH,
                Environment.DIRECTORY_DOWNLOADS + "/WhisperTypeDiagnostics"
            )
            put(MediaStore.MediaColumns.IS_PENDING, 1)
        }
        val resolver = context.contentResolver
        val uri = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values) ?: return
        try {
            resolver.openOutputStream(uri)?.use { out ->
                wavFile.inputStream().use { it.copyTo(out) }
            }
            resolver.update(
                uri,
                ContentValues().apply { put(MediaStore.MediaColumns.IS_PENDING, 0) },
                null,
                null
            )
        } catch (e: Exception) {
            resolver.delete(uri, null, null)
        }
    }
}
