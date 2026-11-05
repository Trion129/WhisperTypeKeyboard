package me.trion.whispertype.util

import me.trion.whispertype.voice.LanguagePolicy
import org.junit.Assert.assertEquals
import org.junit.Test

class LanguagePreferenceTest {
    private val policy = LanguagePolicy.Selectable(listOf("en", "hi"), defaultCode = "en")

    @Test fun savedLanguageTakesPrecedence() {
        assertEquals("hi", resolveLanguage(policy, "hi", "en"))
    }

    @Test fun validLegacyLanguageIsUsedWhenSavedIsInvalid() {
        assertEquals("hi", resolveLanguage(policy, "fr", "hi"))
    }

    @Test fun invalidValuesUsePolicyDefault() {
        assertEquals("en", resolveLanguage(policy, "fr", "de"))
    }

    @Test fun fixedLanguageIgnoresPreferences() {
        assertEquals("hi", resolveLanguage(LanguagePolicy.Fixed("hi", "Hindi"), "en", "en"))
    }
}
