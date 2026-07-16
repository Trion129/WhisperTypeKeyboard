package me.trion.whispertype.voice

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.apache.commons.compress.archivers.tar.TarArchiveInputStream
import org.apache.commons.compress.compressors.bzip2.BZip2CompressorInputStream
import java.io.BufferedInputStream
import java.io.File
import java.io.FileOutputStream
import java.util.concurrent.TimeUnit

class ModelDownloader(private val context: Context) {
    private val client = OkHttpClient.Builder()
        .connectTimeout(60, TimeUnit.SECONDS)
        .readTimeout(0, TimeUnit.SECONDS)
        .writeTimeout(60, TimeUnit.SECONDS)
        .build()

    fun modelsDir(): File = File(context.filesDir, "models").also { it.mkdirs() }

    fun modelFolder(model: AsrModel): File = File(modelsDir(), model.folderName)

    fun isInstalled(model: AsrModel): Boolean {
        val folder = modelFolder(model)
        if (!folder.isDirectory) return false
        return when (model.kind) {
            AsrModel.Kind.WHISPER -> {
                val p = model.whisperPrefix
                File(folder, "$p-encoder.int8.onnx").exists() &&
                    File(folder, "$p-decoder.int8.onnx").exists() &&
                    File(folder, "$p-tokens.txt").exists()
            }
            AsrModel.Kind.PARAKEET -> {
                (File(folder, "model.int8.onnx").exists() || File(folder, "model.onnx").exists()) &&
                    File(folder, "tokens.txt").exists()
            }
        }
    }

    suspend fun download(
        model: AsrModel,
        onProgress: (downloaded: Long, total: Long) -> Unit
    ): Result = withContext(Dispatchers.IO) {
        try {
            if (isInstalled(model)) return@withContext Result.AlreadyInstalled

            val tmpArchive = File(modelsDir(), model.archiveName + ".part")
            val request = Request.Builder().url(model.downloadUrl).get().build()
            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) {
                    return@withContext Result.Error("Download failed: HTTP ${response.code}")
                }
                val body = response.body ?: return@withContext Result.Error("Empty response")
                val total = body.contentLength()
                body.byteStream().use { input ->
                    FileOutputStream(tmpArchive).use { out ->
                        val buffer = ByteArray(DEFAULT_BUFFER)
                        var read: Int
                        var sum = 0L
                        while (input.read(buffer).also { read = it } != -1) {
                            out.write(buffer, 0, read)
                            sum += read
                            onProgress(sum, total)
                        }
                    }
                }
            }

            extractTarBz2(tmpArchive, modelsDir())
            tmpArchive.delete()

            if (!isInstalled(model)) {
                return@withContext Result.Error("Model files missing after extract")
            }
            pruneExtraFiles(modelFolder(model))
            Result.Success
        } catch (e: Exception) {
            Result.Error(e.message ?: "Download failed")
        }
    }

    fun delete(model: AsrModel) {
        modelFolder(model).deleteRecursively()
        File(modelsDir(), model.archiveName).delete()
        File(modelsDir(), model.archiveName + ".part").delete()
    }

    private fun extractTarBz2(archive: File, destDir: File) {
        BufferedInputStream(archive.inputStream()).use { fileIn ->
            BZip2CompressorInputStream(fileIn).use { bzIn ->
                TarArchiveInputStream(bzIn).use { tarIn ->
                    var entry = tarIn.nextEntry
                    while (entry != null) {
                        val outFile = File(destDir, entry.name)
                        if (entry.isDirectory) {
                            outFile.mkdirs()
                        } else {
                            outFile.parentFile?.mkdirs()
                            FileOutputStream(outFile).use { out ->
                                tarIn.copyTo(out)
                            }
                        }
                        entry = tarIn.nextEntry
                    }
                }
            }
        }
    }

    private fun pruneExtraFiles(folder: File) {
        folder.listFiles()?.forEach { f ->
            val n = f.name
            if (n.endsWith(".onnx") && !n.contains(".int8.") &&
                File(folder, n.replace(".onnx", ".int8.onnx")).exists()
            ) {
                f.delete()
            }
            if (n == "test_wavs" || n.endsWith(".sh") || n == "README.md") {
                f.deleteRecursively()
            }
        }
    }

    sealed class Result {
        data object Success : Result()
        data object AlreadyInstalled : Result()
        data class Error(val message: String) : Result()
    }

    companion object {
        private const val DEFAULT_BUFFER = 64 * 1024
    }
}
