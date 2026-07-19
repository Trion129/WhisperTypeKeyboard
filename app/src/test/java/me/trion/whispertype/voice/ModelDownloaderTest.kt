package me.trion.whispertype.voice

import org.junit.Assert.*
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.io.FileOutputStream
import java.security.MessageDigest
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

class ModelDownloaderTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    private lateinit var modelsDir: File
    private lateinit var modelDir: File

    @Before
    fun setUp() {
        modelsDir = tempFolder.newFolder("models")
        modelDir = File(modelsDir, ModelCatalog.FOLDER_NAME)
    }

    // SHA-256 verification

    @Test
    fun `computeSha256 returns correct hash for known content`() {
        val file = tempFolder.newFile("test.bin")
        file.writeBytes("hello world".toByteArray())
        val expected = MessageDigest.getInstance("SHA-256")
            .digest("hello world".toByteArray())
            .joinToString("") { "%02x".format(it) }
        assertEquals(expected, ModelDownloader.computeSha256(file))
    }

    @Test
    fun `verifyArchiveSha256 succeeds for matching hash`() {
        val file = tempFolder.newFile("archive.zip")
        file.writeBytes(byteArrayOf(1, 2, 3, 4))
        val hash = ModelDownloader.computeSha256(file)
        assertTrue(ModelDownloader.verifyArchiveSha256(file, hash))
    }

    @Test
    fun `verifyArchiveSha256 fails for mismatched hash`() {
        val file = tempFolder.newFile("archive.zip")
        file.writeBytes(byteArrayOf(1, 2, 3, 4))
        assertFalse(ModelDownloader.verifyArchiveSha256(file, "0".repeat(64)))
    }

    // Flat extraction

    @Test
    fun `extractZipFlat flattens directory structure`() {
        val zip = createZip(
            "subdir/file1.onnx" to "data1",
            "deep/nested/file2.onnx" to "data2"
        )
        val dest = tempFolder.newFolder("dest")
        ModelDownloader.extractZipFlat(zip, dest)

        assertTrue(File(dest, "file1.onnx").isFile)
        assertTrue(File(dest, "file2.onnx").isFile)
        assertEquals("data1", File(dest, "file1.onnx").readText())
        assertEquals("data2", File(dest, "file2.onnx").readText())
    }

    @Test
    fun `extractZipFlat rejects duplicate basenames`() {
        val zip = createZip(
            "a/file.onnx" to "first",
            "b/file.onnx" to "second"
        )
        val dest = tempFolder.newFolder("dest")
        assertThrows(IllegalArgumentException::class.java) {
            ModelDownloader.extractZipFlat(zip, dest)
        }
    }

    @Test
    fun `extractZipFlat skips directory entries`() {
        val zip = createZip(
            "dir/" to null,
            "dir/file.onnx" to "data"
        )
        val dest = tempFolder.newFolder("dest")
        ModelDownloader.extractZipFlat(zip, dest)
        assertTrue(File(dest, "file.onnx").isFile)
    }

    // Required files verification

    @Test
    fun `verifyRequiredFiles fails when files missing`() {
        modelDir.mkdirs()
        // Create only some required files
        File(modelDir, "Whisper_initializer.onnx").writeBytes(byteArrayOf(1))
        val missing = ModelDownloader.verifyRequiredFiles(modelDir)
        assertTrue("Should report missing files", missing.isNotEmpty())
    }

    @Test
    fun `verifyRequiredFiles succeeds when all present and non-empty`() {
        modelDir.mkdirs()
        for (name in ModelDownloader.REQUIRED_BASENAMES) {
            File(modelDir, name).writeBytes(byteArrayOf(1, 2, 3))
        }
        val missing = ModelDownloader.verifyRequiredFiles(modelDir)
        assertTrue("Should have no missing files", missing.isEmpty())
    }

    @Test
    fun `verifyRequiredFiles fails for empty files`() {
        modelDir.mkdirs()
        for (name in ModelDownloader.REQUIRED_BASENAMES) {
            File(modelDir, name).writeBytes(byteArrayOf())
        }
        val missing = ModelDownloader.verifyRequiredFiles(modelDir)
        assertTrue("Empty files should be reported as missing", missing.isNotEmpty())
    }

    // Safe install (atomic replacement)

    @Test
    fun `safeInstall replaces only after full verification`() {
        // Set up existing valid model
        modelDir.mkdirs()
        for (name in ModelDownloader.REQUIRED_BASENAMES) {
            File(modelDir, name).writeBytes(byteArrayOf(1))
        }
        val existingMarker = File(modelDir, "marker.txt")
        existingMarker.writeText("old")

        // Create staging dir with new valid model
        val staging = tempFolder.newFolder("staging")
        for (name in ModelDownloader.REQUIRED_BASENAMES) {
            File(staging, name).writeBytes(byteArrayOf(2))
        }

        val result = ModelDownloader.safeInstall(staging, modelDir)
        assertTrue("safeInstall should succeed", result.isSuccess)

        // Verify new content
        for (name in ModelDownloader.REQUIRED_BASENAMES) {
            assertEquals(2.toByte(), File(modelDir, name).readBytes()[0])
        }
        // Old marker should be gone (replaced)
        assertFalse("Old files should be cleaned", existingMarker.exists())
    }

    @Test
    fun `safeInstall preserves old model when staging is invalid`() {
        // Set up existing valid model
        modelDir.mkdirs()
        for (name in ModelDownloader.REQUIRED_BASENAMES) {
            File(modelDir, name).writeBytes(byteArrayOf(1))
        }

        // Create staging dir with incomplete model
        val staging = tempFolder.newFolder("staging")
        File(staging, "Whisper_initializer.onnx").writeBytes(byteArrayOf(2))

        val result = ModelDownloader.safeInstall(staging, modelDir)
        assertTrue("safeInstall should fail", result.isFailure)

        // Verify old model is intact
        for (name in ModelDownloader.REQUIRED_BASENAMES) {
            assertEquals(1.toByte(), File(modelDir, name).readBytes()[0])
        }
    }

    @Test
    fun `safeInstall cleans staging dir on failure`() {
        modelDir.mkdirs()
        for (name in ModelDownloader.REQUIRED_BASENAMES) {
            File(modelDir, name).writeBytes(byteArrayOf(1))
        }

        val staging = tempFolder.newFolder("staging")
        File(staging, "Whisper_initializer.onnx").writeBytes(byteArrayOf(2))

        ModelDownloader.safeInstall(staging, modelDir)
        assertFalse("Staging dir should be cleaned up", staging.exists())
    }

    @Test
    fun `safeInstall cleans temp dir on exception`() {
        modelDir.mkdirs()
        for (name in ModelDownloader.REQUIRED_BASENAMES) {
            File(modelDir, name).writeBytes(byteArrayOf(1))
        }

        // Pass a non-directory as staging to trigger an error
        val badStaging = tempFolder.newFile("notadir")
        val result = ModelDownloader.safeInstall(badStaging, modelDir)
        assertTrue("Should fail gracefully", result.isFailure)
    }

    // Helpers

    private fun createZip(vararg entries: Pair<String, String?>): File {
        val zip = tempFolder.newFile("test.zip")
        ZipOutputStream(FileOutputStream(zip)).use { zos ->
            for ((name, content) in entries) {
                zos.putNextEntry(ZipEntry(name))
                if (content != null) {
                    zos.write(content.toByteArray())
                }
                zos.closeEntry()
            }
        }
        return zip
    }
}
