package me.trion.whispertype.ime

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class EmojiSuggestionEngineTest {
    private val catalog = EmojiCatalog(
        groups = listOf("Smileys & Emotion", "Symbols"),
        items = listOf(
            EmojiItem("😂", "Smileys & Emotion", "face-smiling", "face with tears of joy", listOf("laugh", "joy")),
            EmojiItem("🤣", "Smileys & Emotion", "face-smiling", "rolling on the floor laughing", listOf("laugh", "lol")),
            EmojiItem("😢", "Smileys & Emotion", "face-concerned", "crying face", listOf("cry", "sad")),
            EmojiItem("😭", "Smileys & Emotion", "face-concerned", "loudly crying face", listOf("cry", "sad")),
            EmojiItem("❤️", "Symbols", "heart", "red heart", listOf("love", "heart")),
            EmojiItem("😍", "Smileys & Emotion", "face-affection", "smiling face with heart-eyes", listOf("love", "heart")),
            EmojiItem("😆", "Smileys & Emotion", "face-smiling", "grinning squinting face", listOf("laugh")),
        ),
    )
    private val engine = EmojiSuggestionEngine(EmojiSearchEngine(catalog))

    @Test
    fun `supported exact triggers return their two emoji candidates`() {
        val expected = mapOf(
            "haha" to listOf("😂", "🤣"),
            "laugh" to listOf("😂", "🤣"),
            "cry" to listOf("😢", "😭"),
            "sad" to listOf("😢", "😭"),
            "love" to listOf("❤️", "😍"),
            "heart" to listOf("❤️", "😍"),
        )

        expected.forEach { (trigger, emojis) ->
            assertEquals(trigger, emojis, engine.suggest(trigger).map { it.item.emoji })
        }
    }
    @Test
    fun `reviewed aliases keep two emoji slots and one word slot`() {
        val expected = mapOf(
            "cry" to listOf("😢", "😭"),
            "sad" to listOf("😢", "😭"),
            "love" to listOf("❤️", "😍"),
            "heart" to listOf("❤️", "😍"),
        )

        expected.forEach { (trigger, emojis) ->
            val candidates = engine.suggest(trigger)
            assertEquals(emojis, candidates.map { it.item.emoji })
            val composed = SuggestionComposer.compose(listOf("word"), candidates)
            assertEquals(3, composed.size)
            assertEquals(SuggestionItem.Word("word"), composed.last())
        }
    }


    @Test
    fun `partial triggers return no emoji candidates`() {
        listOf("ha", "laug", "cr", "sa", "lov", "hear").forEach { partial ->
            assertTrue(partial, engine.suggest(partial).isEmpty())
        }
    }

    @Test
    fun `replacement removes only the current trigger`() {
        assertEquals(
            EmojiReplacement(deleteBeforeCursor = 4, commitText = "😂"),
            engine.suggest("say haha").first().replacement,
        )
    }

    @Test
    fun `replacement preserves exactly one trailing space`() {
        assertEquals(
            EmojiReplacement(deleteBeforeCursor = 5, commitText = "😂 "),
            engine.suggest("say haha ").first().replacement,
        )
    }

    @Test
    fun `two trailing spaces stop the trigger`() {
        assertTrue(engine.suggest("haha  ").isEmpty())
    }

    @Test
    fun `candidate limit is bounded between zero and two`() {
        assertTrue(engine.suggest("laugh", limit = -1).isEmpty())
        assertTrue(engine.suggest("laugh", limit = 0).isEmpty())
        assertEquals(listOf("😂"), engine.suggest("laugh", limit = 1).map { it.item.emoji })
        assertEquals(listOf("😂", "🤣"), engine.suggest("laugh", limit = 10).map { it.item.emoji })
    }
}

class SuggestionComposerTest {
    private val candidates = listOf("😂", "🤣", "😆").map { emoji ->
        EmojiCandidate(
            item = EmojiItem(emoji, "Smileys & Emotion", "face-smiling", emoji, emptyList()),
            replacement = EmojiReplacement(deleteBeforeCursor = 4, commitText = emoji),
        )
    }

    @Test
    fun `composer puts up to two emojis first and keeps remaining word slots`() {
        assertEquals(
            listOf(
                SuggestionItem.Emoji(candidates[0]),
                SuggestionItem.Emoji(candidates[1]),
                SuggestionItem.Word("hahaha"),
            ),
            SuggestionComposer.compose(
                words = listOf("hahaha", "hah"),
                emojis = candidates,
            ),
        )
    }

    @Test
    fun `composer fills slots not used by emojis with words`() {
        assertEquals(
            listOf(
                SuggestionItem.Emoji(candidates[0]),
                SuggestionItem.Word("hahaha"),
                SuggestionItem.Word("hah"),
            ),
            SuggestionComposer.compose(
                words = listOf("hahaha", "hah", "ha"),
                emojis = candidates.take(1),
            ),
        )
        assertEquals(
            listOf(
                SuggestionItem.Word("hahaha"),
                SuggestionItem.Word("hah"),
                SuggestionItem.Word("ha"),
            ),
            SuggestionComposer.compose(
                words = listOf("hahaha", "hah", "ha", "hammer"),
                emojis = emptyList(),
            ),
        )
    }

    @Test
    fun `composer respects positive and nonpositive limits`() {
        assertEquals(
            listOf(SuggestionItem.Emoji(candidates[0])),
            SuggestionComposer.compose(
                words = listOf("hahaha"),
                emojis = candidates,
                limit = 1,
            ),
        )
        assertTrue(SuggestionComposer.compose(listOf("hahaha"), candidates, limit = 0).isEmpty())
        assertTrue(SuggestionComposer.compose(listOf("hahaha"), candidates, limit = -1).isEmpty())
    }
}
