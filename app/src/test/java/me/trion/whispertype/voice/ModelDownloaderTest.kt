package me.trion.whispertype.voice

import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.io.FileOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

class ModelDownloaderTest {
    @get:Rule val temp = TemporaryFolder()
    private val models get() = File(temp.root, "models").apply { mkdirs() }

    @Test
    fun `legacy catalog files migrate without replacing weights and receive generation`() {
        val spec = ModelCatalog.byId("tiny.en")!!
        val dir = File(models, spec.id).apply { mkdirs() }
        spec.layout.requiredPaths.forEach { File(dir, it).apply { parentFile?.mkdirs(); writeText("legacy:$it") } }
        val before = spec.layout.requiredPaths.associateWith { File(dir, it).readBytes().toList() }

        val resolved = ModelDownloader.resolveModelIn(models, spec.id)

        assertNotNull(resolved)
        val downloaded = File(models, "downloaded-${spec.id}")
        assertTrue(File(downloaded, ModelManifest.FILE_NAME).isFile)
        assertFalse(dir.exists())
        assertEquals(before, spec.layout.requiredPaths.associateWith { File(downloaded, it).readBytes().toList() })
        assertEquals(spec.id, resolved!!.spec.id)
        assertTrue(resolved.generation.isNotBlank())
        assertEquals(resolved.generation, ModelDownloader.resolveModelIn(models, spec.id)!!.generation)
    }

    @Test
    fun `multilingual legacy migration round trips the auto language code`() {
        val spec = ModelCatalog.byId("base")!!
        val dir = File(models, spec.id).apply { mkdirs() }
        spec.layout.requiredPaths.forEach { File(dir, it).apply { parentFile?.mkdirs(); writeText(it) } }

        val first = ModelDownloader.resolveModelIn(models, spec.id)!!
        val second = ModelDownloader.resolveModelIn(models, spec.id)!!

        val policy = second.spec.languagePolicy as LanguagePolicy.Selectable
        assertTrue(policy.codes.contains(""))
        assertEquals(first.generation, second.generation)
    }

    @Test
    fun `catalog-prefixed legacy import keeps import identity across repeated resolution`() {
        val dir = File(models, ModelCatalog.IMPORT_ID).apply { mkdirs() }
        File(dir, "base-encoder.int8.onnx").writeText("encoder")
        File(dir, "base-decoder.int8.onnx").writeText("decoder")
        File(dir, "base-tokens.txt").writeText("tokens")

        val first = ModelDownloader.resolveModelIn(models, ModelCatalog.IMPORT_ID)
        val second = ModelDownloader.resolveModelIn(models, ModelCatalog.IMPORT_ID)

        assertEquals(ModelCatalog.IMPORT_ID, first?.spec?.id)
        assertEquals(ModelCatalog.IMPORT_ID, second?.spec?.id)
        assertEquals(first?.generation, second?.generation)
    }

