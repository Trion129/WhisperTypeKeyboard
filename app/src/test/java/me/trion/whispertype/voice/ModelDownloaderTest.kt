package me.trion.whispertype.voice

import org.junit.Assert.*
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.io.FileOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

class ModelDownloaderTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    private lateinit var modelsDir: File

    @Before
    fun setUp() {
        modelsDir = tempFolder.newFolder("models")
    }

    private fun writeCatalogInstall(id: String) {
        val dir = File(modelsDir, id).apply { mkdirs() }
        val names = ModelDownloader.requiredFileNames(id)
        names.forEach { File(dir, it).writeBytes(byteArrayOf(1)) }
    }

    // Install layout

    @Test
    fun `catalog install is not installed when a file is missing`() {
        val dir = File(modelsDir, "tiny.en").apply { mkdirs() }
        File(dir, "tiny.en-encoder.int8.onnx").writeBytes(byteArrayOf(1))
        File(dir, "tiny.en-decoder.int8.onnx").writeBytes(byteArrayOf(1))
        // tokens.txt missing
        assertNull(ModelDownloader.resolvePathsIn(modelsDir, "tiny.en"))
        assertFalse(ModelDownloader.isInstalledDir(modelsDir, "tiny.en"))
    }

    @Test
    fun `catalog install is not installed when a file is empty`() {
        val dir = File(modelsDir, "base.en").apply { mkdirs() }
        File(dir, "base.en-encoder.int8.onnx").writeBytes(byteArrayOf(1))
        File(dir, "base.en-decoder.int8.onnx").writeBytes(byteArrayOf())
        File(dir, "base.en-tokens.txt").writeBytes(byteArrayOf(1))
        assertNull(ModelDownloader.resolvePathsIn(modelsDir, "base.en"))
    }

    @Test
    fun `complete catalog install resolves all three paths`() {
        writeCatalogInstall("small.en")
        val paths = ModelDownloader.resolvePathsIn(modelsDir, "small.en")
        assertNotNull(paths)
        val (encoder, decoder, tokens) = paths!!
        assertEquals("small.en-encoder.int8.onnx", encoder.name)
        assertEquals("small.en-decoder.int8.onnx", decoder.name)
        assertEquals("small.en-tokens.txt", tokens.name)
    }

    @Test
    fun `every catalog model resolves its three required files`() {
        for (spec in ModelCatalog.entries) {
            writeCatalogInstall(spec.id)
            assertNotNull("missing files for ${spec.id}", ModelDownloader.resolvePathsIn(modelsDir, spec.id))
        }
    }

    @Test
    fun `import detection recognizes multilingual catalog prefixes`() {
        assertEquals("tiny", ModelDownloader.detectCatalogId("tiny-encoder.int8.onnx"))
        assertEquals("base", ModelDownloader.detectCatalogId("base-encoder.int8.onnx"))
        assertEquals("small", ModelDownloader.detectCatalogId("small-encoder.int8.onnx"))
    }

    @Test
    fun `unknown id never resolves`() {
        writeCatalogInstall("tiny.en")
        assertNull(ModelDownloader.resolvePathsIn(modelsDir, "nope"))
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

    // Import

    @Test
    fun `import zip with catalog-prefixed files installs to that model id`() {
        val zip = createZip(
            "tiny.en-encoder.int8.onnx" to "enc",
            "tiny.en-decoder.int8.onnx" to "dec",
            "tiny.en-tokens.txt" to "tok"
        )
        val result = installImportZip(zip, modelsDir)
        assertTrue("import should succeed", result is ModelDownloader.Result.Success)
        assertEquals("tiny.en", (result as ModelDownloader.Result.Success).modelId)
        assertNotNull(ModelDownloader.resolvePathsIn(modelsDir, "tiny.en"))
        assertFalse("part zip should be cleaned up", zip.exists())
    }

    @Test
    fun `import zip without catalog prefix installs to import slot`() {
        val zip = createZip(
            "my-model-encoder.int8.onnx" to "enc",
            "my-model-decoder.int8.onnx" to "dec",
            "my-model-tokens.txt" to "tok"
        )
        val result = installImportZip(zip, modelsDir)
        assertTrue("import should succeed", result is ModelDownloader.Result.Success)
        assertEquals(
            ModelCatalog.IMPORT_ID,
            (result as ModelDownloader.Result.Success).modelId
        )
        assertNotNull(ModelDownloader.resolvePathsIn(modelsDir, ModelCatalog.IMPORT_ID))
    }

    @Test
    fun `import accepts non-int8 encoder and decoder names`() {
        val zip = createZip(
            "model-encoder.onnx" to "enc",
            "model-decoder.onnx" to "dec",
            "model-tokens.txt" to "tok"
        )
        val result = installImportZip(zip, modelsDir)
        assertTrue("import should succeed", result is ModelDownloader.Result.Success)
        assertEquals(ModelCatalog.IMPORT_ID, (result as ModelDownloader.Result.Success).modelId)
        assertNotNull(ModelDownloader.resolvePathsIn(modelsDir, ModelCatalog.IMPORT_ID))
    }

    @Test
    fun `incomplete zip fails and does not delete other model dirs`() {
        writeCatalogInstall("tiny.en")

        val zip = createZip("tiny.en-encoder.int8.onnx" to "only-encoder")
        val result = installImportZip(zip, modelsDir)
        assertTrue("incomplete zip should fail", result is ModelDownloader.Result.Error)

        // The previously installed model is untouched.
        assertNotNull(ModelDownloader.resolvePathsIn(modelsDir, "tiny.en"))
        assertEquals(
            1.toByte(),
            File(File(modelsDir, "tiny.en"), "tiny.en-encoder.int8.onnx").readBytes()[0]
        )
    }

    @Test
    fun `failed import keeps existing install of the same id intact`() {
        writeCatalogInstall("base.en")
        val zip = createZip("base.en-encoder.int8.onnx" to "only")
        val result = installImportZip(zip, modelsDir)
        assertTrue(result is ModelDownloader.Result.Error)
        assertNotNull(ModelDownloader.resolvePathsIn(modelsDir, "base.en"))
    }

    @Test
    fun `resolvePaths for import prefers int8 encoder over plain onnx`() {
        val dir = File(modelsDir, ModelCatalog.IMPORT_ID).apply { mkdirs() }
        File(dir, "model-encoder.onnx").writeBytes(byteArrayOf(1))
        File(dir, "model-encoder.int8.onnx").writeBytes(byteArrayOf(2))
        File(dir, "model-decoder.int8.onnx").writeBytes(byteArrayOf(1))
        File(dir, "model-tokens.txt").writeBytes(byteArrayOf(1))
        val paths = ModelDownloader.resolvePathsIn(modelsDir, ModelCatalog.IMPORT_ID)
        assertNotNull(paths)
        assertEquals("model-encoder.int8.onnx", paths!!.first.name)
    }

    // Legacy purge

    @Test
    fun `purgeLegacyInstall removes the legacy RTranslator folder`() {
        val legacy = File(modelsDir, ModelCatalog.LEGACY_FOLDER).apply { mkdirs() }
        File(legacy, "Whisper_encoder.onnx").writeBytes(byteArrayOf(1))
        File(legacy, "Whisper_decoder.onnx").writeBytes(byteArrayOf(1))

        ModelDownloader.purgeLegacyDir(modelsDir)
        assertFalse("Legacy folder should be gone", legacy.exists())
    }

    @Test
    fun `purgeLegacyInstall is a no-op when the folder is absent`() {
        ModelDownloader.purgeLegacyDir(modelsDir)
        assertTrue(modelsDir.exists())
    }

    // Safe install

    @Test
    fun `safeInstall replaces existing install only after verification`() {
        val target = File(modelsDir, "base.en")
        writeCatalogInstall("base.en")
        File(target, "marker.txt").writeText("old")

        val staging = tempFolder.newFolder("staging")
        val names = ModelDownloader.requiredFileNames("base.en")
        names.forEach { File(staging, it).writeBytes(byteArrayOf(2)) }

        val result = ModelDownloader.safeInstall(staging, target, names)
        assertTrue("safeInstall should succeed", result.isSuccess)
        assertFalse("old marker should be gone", File(target, "marker.txt").exists())
        assertEquals(2.toByte(), File(target, names[0]).readBytes()[0])
    }

    @Test
    fun `safeInstall preserves old model when staging is incomplete`() {
        val target = File(modelsDir, "base.en")
        writeCatalogInstall("base.en")

        val staging = tempFolder.newFolder("staging")
        val names = ModelDownloader.requiredFileNames("base.en")
        File(staging, names[0]).writeBytes(byteArrayOf(2))

        val result = ModelDownloader.safeInstall(staging, target, names)
        assertTrue("safeInstall should fail", result.isFailure)
        assertNotNull(ModelDownloader.resolvePathsIn(modelsDir, "base.en"))
        assertEquals(1.toByte(), File(target, names[0]).readBytes()[0])
    }

    @Test
    fun `safeInstall cleans staging dir on failure`() {
        val target = File(modelsDir, "base.en")
        writeCatalogInstall("base.en")

        val staging = tempFolder.newFolder("staging")
        val names = ModelDownloader.requiredFileNames("base.en")
        File(staging, names[0]).writeBytes(byteArrayOf(2))

        ModelDownloader.safeInstall(staging, target, names)
        assertFalse("staging dir should be cleaned up", staging.exists())
    }

    @Test
    fun `safeInstall fails gracefully on non-directory staging`() {
        val target = File(modelsDir, "base.en")
        writeCatalogInstall("base.en")

        val badStaging = tempFolder.newFile("notadir")
        val names = ModelDownloader.requiredFileNames("base.en")
        val result = ModelDownloader.safeInstall(badStaging, target, names)
        assertTrue("should fail gracefully", result.isFailure)
        assertNotNull(ModelDownloader.resolvePathsIn(modelsDir, "base.en"))
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
