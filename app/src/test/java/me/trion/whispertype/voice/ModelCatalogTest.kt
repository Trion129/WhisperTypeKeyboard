package me.trion.whispertype.voice

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ModelCatalogTest {
    @Test
    fun `catalog ids are unique and every entry is addressable`() {
        assertEquals(ModelCatalog.entries.size, ModelCatalog.entries.map { it.id }.toSet().size)
        for (spec in ModelCatalog.entries) {
            assertEquals(spec, ModelCatalog.byId(spec.id))
            assertTrue(spec.requiredPathsDeclared())
        }
        assertNull(ModelCatalog.byId("unknown-model"))
        assertNotNull(ModelCatalog.byId(ModelCatalog.DEFAULT_ID))
    }

    @Test
    fun `canary entry pins artifacts and offers only its forced languages`() {
        val spec = requireNotNull(ModelCatalog.byId("canary-180m-flash"))

        assertEquals("Canary 180M Flash EN/ES/DE/FR", spec.title)
        assertEquals(
            ModelLayout.Canary("encoder.int8.onnx", "decoder.int8.onnx", "tokens.txt"),
            spec.layout,
        )
        assertEquals(LanguagePolicy.Selectable(listOf("en", "es", "de", "fr"), defaultCode = "en"), spec.languagePolicy)
        assertEquals(45_000L, spec.maxSegmentMs)
        assertEquals(207, spec.approxSizeMb)
        assertEquals("9077164e0d3dd1d5353743e89ceaa1d3a770838c", spec.revision)
        assertEquals(
            mapOf(
                "encoder.int8.onnx" to
                    (132678643L to "7a75b4e2a5857a6dcc0819503bbe3fad66943db4a3ccf21d3f27c633667d303f"),
                "decoder.int8.onnx" to
                    (74437848L to "e41a2ab9c0c2fe81a1e8ade5a45fb02a74bc4db7d1f91b89a54a25e2cf79cba2"),
                "tokens.txt" to
                    (53555L to "2dae6fc7815f9640645e0c765522b278ee0cef49b482d91f6913e334628d3e77"),
            ),
            spec.artifacts.associate { it.path to (it.sizeBytes to it.sha256) },
        )
        val policy = spec.languagePolicy
        assertEquals("es", policy.effectiveLanguage("es"))
        assertEquals("en", policy.effectiveLanguage("hi"))
        assertEquals("en", policy.effectiveLanguage(""))
    }

    private fun ModelSpec.requiredPathsDeclared(): Boolean =
        artifacts.map { it.path }.containsAll(layout.requiredPaths)

    @Test
    fun `reserved import id remains outside catalog`() {
        assertNull(ModelCatalog.byId(ModelCatalog.IMPORT_ID))
    }

}
