package me.trion.whispertype.ime

import android.content.Context
import android.os.SystemClock
import android.view.inputmethod.InputMethodManager
import android.widget.EditText
import androidx.test.ext.junit.rules.ActivityScenarioRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.By
import androidx.test.uiautomator.BySelector
import androidx.test.uiautomator.StaleObjectException
import androidx.test.uiautomator.UiDevice
import androidx.test.uiautomator.UiObject2
import androidx.test.uiautomator.Until
import me.trion.whispertype.R
import me.trion.whispertype.settings.SetupActivity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class EmojiKeyboardIntegrationTest {
    private val device = UiDevice.getInstance(InstrumentationRegistry.getInstrumentation())

    @get:Rule
    val activityRule = ActivityScenarioRule(SetupActivity::class.java)

    @Before
    fun resetImeAndField() {
        executeImeCommand("ime reset")
        executeImeCommand("ime enable $IME_COMPONENT")
        executeImeCommand("ime set $IME_COMPONENT")

        activityRule.scenario.onActivity { activity ->
            val field = activity.findViewById<EditText>(R.id.test_input)
            field.clearFocus()
            field.setText("")
            assertTrue("SetupActivity test field could not receive focus", field.requestFocus())
            field.setSelection(field.text.length)
        }

        // `ime set` returns before the new IME is bound; a show request sent
        // before then is dropped, so repeat it until the keyboard appears.
        val deadline = SystemClock.uptimeMillis() + IME_TIMEOUT_MS
        var rebindAt = SystemClock.uptimeMillis() + REBIND_AFTER_MS
        while (!device.hasObject(By.res(APP_PACKAGE, "keyboard_root"))) {
            val now = SystemClock.uptimeMillis()
            if (now >= deadline) {
                throw AssertionError(
                    "WhisperType IME did not appear after resetting, enabling, selecting, " +
                        "and focusing the test field after ${IME_TIMEOUT_MS}ms",
                )
            }
            // A cold CI emulator can drop the first "ime set" bind; select the
            // keyboard again instead of only asking for it to show.
            if (now >= rebindAt) {
                executeImeCommand("ime enable $IME_COMPONENT")
                executeImeCommand("ime set $IME_COMPONENT")
                rebindAt = now + REBIND_AFTER_MS
            }
            requestKeyboard()
            device.wait(Until.hasObject(By.res(APP_PACKAGE, "keyboard_root")), SHOW_RETRY_MS)
        }
        assertFieldText("", "The SetupActivity test field was not empty at test start")
    }

    private fun requestKeyboard() {
        activityRule.scenario.onActivity { activity ->
            val field = activity.findViewById<EditText>(R.id.test_input)
            val inputMethodManager =
                activity.getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
            inputMethodManager.restartInput(field)
            field.post {
                inputMethodManager.showSoftInput(field, InputMethodManager.SHOW_IMPLICIT)
            }
        }
    }

    @Test
    fun searchQueryStaysLocalAndResultCommits() {
        openEmojiSearch()
        typeEmojiSearch("red heart")
        assertHostEditorVisible(
            failureContext = "The host editor became hidden after entering the emoji search query",
        )
        assertResourceText(
            resourceName = "emoji_search_query",
            expected = "red heart",
            failureContext = "Emoji search did not display the complete local query",
        )
        assertFieldText(
            expected = "",
            failureContext = "Typing an emoji search query leaked text into the host editor",
        )
        assertRedHeartFilterResults()

        clickDescription(
            description = "red heart",
            failureContext = "The local 'red heart' search did not expose the red-heart result",
        )
        awaitFieldText(
            expected = "❤️",
            failureContext = "Selecting the red-heart result did not commit it to the host editor",
        )
    }

    @Test
    fun searchBackspaceChangesOnlyTheLocalQuery() {
        openEmojiSearch()
        typeEmojiSearch("cryx")
        clickDescription(
            description = "emoji-search-backspace",
            failureContext = "Emoji search did not expose its local backspace key",
        )

        assertResourceText(
            resourceName = "emoji_search_query",
            expected = "cry",
            failureContext = "Emoji search backspace did not remove only the final query character",
        )
        assertFieldText(
            expected = "",
            failureContext = "Emoji search backspace changed the host editor instead of only the local query",
        )
    }

    @Test
    fun normalSuggestionReplacesTriggerWord() {
        typeNormalKeys("haha")

        waitForObject(
            By.text("😂"),
            SUGGESTION_TIMEOUT_MS,
            "Typing 'haha' did not show the 😂 emoji suggestion",
        )
        waitForObject(
            By.text("🤣"),
            SUGGESTION_TIMEOUT_MS,
            "Typing 'haha' showed no alternate 🤣 emoji suggestion",
        )
        clickText(
            text = "😂",
            timeoutMs = SUGGESTION_TIMEOUT_MS,
            failureContext = "The 😂 suggestion disappeared before it could be selected",
        )

        awaitFieldText(
            expected = "😂",
            failureContext = "Selecting the 😂 suggestion did not replace the complete 'haha' trigger word",
        )
    }

    @Test
    fun suggestionReplacementPreservesOneTrailingSpace() {
        typeNormalKeys("haha")
        clickDescription(
            description = "key-space",
            failureContext = "The normal keyboard did not expose a stable space-key selector",
        )

        clickText(
            text = "😂",
            timeoutMs = SUGGESTION_TIMEOUT_MS,
            failureContext = "Typing 'haha ' did not retain the 😂 emoji suggestion",
        )
        awaitFieldText(
            expected = "😂 ",
            failureContext = "Emoji suggestion replacement did not preserve exactly one trailing space",
        )
    }

    @Test
    fun reopenedKeyboardShowsTheCaseItTypes() {
        awaitKeyLabel("r", "R", "An empty field did not show auto-capitalized keys")

        // Reopen the keyboard on a field that is mid-sentence.
        activityRule.scenario.onActivity { activity ->
            val field = activity.findViewById<EditText>(R.id.test_input)
            field.setText("see you tomor")
            field.setSelection(field.text.length)
        }
        requestKeyboard()

        awaitKeyLabel("r", "r", "Keys still showed capitals after reopening mid-sentence")
        typeNormalKeys("r")
        awaitFieldText("see you tomorr", "The key typed a different case than its label showed")
    }

    @Test
    fun keyLabelsFollowTheCursor() {
        activityRule.scenario.onActivity { activity ->
            val field = activity.findViewById<EditText>(R.id.test_input)
            field.setText("Hi. hello")
            field.setSelection(field.text.length)
        }
        awaitKeyLabel("a", "a", "Keys showed capitals mid-sentence")

        activityRule.scenario.onActivity { activity ->
            activity.findViewById<EditText>(R.id.test_input).setSelection(4)
        }
        awaitKeyLabel("a", "A", "Keys did not show capitals after the cursor moved to a sentence start")
        typeNormalKeys("a")
        awaitFieldText("Hi. Ahello", "The key typed a different case than its label showed")
    }

    private fun awaitKeyLabel(key: String, expected: String, failureContext: String) {
        val selector = By.desc("key-$key")
        val deadline = SystemClock.uptimeMillis() + UI_TIMEOUT_MS
        var actual: String? = null
        while (SystemClock.uptimeMillis() < deadline) {
            actual = try {
                device.findObject(selector)?.text
            } catch (e: StaleObjectException) {
                null
            }
            if (actual == expected) return
            SystemClock.sleep(POLL_INTERVAL_MS)
        }
        throw AssertionError("$failureContext: key '$key' showed '$actual', expected '$expected'")
    }

    private fun openEmojiSearch() {
        clickResource(
            resourceName = "btn_emoji_strip",
            failureContext = "The keyboard toolbar did not expose the emoji button",
        )
        clickResource(
            resourceName = "emoji_search_entry",
            timeoutMs = FEATURE_TIMEOUT_MS,
            failureContext = "Opening the emoji panel did not expose the stable emoji search entry",
        )
        assertHostEditorVisible(
            failureContext = "The host editor became hidden after opening emoji search",
        )
    }

    private fun typeEmojiSearch(query: String) {
        query.forEach { character ->
            val description = if (character == ' ') {
                "emoji-search-space"
            } else {
                "emoji-search-key-$character"
            }
            clickDescription(
                description = description,
                failureContext = "Emoji search could not enter character ${character.toDebugText()}",
            )
        }
    }

    private fun typeNormalKeys(text: String) {
        text.forEach { character ->
            clickDescription(
                description = "key-$character",
                failureContext = "The normal keyboard could not type '$character' with its stable selector",
            )
        }
    }

    private fun clickResource(
        resourceName: String,
        timeoutMs: Long = UI_TIMEOUT_MS,
        failureContext: String,
    ) {
        clickObject(
            By.res(APP_PACKAGE, resourceName),
            timeoutMs,
            "$failureContext (missing resource '$resourceName')",
        )
    }

    private fun assertRedHeartFilterResults() {
        waitForObject(
            By.res(APP_PACKAGE, "emoji_result_grid")
                .hasDescendant(By.desc("red heart")),
            UI_TIMEOUT_MS,
            "The filtered emoji result grid did not contain the 'red heart' cell",
        )
        assertFalse(
            "The filtered 'red heart' result grid still contained the unrelated 'grinning face' cell",
            device.hasObject(
                By.res(APP_PACKAGE, "emoji_result_grid")
                    .hasDescendant(By.desc("grinning face")),
            ),
        )
    }

    private fun clickDescription(
        description: String,
        timeoutMs: Long = UI_TIMEOUT_MS,
        failureContext: String,
    ) {
        clickObject(
            By.desc(description),
            timeoutMs,
            "$failureContext (missing content description '$description')",
        )
    }

    private fun clickText(text: String, timeoutMs: Long, failureContext: String) {
        clickObject(
            By.text(text),
            timeoutMs,
            "$failureContext (missing visible text '$text')",
        )
    }

    /**
     * The keyboard rebuilds rows (for example the suggestion strip after a
     * key press), so a found view can be replaced before the click lands.
     * Finds it again and retries until the timeout.
     */
    private fun clickObject(selector: BySelector, timeoutMs: Long, failureMessage: String) {
        val deadline = SystemClock.uptimeMillis() + timeoutMs
        while (true) {
            val remaining = (deadline - SystemClock.uptimeMillis()).coerceAtLeast(POLL_INTERVAL_MS)
            try {
                waitForObject(selector, remaining, failureMessage).click()
                return
            } catch (e: StaleObjectException) {
                if (SystemClock.uptimeMillis() >= deadline) throw e
            }
        }
    }

    private fun waitForObject(
        selector: BySelector,
        timeoutMs: Long,
        failureMessage: String,
    ): UiObject2 = device.wait(Until.findObject(selector), timeoutMs)
        ?: throw AssertionError("$failureMessage after ${timeoutMs}ms")

    private fun assertResourceText(
        resourceName: String,
        expected: String,
        failureContext: String,
    ) {
        val selector = By.res(APP_PACKAGE, resourceName)
        waitForObject(
            selector,
            UI_TIMEOUT_MS,
            "$failureContext (missing resource '$resourceName')",
        )

        val deadline = SystemClock.uptimeMillis() + UI_TIMEOUT_MS
        var actual: String? = null
        while (SystemClock.uptimeMillis() < deadline) {
            actual = device.findObject(selector)?.text
            if (actual == expected) return
            SystemClock.sleep(POLL_INTERVAL_MS)
        }
        throw AssertionError("$failureContext: expected '$expected', last observed '$actual'")
    }

    private fun assertHostEditorVisible(failureContext: String) {
        val imeRoot = waitForObject(
            By.res(APP_PACKAGE, "keyboard_root"),
            UI_TIMEOUT_MS,
            "$failureContext (missing IME root)",
        )
        val field = waitForObject(
            By.res(APP_PACKAGE, "test_input"),
            UI_TIMEOUT_MS,
            "$failureContext (missing host test field)",
        )
        val visibleBounds = field.visibleBounds
        val imeBounds = imeRoot.visibleBounds
        assertTrue(
            "$failureContext: host field visible height was ${visibleBounds.height()} (bounds=$visibleBounds)",
            visibleBounds.height() > 0,
        )
        assertTrue(
            "$failureContext: host field visible bottom ${visibleBounds.bottom} exceeded display height ${device.displayHeight} (bounds=$visibleBounds)",
            visibleBounds.bottom <= device.displayHeight,
        )

        var fieldTop = 0
        var fieldHeight = 0
        activityRule.scenario.onActivity { activity ->
            val hostField = activity.findViewById<EditText>(R.id.test_input)
            val location = IntArray(2)
            hostField.getLocationOnScreen(location)
            fieldTop = location[1]
            fieldHeight = hostField.height
        }
        val fieldBottom = fieldTop + fieldHeight
        assertTrue(
            "$failureContext: host field height was $fieldHeight",
            fieldHeight > 0,
        )
        assertTrue(
            "$failureContext: host field top was $fieldTop (field must start on-screen)",
            fieldTop >= 0,
        )
        assertTrue(
            "$failureContext: full host field bottom $fieldBottom was not above IME top ${imeBounds.top} (imeBounds=$imeBounds)",
            fieldBottom <= imeBounds.top,
        )
    }

    private fun assertFieldText(expected: String, failureContext: String) {
        device.waitForIdle()
        assertEquals(failureContext, expected, readFieldText())
    }

    private fun awaitFieldText(expected: String, failureContext: String) {
        val deadline = SystemClock.uptimeMillis() + UI_TIMEOUT_MS
        var actual = readFieldText()
        while (actual != expected && SystemClock.uptimeMillis() < deadline) {
            SystemClock.sleep(POLL_INTERVAL_MS)
            actual = readFieldText()
        }
        assertEquals("$failureContext; last observed host text was '$actual'", expected, actual)
    }

    private fun readFieldText(): String {
        var text = ""
        activityRule.scenario.onActivity { activity ->
            text = activity.findViewById<EditText>(R.id.test_input).text.toString()
        }
        return text
    }

    private fun executeImeCommand(command: String) {
        val output = device.executeShellCommand(command).trim()
        if (output.contains("error", ignoreCase = true)) {
            throw AssertionError("Shell command '$command' failed: $output")
        }
    }

    private fun Char.toDebugText(): String = if (this == ' ') "<space>" else "'$this'"

    private companion object {
        // The debug build installs with an applicationIdSuffix, and its view IDs
        // carry that package too, so read it from the app under test.
        val APP_PACKAGE: String =
            InstrumentationRegistry.getInstrumentation().targetContext.packageName
        val IME_COMPONENT = "$APP_PACKAGE/${WhisperKeyboardService::class.java.name}"
        const val POLL_INTERVAL_MS = 50L
        const val FEATURE_TIMEOUT_MS = 3_000L
        const val UI_TIMEOUT_MS = 5_000L
        const val SUGGESTION_TIMEOUT_MS = 2_000L
        const val IME_TIMEOUT_MS = 30_000L
        const val SHOW_RETRY_MS = 2_000L
        const val REBIND_AFTER_MS = 5_000L
    }
}
