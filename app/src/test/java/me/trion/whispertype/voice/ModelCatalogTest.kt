package me.trion.whispertype.voice

import org.junit.Assert.*
import org.junit.Test

class ModelCatalogTest {

    @Test
    fun `catalog has six unique entries`() {
        assertEquals(6, ModelCatalog.entries.size)
        assertEquals(
            "Model ids must be unique",
            ModelCatalog.entries.size,
            ModelCatalog.entries.map { it.id }.toSet().size
        )
    }

    @Test
    fun `default id is base en and present in the catalog`() {
        assertEquals("base.en", ModelCatalog.DEFAULT_ID)
        assertNotNull(ModelCatalog.byId(ModelCatalog.DEFAULT_ID))
        assertEquals("Whisper Base EN", ModelCatalog.byId(ModelCatalog.DEFAULT_ID)!!.title)
    }

    @Test
    fun `byId finds every catalog entry and rejects unknown ids`() {
        for (spec in ModelCatalog.entries) {
            assertEquals(spec, ModelCatalog.byId(spec.id))
        }
        assertNull(ModelCatalog.byId("medium.en"))
        assertNull(ModelCatalog.byId(""))
    }

    @Test
    fun `import id is reserved and legacy folder is pinned`() {
        assertEquals("import", ModelCatalog.IMPORT_ID)
        assertNull(ModelCatalog.byId(ModelCatalog.IMPORT_ID))
        assertEquals("whisper_small_int8", ModelCatalog.LEGACY_FOLDER)
    }

    @Test
    fun `file name helpers produce expected sherpa names`() {
        val spec = ModelCatalog.byId("base.en")!!
        assertEquals("base.en-encoder.int8.onnx", spec.encoderFileName)
        assertEquals("base.en-decoder.int8.onnx", spec.decoderFileName)
        assertEquals("base.en-tokens.txt", spec.tokensFileName)
    }

    @Test
    fun `urls point at the official csukuangfj huggingface packs`() {
        val spec = ModelCatalog.byId("tiny.en")!!
        assertEquals(
            "https://huggingface.co/csukuangfj/sherpa-onnx-whisper-tiny.en/resolve/main",
            spec.baseUrl()
        )
        assertTrue(spec.encoderUrl().endsWith("/" + spec.encoderFileName))
        assertTrue(spec.decoderUrl().endsWith("/" + spec.decoderFileName))
        assertTrue(spec.tokensUrl().endsWith("/" + spec.tokensFileName))
    }

    @Test
    fun `catalog sizes are the approximate real downloads`() {
        assertEquals(104, ModelCatalog.byId("tiny.en")!!.approxSizeMb)
        assertEquals(161, ModelCatalog.byId("base.en")!!.approxSizeMb)
        assertEquals(375, ModelCatalog.byId("small.en")!!.approxSizeMb)
    }

    @Test
    fun `catalog includes multilingual whisper variants`() {
        val ids = ModelCatalog.entries.map { it.id }
        assertEquals(
            listOf("tiny.en", "base.en", "small.en", "tiny", "base", "small"),
            ids
        )
        assertFalse(ModelCatalog.byId("tiny.en")!!.isMultilingual)
        assertTrue(ModelCatalog.byId("tiny")!!.isMultilingual)
        assertEquals("Whisper Base Multilingual", ModelCatalog.byId("base")!!.title)
        assertEquals(161, ModelCatalog.byId("base")!!.approxSizeMb)
    }

    @Test
    fun `language resolution preserves english-only models`() {
        assertEquals("", ModelCatalog.normalizeLanguage(""))
        assertEquals("fr", ModelCatalog.normalizeLanguage("fr"))
        assertEquals("", ModelCatalog.normalizeLanguage("not-a-language"))
        assertEquals("en", ModelCatalog.effectiveLanguage("base.en", "fr"))
        assertEquals("fr", ModelCatalog.effectiveLanguage("base", "fr"))
        assertEquals("", ModelCatalog.effectiveLanguage("base", ""))
        assertEquals("en", ModelCatalog.effectiveLanguage(ModelCatalog.IMPORT_ID, "fr"))
        assertEquals("en", ModelCatalog.effectiveLanguage("unknown", "fr"))
    }
}
