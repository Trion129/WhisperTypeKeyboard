package me.trion.whispertype.ime

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class EmojiSearchEngineTest {
    private val catalog = EmojiCatalog(
        groups = listOf("Smileys & Emotion", "Symbols", "Flags"),
        items = listOf(
            EmojiItem("🤣", "Smileys & Emotion", "face-smiling", "rolling on the floor laughing", listOf("laugh", "lol", "floor")),
            EmojiItem("😂", "Smileys & Emotion", "face-smiling", "face with tears of joy", listOf("laugh", "joy", "stroller", "dancefloor")),
            EmojiItem("❤️", "Symbols", "heart", "red heart", listOf("love", "heart")),
            EmojiItem("🇮🇳", "Flags", "country-flag", "flag: India", listOf("flag", "India")),
        ),
    )
    private val engine = EmojiSearchEngine(catalog)

    @Test
    fun `alias exact match ranks first`() {
        assertEquals(listOf("😂", "🤣"), engine.search("haha").take(2).map { it.emoji })
    }

    @Test
    fun `multi word query matches normalized name`() {
        assertEquals("❤️", engine.search("  RED   HEART ").first().emoji)
    }

    @Test
    fun `search covers every catalog group`() {
        assertEquals("🇮🇳", engine.search("flag india").first().emoji)
    }

    @Test
    fun `exact keyword ranks before substring`() {
        assertEquals(listOf("🤣", "😂"), engine.search("floor").take(2).map { it.emoji })
    }

    @Test
    fun `prefix ranks before substring`() {
        assertEquals(listOf("🤣", "😂"), engine.search("roll").map { it.emoji })
    }

    @Test
    fun `multi word prefix applies only at field start`() {
        val engine = EmojiSearchEngine(
            EmojiCatalog(
                groups = listOf("Test"),
                items = listOf(
                    EmojiItem("🎭", "Test", "later", "grinning face with joy", emptyList()),
                    EmojiItem("😂", "Test", "start", "face with tears of joy", emptyList()),
                ),
            ),
        )

        assertEquals(listOf("😂", "🎭"), engine.search("face wi").map { it.emoji })
    }

    @Test
    fun `substring matches normalized metadata`() {
        assertEquals(listOf("😂"), engine.search("tear").map { it.emoji })
    }

    @Test
    fun `punctuation and case are normalized`() {
        assertEquals("🇮🇳", engine.search("  FLAG---india ").first().emoji)
    }

    @Test
    fun `equal scores preserve catalog order`() {
        assertEquals(listOf("🤣", "😂"), engine.search("face").map { it.emoji })
    }

    @Test
    fun `exact emoji query returns that emoji`() {
        assertEquals(listOf("❤️"), engine.search("❤️").map { it.emoji })
    }

    @Test
    fun `exact suggestion uses aliases names and keywords`() {
        assertEquals(listOf("😂", "🤣"), engine.suggestExact("haha").map { it.emoji })
        assertEquals(listOf("❤️"), engine.suggestExact("red heart").map { it.emoji })
        assertEquals(listOf("😂"), engine.suggestExact("joy").map { it.emoji })
    }

    @Test
    fun `exact suggestion respects limit and rejects incomplete triggers`() {
        assertEquals(listOf("😂"), engine.suggestExact("haha", limit = 1).map { it.emoji })
        assertTrue(engine.suggestExact("haha", limit = 0).isEmpty())
        assertTrue(engine.suggestExact("ha").isEmpty())
    }

    @Test
    fun `empty and unknown queries are empty`() {
        assertTrue(engine.search("   ").isEmpty())
        assertTrue(engine.search("not-an-emoji-term").isEmpty())
    }
}