    @Test
    fun `legacy catalog directory migrates to downloaded storage without changing weights`() {
        val spec = ModelCatalog.byId("tiny.en")!!
        val legacy = File(models, spec.id).apply { mkdirs() }
        spec.layout.requiredPaths.forEach { File(legacy, it).apply { parentFile?.mkdirs(); writeText("legacy:$it") } }
        val before = spec.layout.requiredPaths.associateWith { File(legacy, it).readBytes().toList() }

        val resolved = ModelDownloader.resolveModelIn(models, spec.id)
        val downloaded = File(models, "downloaded-${spec.id}")

        assertNotNull(resolved)
        assertEquals(downloaded.canonicalFile, resolved!!.directory.canonicalFile)
        assertFalse(legacy.exists())
        assertEquals(before, spec.layout.requiredPaths.associateWith { File(downloaded, it).readBytes().toList() })
        assertEquals(spec.id, resolved.spec.id)
    }
    @Test
    fun `nested manifest import preserves sidecars and paths`() {
        val zip = zip(
            "bundle/model.json" to manifestJson(
                backend = "nemo_ctc",
                model = "graphs/model.int8.onnx",
                tokens = "assets/tokens.txt",
                artifacts = listOf("graphs/model.int8.onnx", "assets/tokens.txt", "graphs/model.onnx.data"),
            ),
            "bundle/graphs/model.int8.onnx" to "onnx",
            "bundle/assets/tokens.txt" to "tokens",
            "bundle/graphs/model.onnx.data" to "external weights",
        )

        val result = ModelDownloader.installImportZip(zip, models)

        assertEquals(ModelDownloader.Result.Success(ModelCatalog.IMPORT_ID), result)
        val installed = ModelDownloader.resolveModelIn(models, ModelCatalog.IMPORT_ID)!!
        assertTrue(installed.spec.layout is ModelLayout.NemoCtc)
        assertEquals("graphs/model.int8.onnx", (installed.spec.layout as ModelLayout.NemoCtc).model)
        assertEquals("external weights", File(installed.directory, "graphs/model.onnx.data").readText())
        assertTrue(File(installed.directory, ModelManifest.FILE_NAME).isFile)
    }

    @Test
    fun `catalog-prefixed whisper zip installs only into import slot`() {
        val catalogDir = File(models, "tiny")
        val downloadedDir = File(models, "downloaded-tiny")
        val zip = zip(
            "tiny-encoder.int8.onnx" to "encoder",
            "tiny-decoder.int8.onnx" to "decoder",
            "tiny-tokens.txt" to "tokens",
        )

        val result = ModelDownloader.installImportZip(zip, models)

        assertEquals(ModelDownloader.Result.Success(ModelCatalog.IMPORT_ID), result)
        assertEquals(ModelCatalog.IMPORT_ID, ModelDownloader.resolveModelIn(models, ModelCatalog.IMPORT_ID)?.spec?.id)
        assertFalse(catalogDir.exists())
        assertFalse(downloadedDir.exists())
    }

    @Test
    fun `Finder metadata entries are ignored during import`() {
        val zip = zip(
            "encoder.onnx" to "encoder",
            "decoder.onnx" to "decoder",
            "tokens.txt" to "tokens",
            "__MACOSX/._encoder.onnx" to "appledouble",
            "._encoder.onnx" to "appledouble",
            ".DS_Store" to "finder metadata",
        )

        val result = ModelDownloader.installImportZip(zip, models)

        assertEquals(ModelDownloader.Result.Success(ModelCatalog.IMPORT_ID), result)
        val installed = ModelDownloader.resolveModelIn(models, ModelCatalog.IMPORT_ID)!!
        assertEquals("encoder", File(installed.directory, "encoder.onnx").readText())
        assertFalse(File(installed.directory, "._encoder.onnx").exists())
        assertFalse(File(installed.directory, ".DS_Store").exists())
    }

    @Test
    fun `invalid replacement preserves the prior imported package`() {
        val good = zip(
            "model.json" to manifestJson("nemo_ctc", "model.onnx", "tokens.txt", listOf("model.onnx", "tokens.txt")),
            "model.onnx" to "old graph",
            "tokens.txt" to "old tokens",
        )
        assertEquals(ModelDownloader.Result.Success(ModelCatalog.IMPORT_ID), ModelDownloader.installImportZip(good, models))
        val old = File(models, ModelCatalog.IMPORT_ID).walkTopDown().filter { it.isFile }
            .associate { it.relativeTo(File(models, ModelCatalog.IMPORT_ID)).invariantSeparatorsPath to it.readBytes().toList() }

        val bad = zip(
            "model.json" to manifestJson("nemo_ctc", "../escape.onnx", "tokens.txt", listOf("../escape.onnx", "tokens.txt")),
            "tokens.txt" to "new tokens",
        )
        val result = ModelDownloader.installImportZip(bad, models)

        assertTrue(result is ModelDownloader.Result.Error)
        assertEquals(old, File(models, ModelCatalog.IMPORT_ID).walkTopDown().filter { it.isFile }
            .associate { it.relativeTo(File(models, ModelCatalog.IMPORT_ID)).invariantSeparatorsPath to it.readBytes().toList() })
    }

