package me.trion.whispertype.voice

import ai.onnxruntime.OrtSession
import ai.onnxruntime.extensions.OrtxPackage
import android.content.Context
import android.net.Uri
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.security.MessageDigest
import java.util.concurrent.TimeUnit
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream

/**
 * Shared install pipeline used by both download and import: extract the zip
 * into a staging dir, verify required files, validate sessions, then swap in
 * via safeInstall. Never touches the live model dir until safeInstall, and
 * never changes prefs. The caller is responsible for setting model_source.
 */
internal fun installModelZip(
    zipFile: File,
    modelsDir: File,
    modelDir: File,
    sessionValidator: (File) -> Unit = {},
): ModelDownloader.Result {
    val staging = File(modelsDir, ModelCatalog.FOLDER_NAME + ".staging")
    try {
        staging.deleteRecursively()
        staging.mkdirs()
        try {
            ModelDownloader.extractZipFlat(zipFile, staging)
        } catch (e: Exception) {
            return ModelDownloader.Result.Error(ModelDownloader.MSG_IMPORT_READ)
        }

        val missing = ModelDownloader.verifyRequiredFiles(staging)
        if (missing.isNotEmpty()) {
            return ModelDownloader.Result.Error(ModelDownloader.MSG_IMPORT_INVALID)
        }

        try {
            sessionValidator(staging)
        } catch (e: Exception) {
            return ModelDownloader.Result.Error(ModelDownloader.MSG_IMPORT_LOAD)
        }

        return ModelDownloader.safeInstall(staging, modelDir).fold(
            onSuccess = { ModelDownloader.Result.Success },
            onFailure = { ModelDownloader.Result.Error(ModelDownloader.MSG_IMPORT_IO) }
        )
    } finally {
        if (staging.exists()) staging.deleteRecursively()
        zipFile.delete()
    }
}

