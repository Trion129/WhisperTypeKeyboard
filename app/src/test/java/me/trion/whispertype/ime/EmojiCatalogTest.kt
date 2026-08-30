package me.trion.whispertype.ime

import org.junit.Assert.*
import org.junit.Test

class EmojiCatalogTest {

    private val sample = """
        {
          "groups": ["Smileys & Emotion", "Flags"],
          "items": [
            {"emoji":"😀","group":"Smileys & Emotion","subgroup":"face-smiling","name":"grinning face","keywords":["face","smile"]},
            {"emoji":"👋","group":"People & Body","subgroup":"hand","name":"waving hand","keywords":["hand","wave"],"tones":["👋","👋🏻","👋🏼","👋🏽","👋🏾","👋🏿"]},
            {"emoji":"🇺🇸","group":"Flags","subgroup":"country-flag","name":"flag United States","keywords":["us","flag"]}
          ]
        }
    """.trimIndent()

    @Test
    fun `parse reads groups items keywords and tones`() {
        val catalog = EmojiCatalog.parse(sample)
        assertEquals(listOf("Smileys & Emotion", "Flags"), catalog.groups)
        assertEquals(3, catalog.items.size)
        val wave = catalog.items[1]
        assertTrue(wave.toneCapable)
        assertEquals(6, wave.tones.size)
        assertEquals("grinning face", catalog.items[0].name)
    }


    @Test
    fun `recents preserve order and cap`() {
        val catalog = EmojiCatalog.parse(sample)
        val recents = catalog.recents(listOf("🇺🇸", "missing", "😀", "👋"), cap = 3)
        assertEquals(listOf("🇺🇸", "missing", "😀"), recents.map { it.emoji })
        assertEquals("Recents", recents[1].group)
    }

    @Test
    fun `inGroup filters`() {
        val catalog = EmojiCatalog.parse(sample)
        assertEquals(1, catalog.inGroup("Flags").size)
        assertTrue(catalog.inGroup("Objects").isEmpty())
    }
}
