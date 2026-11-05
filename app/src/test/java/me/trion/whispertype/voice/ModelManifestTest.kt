package me.trion.whispertype.voice

import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class ModelManifestTest {
    @get:Rule val temp = TemporaryFolder()

    private fun validCtcManifest() = JSONObject(
        """{"version":1,"backend":"nemo_ctc","files":{"model":"m.onnx","tokens":"tokens.txt"},"artifacts":["m.onnx","tokens.txt"],"title":"x","language":{"mode":"fixed","code":"en","label":"English"}}""",
    )

    @Test
    fun `round trips supported layouts and language policies without download URLs`() {
        val dir = temp.newFolder("manifest")
        val spec = ModelSpec(
            id = ModelCatalog.IMPORT_ID,
            title = "Imported CTC",
            approxSizeMb = 42,
            layout = ModelLayout.NemoCtc("graphs/model.int8.onnx", "tokens.txt"),
            languagePolicy = LanguagePolicy.Fixed("en", "English"),
            artifacts = listOf(
                ModelArtifact("graphs/model.int8.onnx", ""),
                ModelArtifact("tokens.txt", ""),
                ModelArtifact("graphs/model.onnx.data", ""),
            ),
            description = "test descriptor",
            revision = "r1",
            maxSegmentMs = 40_000,
        )

        ModelManifest.write(dir, spec, "generation-1")
        val json = JSONObject(File(dir, ModelManifest.FILE_NAME).readText())
        val read = ModelManifest.read(dir)

        assertEquals(1, json.getInt("version"))
        assertEquals("nemo_ctc", json.getString("backend"))
        assertEquals(spec, read.spec)
        assertEquals("generation-1", read.generation)
        assertFalse(json.has("downloadUrl"))
    }

    @Test
    fun `rejects unknown versions and runtime families explicitly`() {
        val dir = temp.newFolder("manifest")
        val file = File(dir, ModelManifest.FILE_NAME)
        file.writeText(validCtcManifest().put("version", 2).toString())
        assertThrows(IllegalArgumentException::class.java) { ModelManifest.read(dir) }

        file.writeText(validCtcManifest().put("backend", "transducer").toString())
        assertThrows(IllegalArgumentException::class.java) { ModelManifest.read(dir) }
    }

    @Test
    fun `rejects incomplete role mappings and unsupported language policies`() {
        val dir = temp.newFolder("manifest")
        val file = File(dir, ModelManifest.FILE_NAME)
        file.writeText(validCtcManifest().put("files", JSONObject().put("model", "m.onnx")).toString())
        assertThrows(IllegalArgumentException::class.java) { ModelManifest.read(dir) }

        file.writeText(validCtcManifest().put("language", JSONObject().put("mode", "transducer")).toString())
        assertThrows(IllegalArgumentException::class.java) { ModelManifest.read(dir) }
    }

    @Test
    fun `ctc manifest cannot advertise language forcing its backend ignores`() {
        val dir = temp.newFolder("ctc-language")
        File(dir, ModelManifest.FILE_NAME).writeText(
            """{"version":1,"backend":"nemo_ctc","files":{"model":"m.onnx","tokens":"tokens.txt"},"artifacts":["m.onnx","tokens.txt"],"title":"x","language":{"mode":"selectable","codes":["","hi"],"defaultCode":""}}""",
        )
        assertThrows(IllegalArgumentException::class.java) { ModelManifest.read(dir) }
    }

    @Test
    fun `canary manifest round trips with a selectable language policy`() {
        val dir = temp.newFolder("canary")
        val spec = ModelSpec(
            id = ModelCatalog.IMPORT_ID,
            title = "Imported Canary",
            approxSizeMb = 207,
            layout = ModelLayout.Canary("encoder.int8.onnx", "decoder.int8.onnx", "tokens.txt"),
            languagePolicy = LanguagePolicy.Selectable(listOf("en", "es", "de", "fr"), defaultCode = "en"),
            artifacts = listOf(
                ModelArtifact("encoder.int8.onnx", ""),
                ModelArtifact("decoder.int8.onnx", ""),
                ModelArtifact("tokens.txt", ""),
            ),
        )

        ModelManifest.write(dir, spec, "generation-canary")
        val json = JSONObject(File(dir, ModelManifest.FILE_NAME).readText())

        assertEquals("canary", json.getString("backend"))
        assertEquals(spec, ModelManifest.read(dir).spec)
    }

    @Test
    fun `canary manifest rejects role mappings its backend does not use`() {
        val dir = temp.newFolder("canary-roles")
        File(dir, ModelManifest.FILE_NAME).writeText(
            """{"version":1,"backend":"canary","files":{"model":"m.onnx","tokens":"tokens.txt"},"artifacts":["m.onnx","tokens.txt"],"title":"x","language":{"mode":"fixed","code":"en","label":"English"}}""",
        )
        assertThrows(IllegalArgumentException::class.java) { ModelManifest.read(dir) }
    }

    @Test
    fun `optional segment limit defaults by backend and rejects unsafe bounds`() {
        val dir = temp.newFolder("segment-default")
        val file = File(dir, ModelManifest.FILE_NAME)
        file.writeText(validCtcManifest().toString())
        assertEquals(45_000L, ModelManifest.read(dir).spec.maxSegmentMs)
        file.writeText(validCtcManifest().put("maxSegmentMs", 4_000).toString())
        assertThrows(IllegalArgumentException::class.java) { ModelManifest.read(dir) }
        file.writeText(validCtcManifest().put("maxSegmentMs", 61_000).toString())
        assertThrows(IllegalArgumentException::class.java) { ModelManifest.read(dir) }
    }
}