class ModelDownloader(
    private val context: Context,
    private val sessionValidator: (File) -> Unit = Companion::validateSessions,
) {
    private val client = OkHttpClient.Builder()
        .connectTimeout(60, TimeUnit.SECONDS)
        .readTimeout(0, TimeUnit.SECONDS)
        .writeTimeout(60, TimeUnit.SECONDS)
        .build()

    fun modelsDir(): File = File(context.filesDir, "models").also { it.mkdirs() }
    fun modelDir(): File = File(modelsDir(), ModelCatalog.FOLDER_NAME)

    val initializerFile: File get() = File(modelDir(), "Whisper_initializer.onnx")
    val encoderFile: File get() = File(modelDir(), "Whisper_encoder.onnx")
    val decoderFile: File get() = File(modelDir(), "Whisper_decoder.onnx")
    val cacheInitFile: File get() = File(modelDir(), "Whisper_cache_initializer.onnx")
    val cacheInitBatchFile: File get() = File(modelDir(), "Whisper_cache_initializer_batch.onnx")
    val detokenizerFile: File get() = File(modelDir(), "Whisper_detokenizer.onnx")

    val requiredFiles: List<File> get() = listOf(
        initializerFile, encoderFile, decoderFile,
        cacheInitFile, cacheInitBatchFile, detokenizerFile
    )

    fun isInstalled(): Boolean = requiredFiles.all { it.isFile && it.length() > 0L }

    suspend fun download(onProgress: (downloaded: Long, total: Long) -> Unit): Result =
        withContext(Dispatchers.IO) {
            try {
                if (isInstalled()) return@withContext Result.AlreadyInstalled

                val tmpZip = File(modelsDir(), ModelCatalog.ARCHIVE_NAME + ".part")
                val request = Request.Builder().url(ModelCatalog.DOWNLOAD_URL).get().build()
                client.newCall(request).execute().use { response ->
                    if (!response.isSuccessful) return@withContext Result.Error("Download failed: HTTP ${response.code}")
                    val body = response.body ?: return@withContext Result.Error("Empty response")
                    val total = body.contentLength()
                    body.byteStream().use { input ->
                        FileOutputStream(tmpZip).use { out ->
                            val buffer = ByteArray(64 * 1024)
                            var totalRead = 0L
                            while (true) {
                                val read = input.read(buffer)
                                if (read < 0) break
                                out.write(buffer, 0, read)
                                totalRead += read
                                onProgress(totalRead, total)
                            }
                        }
                    }
                }

                val archiveHash = computeSha256(tmpZip)
                if (!archiveHash.equals(ModelCatalog.EXPECTED_SHA256, ignoreCase = true)) {
                    tmpZip.delete()
                    return@withContext Result.Error(
                        "Archive integrity check failed: expected ${ModelCatalog.EXPECTED_SHA256}, got $archiveHash"
                    )
                }

                installModelZip(tmpZip, modelsDir(), modelDir(), sessionValidator)
            } catch (e: Exception) {
                File(modelsDir(), ModelCatalog.ARCHIVE_NAME + ".part").delete()
                Result.Error(e.message ?: "Download failed")
            }
        }

    suspend fun importFromUri(uri: Uri, onProgress: (copied: Long, total: Long) -> Unit): Result =
        withContext(Dispatchers.IO) {
            val partFile = File(modelsDir(), "import.zip.part")
            try {
                val resolver = context.contentResolver
                val total = resolver.openAssetFileDescriptor(uri, "r")?.use { it.length } ?: -1L
                val input = try {
                    resolver.openInputStream(uri)
                        ?: return@withContext Result.Error(MSG_IMPORT_READ)
                } catch (e: Exception) {
                    partFile.delete()
                    return@withContext Result.Error(MSG_IMPORT_READ)
                }
                try {
                    input.use { stream ->
                        FileOutputStream(partFile).use { out ->
                            val buffer = ByteArray(64 * 1024)
                            var copied = 0L
                            while (true) {
                                val read = stream.read(buffer)
                                if (read < 0) break
                                out.write(buffer, 0, read)
                                copied += read
                                onProgress(copied, total)
                            }
                        }
                    }
                } catch (e: IOException) {
                    partFile.delete()
                    return@withContext Result.Error(MSG_IMPORT_IO)
                }
                installModelZip(partFile, modelsDir(), modelDir(), sessionValidator)
            } catch (e: Exception) {
                partFile.delete()
                Result.Error(MSG_IMPORT_READ)
            }
        }

    fun delete() {
        modelDir().deleteRecursively()
        File(modelsDir(), ModelCatalog.ARCHIVE_NAME + ".part").delete()
        File(modelsDir(), "import.zip.part").delete()
    }

    sealed class Result {
        data object Success : Result()
        data object AlreadyInstalled : Result()
        data class Error(val message: String) : Result()
    }

    companion object {
        val REQUIRED_BASENAMES = listOf(
            "Whisper_initializer.onnx",
            "Whisper_encoder.onnx",
            "Whisper_decoder.onnx",
            "Whisper_cache_initializer.onnx",
            "Whisper_cache_initializer_batch.onnx",
            "Whisper_detokenizer.onnx"
        )

        // The files WhisperEngine actually loads at runtime
        // (cache_initializer_batch is required for install but never loaded).
        val SESSION_BASENAMES = listOf(
            "Whisper_initializer.onnx",
            "Whisper_encoder.onnx",
            "Whisper_decoder.onnx",
            "Whisper_cache_initializer.onnx",
            "Whisper_detokenizer.onnx"
        )

        const val MSG_IMPORT_READ = "Could not read the selected file"
        const val MSG_IMPORT_INVALID = "Invalid model package (missing files)"
        const val MSG_IMPORT_LOAD = "Model files could not be loaded"
        const val MSG_IMPORT_IO = "Not enough space or write failed"

        fun computeSha256(file: File): String {
            val digest = MessageDigest.getInstance("SHA-256")
            file.inputStream().use { input ->
                val buffer = ByteArray(8192)
                while (true) {
                    val read = input.read(buffer)
                    if (read < 0) break
                    digest.update(buffer, 0, read)
                }
            }
            return digest.digest().joinToString("") { "%02x".format(it) }
        }

        fun verifyArchiveSha256(file: File, expectedHash: String): Boolean {
            return computeSha256(file).equals(expectedHash, ignoreCase = true)
        }

        /**
         * Opens the five runtime sessions exactly like WhisperEngine does
         * (including the custom-op library and encoder options) and closes
         * them all. Throws if any session fails to open.
         */
        fun validateSessions(dir: File) {
            val opts = OrtSession.SessionOptions().apply {
                registerCustomOpLibrary(OrtxPackage.getLibraryPath())
                setCPUArenaAllocator(false)
                setMemoryPatternOptimization(false)
                setOptimizationLevel(OrtSession.SessionOptions.OptLevel.NO_OPT)
            }
            val encOpts = OrtSession.SessionOptions().apply {
                registerCustomOpLibrary(OrtxPackage.getLibraryPath())
                setCPUArenaAllocator(false)
                setMemoryPatternOptimization(false)
                setSymbolicDimensionValue("batch_size", 1)
                setOptimizationLevel(OrtSession.SessionOptions.OptLevel.NO_OPT)
            }
            val opened = mutableListOf<OrtSession>()
            try {
                for (name in SESSION_BASENAMES) {
                    val fileOpts = if (name == "Whisper_encoder.onnx") encOpts else opts
                    opened.add(OnnxRuntime.loadSession(File(dir, name), fileOpts))
                }
            } finally {
                opened.forEach { session -> runCatching { session.close() } }
            }
        }

        fun extractZipFlat(zipFile: File, destDir: File) {
            val seenBasenames = mutableSetOf<String>()
            ZipInputStream(zipFile.inputStream().buffered()).use { zis ->
                var entry: ZipEntry? = zis.nextEntry
                while (entry != null) {
                    if (!entry!!.isDirectory) {
                        val basename = File(entry!!.name).name
                        if (basename.isBlank()) {
                            entry = zis.nextEntry
                            continue
                        }
                        if (!seenBasenames.add(basename)) {
                            throw IllegalArgumentException(
                                "Duplicate required basename in archive: $basename"
                            )
                        }
                        val outFile = File(destDir, basename)
                        FileOutputStream(outFile).use { out -> zis.copyTo(out) }
                    }
                    entry = zis.nextEntry
                }
            }
        }

        fun verifyRequiredFiles(dir: File): List<String> {
            return REQUIRED_BASENAMES.filter { name ->
                val f = File(dir, name)
                !f.isFile || f.length() == 0L
            }
        }

        fun safeInstall(stagingDir: File, targetDir: File): kotlin.Result<Unit> {
            val backupDir = File(targetDir.parentFile, targetDir.name + ".backup")
            var targetBackedUp = false
            try {
                if (!stagingDir.isDirectory) {
                    return kotlin.Result.failure(IllegalArgumentException("Staging dir is not a directory: $stagingDir"))
                }
                val missing = verifyRequiredFiles(stagingDir)
                if (missing.isNotEmpty()) {
                    return kotlin.Result.failure(
                        IllegalArgumentException("Staging dir missing required files: $missing")
                    )
                }
                backupDir.deleteRecursively()
                if (targetDir.exists()) {
                    if (!targetDir.renameTo(backupDir)) {
                        return kotlin.Result.failure(
                            IllegalStateException("Failed to back up existing model: $targetDir")
                        )
                    }
                    targetBackedUp = true
                }
                if (!stagingDir.renameTo(targetDir)) {
                    if (targetBackedUp && !backupDir.renameTo(targetDir)) {
                        backupDir.copyRecursively(targetDir, overwrite = true)
                        backupDir.deleteRecursively()
                    }
                    return kotlin.Result.failure(
                        IllegalStateException("Failed to rename staging to target: $stagingDir -> $targetDir")
                    )
                }
                backupDir.deleteRecursively()
                return kotlin.Result.success(Unit)
            } catch (e: Exception) {
                if (targetBackedUp && !targetDir.exists()) backupDir.renameTo(targetDir)
                return kotlin.Result.failure(e)
            } finally {
                if (stagingDir.exists()) {
                    stagingDir.deleteRecursively()
                }
            }
        }
    }
}
