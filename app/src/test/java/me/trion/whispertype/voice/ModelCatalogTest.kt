package me.trion.whispertype.voice

import org.junit.Assert.*
import org.junit.Test

class ModelCatalogTest {

    @Test
    fun `REVISION is immutable pinned HuggingFace commit hash`() {
        assertEquals(
            "cb523d0fba0f8d9a28a3f7432668a8ea4e8be6e4",
            ModelCatalog.REVISION
        )
    }

    @Test
    fun `DOWNLOAD_URL uses pinned revision not main`() {
        val url = ModelCatalog.DOWNLOAD_URL
        assertTrue(
            "URL must contain the pinned revision, not 'main': $url",
            url.contains(ModelCatalog.REVISION)
        )
        assertFalse(
            "URL must not contain '/resolve/main/': $url",
            url.contains("/resolve/main/")
        )
    }

    @Test
    fun `EXPECTED_SHA256 matches pinned HuggingFace LFS object`() {
        val sha = ModelCatalog.EXPECTED_SHA256
        assertEquals(
            "97ac121610693e8c9adaef9c6db9066ef0cbebba53ca6e47f6d1541068ca19e3",
            sha
        )
    }
}
