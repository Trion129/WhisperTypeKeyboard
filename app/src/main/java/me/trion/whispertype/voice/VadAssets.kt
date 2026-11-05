package me.trion.whispertype.voice

import android.content.Context
import android.util.Log
import java.io.File
import java.io.FileOutputStream

/** Copies the bundled Silero VAD model to app storage once. */
object VadAssets {
    const val ASSET_PATH = "vad/silero_vad.onnx"
    const val SIZE_BYTES = 643_854L
    private const val FILE_NAME = "silero_vad.onnx"
    private const val TAG = "WhisperType"

    /**
     * Returns the model file, or null when the asset is missing or the copy
     * fails. Callers fall back to fixed-size segments on null.
     */
    fun ensureInstalled(context: Context): File? {
        val app = context.applicationContext
        val directory = File(app.filesDir, "vad")
        val file = File(directory, FILE_NAME)
        if (file.isFile && file.length() == SIZE_BYTES) return file
        return try {
            if (!directory.isDirectory && !directory.mkdirs()) return null
            app.assets.open(ASSET_PATH).use { input ->
                FileOutputStream(file).use { output -> input.copyTo(output, 64 * 1024) }
            }
            file.takeIf { it.length() == SIZE_BYTES }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to install the VAD model", e)
            null
        }
    }
}