    @Test
    fun `unsupported backend returns explicit error without activation`() {
        val zip = zip("model.json" to """{"version":1,"title":"x","backend":"transducer"}""")
        val result = ModelDownloader.installImportZip(zip, models)
        assertTrue(result is ModelDownloader.Result.Error)
        assertFalse(File(models, ModelCatalog.IMPORT_ID).exists())
    }

    @Test
    fun `manifestless import accepts only unambiguous whisper and never guesses ctc`() {
        val ambiguous = zip(
            "a-encoder.onnx" to "enc1", "b-encoder.onnx" to "enc2",
            "model-decoder.onnx" to "dec", "tokens.txt" to "tokens",
        )
        assertTrue(ModelDownloader.installImportZip(ambiguous, models) is ModelDownloader.Result.Error)

        val ctc = zip("model.int8.onnx" to "graph", "tokens.txt" to "tokens")
        assertTrue(ModelDownloader.installImportZip(ctc, models) is ModelDownloader.Result.Error)
        assertFalse(File(models, ModelCatalog.IMPORT_ID).exists())
    }

    @Test
    fun `manifestless whisper migration derives only metadata-supported language policy`() {
        val dir = File(models, ModelCatalog.IMPORT_ID).apply { mkdirs() }
        File(dir, "nested/encoder.onnx").apply { parentFile?.mkdirs(); writeText("onnx") }
        File(dir, "nested/decoder.onnx").writeText("decoder")
        File(dir, "nested/tokens.txt").writeText("tokens")

        val resolved = ModelDownloader.resolveModelIn(models, ModelCatalog.IMPORT_ID)

        assertNotNull(resolved)
        assertEquals(ModelLayout.Whisper("nested/encoder.onnx", "nested/decoder.onnx", "nested/tokens.txt"), resolved!!.spec.layout)
        assertEquals(LanguagePolicy.Fixed("en", "English"), resolved.spec.languagePolicy)
    }

    @Test
    fun `replacement install generation changes`() {
        fun packageZip(graph: String) = zip(
            "model.json" to manifestJson("nemo_ctc", "model.onnx", "tokens.txt", listOf("model.onnx", "tokens.txt")),
            "model.onnx" to graph,
            "tokens.txt" to "tokens",
        )
        assertEquals(ModelDownloader.Result.Success(ModelCatalog.IMPORT_ID), ModelDownloader.installImportZip(packageZip("graph v1"), models))
        val first = ModelDownloader.resolveModelIn(models, ModelCatalog.IMPORT_ID)!!.generation

        assertEquals(ModelDownloader.Result.Success(ModelCatalog.IMPORT_ID), ModelDownloader.installImportZip(packageZip("graph v2"), models))
        val second = ModelDownloader.resolveModelIn(models, ModelCatalog.IMPORT_ID)!!.generation

        assertNotEquals(first, second)
    }

    private fun manifestJson(backend: String, model: String, tokens: String, artifacts: List<String>): String =
        """{"version":1,"title":"Test package","backend":"$backend","files":{"model":"$model","tokens":"$tokens"},"artifacts":[${artifacts.joinToString(",") { "\"$it\"" }}],"language":{"mode":"fixed","code":"en","label":"English"}}"""

    private fun zip(vararg entries: Pair<String, String>): File {
        val file = temp.newFile("package-${System.nanoTime()}.zip")
        ZipOutputStream(FileOutputStream(file)).use { output ->
            entries.forEach { (name, content) ->
                output.putNextEntry(ZipEntry(name))
                output.write(content.toByteArray())
                output.closeEntry()
            }
        }
        return file
    }
}
