package me.trion.whispertype.ime

import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class SuggestionEngineTest {

    private val words = listOf("the", "then", "there", "they", "theme", "hello", "help", "world")
    private val engine = SuggestionEngine(words)

    @Test
    fun `blank prefix is empty`() {
        assertTrue(engine.suggest("", emptyMap()).isEmpty())
        assertTrue(engine.suggest("   ", emptyMap()).isEmpty())
    }

    @Test
    fun `prefix match uses wordlist order`() {
        assertEquals(listOf("the", "then", "there"), engine.suggest("th", emptyMap()))
    }

    @Test
    fun `learned count ranks above wordlist order`() {
        assertEquals(
            listOf("they", "the", "then"),
            engine.suggest("th", mapOf("they" to 5))
        )
    }

    @Test
    fun `learned-only word appears after listed words with equal count`() {
        val result = engine.suggest("th", mapOf("thx" to 0), limit = 10)
        assertTrue(result.contains("thx"))
        assertEquals("thx", result.last())
        assertTrue(result.indexOf("thx") > result.indexOf("the"))
    }

    @Test
    fun `capitalized prefix title-cases results`() {
        assertEquals(listOf("The", "Then", "There"), engine.suggest("Th", emptyMap()))
    }

    @Test
    fun `all-caps prefix uppercases results`() {
        assertEquals(listOf("THE", "THEN", "THERE"), engine.suggest("TH", emptyMap()))
    }
}

class UnigramStoreTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    private fun store(): UnigramStore = UnigramStore(File(tempFolder.root, "unigrams.json"))

    @Test
    fun `learn increments and persists`() {
        val s = store()
        s.learn("Hello")
        s.learn("hello")
        assertEquals(2, store().snapshot()["hello"])
    }

    @Test
    fun `short and non-letter words are ignored`() {
        val s = store()
        s.learn("a")
        s.learn("ok1")
        s.learn("hi!")
        assertTrue(s.snapshot().isEmpty())
        s.learn("ok")
        assertEquals(1, s.snapshot()["ok"])
    }

    @Test
    fun `overflow drops lowest count then alphabetical`() {
        val file = File(tempFolder.root, "full.json")
        val seed = (1..UnigramStore.MAX_ENTRIES - 1).associate { "w${it.toString().padStart(4, '0')}" to 2 } +
            mapOf("aaaa" to 1)
        file.writeText(UnigramStore.encodeObject(seed))
        val s = UnigramStore(file)
        val after = s.learn("freshword")
        assertEquals(UnigramStore.MAX_ENTRIES, after.size)
        assertFalse(after.containsKey("aaaa"))
        assertEquals(1, after["freshword"])
    }
}
