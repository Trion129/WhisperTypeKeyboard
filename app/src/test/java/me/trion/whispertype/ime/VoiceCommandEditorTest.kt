package me.trion.whispertype.ime

import android.text.InputType
import android.os.Looper
import android.view.ContextThemeWrapper
import android.view.KeyEvent
import android.view.LayoutInflater
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputConnection
import android.view.inputmethod.InputConnectionWrapper
import android.widget.EditText
import android.widget.TextView
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import me.trion.whispertype.R
import me.trion.whispertype.util.Prefs
import me.trion.whispertype.voice.LocalAsrEngine
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import java.io.File

/** Exercises recognized speech through the keyboard and a real EditText connection. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class VoiceCommandEditorTest {
    private lateinit var field: EditText
    private lateinit var connection: InputConnection
    private lateinit var controller: KeyboardController
    private lateinit var prefs: Prefs
    private lateinit var scope: CoroutineScope

    @Before
    fun setUp() {
        val context = ContextThemeWrapper(RuntimeEnvironment.getApplication(), R.style.Theme_WhisperType)
        prefs = Prefs(context).apply {
            voiceCommandPrefix = "Whispy"
            incognito = true
            autoSpace = true
        }
        field = EditText(context).apply {
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE
            requestFocus()
        }
        val info = EditorInfo()
        connection = field.onCreateInputConnection(info)!!
        val root = LayoutInflater.from(context).inflate(R.layout.keyboard_view, null)
        scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        controller = KeyboardController(
            context = context,
            root = root,
            scope = scope,
            inputConnectionProvider = { connection },
            editorInfoProvider = { info },
            performHaptic = {},
            requestMicPermission = { false },
            clipboardStore = ClipboardStore(File(context.cacheDir, "voice-command-clips.json")),
        )
    }

    @After
    fun tearDown() {
        controller.destroy()
        scope.cancel()
    }

    @Test
    fun `one two and three clears update the actual editor`() {
        val expected = listOf("i have a good day but i ", "i have a good day but ", "i have a good day ")
        for (count in 1..3) {
            setText("i have a good day but i wish ")
            recognize("Whispy " + List(count) { "clear" }.joinToString(" "))
            assertEquals(expected[count - 1], field.text.toString())
            assertEquals(field.text.length, field.selectionStart)
        }
    }

    @Test
    fun `punctuation and case still delete exactly two words`() {
        setText("hello good day. ")
        recognize("WHISPY, Clear, clear!")
        assertEquals("hello ", field.text.toString())
    }

    @Test
    fun `long words cause the editor context to expand`() {
        setText("prefix " + "x".repeat(200) + " tail ")
        recognize("Whispy clear clear")
        assertEquals("prefix ", field.text.toString())
    }

    @Test
    fun `deleting at a middle cursor preserves text after it`() {
        setText("before target after")
        field.setSelection("before target".length)
        recognize("Whispy clear clear")
        assertEquals(" after", field.text.toString())
    }

    @Test
    fun `clear all clears both sides of the cursor`() {
        setText("before cursor after")
        field.setSelection(7)
        recognize("Whispy clear all")
        assertEquals("", field.text.toString())
    }

    @Test
    fun `selected text is removed once without deleting adjacent words`() {
        setText("before selected words after")
        field.setSelection(7, 21)
        recognize("Whispy clear clear")
        assertEquals("before  after", field.text.toString())
    }

    @Test
    fun `dictation before the command is inserted before deleting its last words`() {
        setText("start")
        recognize("i have a good day but i wish Whispy clear clear")
        assertEquals("start i have a good day but ", field.text.toString())
    }

    @Test
    fun `configured prefix applies and blank prefix disables commands`() {
        prefs.voiceCommandPrefix = "Hey keyboard"
        setText("hello good day")
        recognize("Hey keyboard clear clear")
        assertEquals("hello ", field.text.toString())
        prefs.voiceCommandPrefix = ""
        setText("")
        recognize("Hey keyboard clear clear")
        assertEquals("Hey keyboard clear clear", field.text.toString())
    }

    @Test
    fun `ordinary dictation is committed without executing a command`() {
        setText("hello")
        recognize("Whispy clearer")
        assertEquals("hello Whispy clearer", field.text.toString())
    }

    @Test
    fun `more clears than words remove only the available text`() {
        setText("hello ")
        recognize("Whispy clear clear clear")
        assertEquals("", field.text.toString())
        recognize("Whispy clear clear")
        assertEquals("", field.text.toString())
    }

    @Test
    fun `unsupported clear all preserves a preexisting selection`() {
        setText("before selected after")
        field.setSelection(7, 15)
        connection = object : InputConnectionWrapper(connection, false) {
            override fun performContextMenuAction(id: Int) = false
            override fun sendKeyEvent(event: KeyEvent) = false
        }
        recognize("Whispy clear all")
        assertEquals("before selected after", field.text.toString())
    }

    @Test
    fun `consuming control A without selecting all preserves a partial selection`() {
        setText("before selected after")
        field.setSelection(7, 15)
        connection = object : InputConnectionWrapper(connection, false) {
            override fun performContextMenuAction(id: Int) = false
            override fun sendKeyEvent(event: KeyEvent) = true
        }
        recognize("Whispy clear all")
        assertEquals("before selected after", field.text.toString())
    }

    @Test
    fun `clear all uses the control A fallback when supported`() {
        setText("before cursor after")
        connection = object : InputConnectionWrapper(connection, false) {
            override fun performContextMenuAction(id: Int) = false
            override fun sendKeyEvent(event: KeyEvent): Boolean {
                assertEquals(KeyEvent.KEYCODE_A, event.keyCode)
                assertTrue(event.isCtrlPressed)
                if (event.action == KeyEvent.ACTION_UP) field.selectAll()
                return true
            }
        }
        recognize("Whispy clear all")
        assertEquals("", field.text.toString())
    }

    @Test
    fun `current session transcription still executes its voice command`() {
        setText("hello good day")
        preparePendingTranscription()
        deliver(LocalAsrEngine.Result.Success("Whispy clear clear"), sessionId(), connection)
        assertEquals("hello ", field.text.toString())
        assertFalse(privateField("isTranscribing").getBoolean(controller))
    }

    @Test
    fun `finishing input cancels pending clear all before switching editors`() {
        setText("first editor text")
        val firstField = field
        val firstConnection = connection
        val oldSession = sessionId()
        val pending = preparePendingTranscription()
        controller.onFinishInput()
        switchEditor("second editor text")
        controller.onStartInput()

        deliver(LocalAsrEngine.Result.Success("Whispy clear all"), oldSession, firstConnection)

        assertEquals("first editor text", firstField.text.toString())
        assertEquals("second editor text", field.text.toString())
        assertTrue(pending.isCancelled)
        assertFalse(privateField("isTranscribing").getBoolean(controller))
    }

    @Test
    fun `restarting input invalidates a pending result even with the same connection`() {
        setText("hello good day")
        val oldSession = sessionId()
        val pending = preparePendingTranscription()
        controller.onStartInput()

        deliver(LocalAsrEngine.Result.Success("Whispy clear all"), oldSession, connection)

        assertEquals("hello good day", field.text.toString())
        assertTrue(pending.isCancelled)
        assertFalse(privateField("isTranscribing").getBoolean(controller))
    }

    @Test
    fun `connection changes suppress late commands before lifecycle callbacks arrive`() {
        setText("first editor text")
        val firstField = field
        val firstConnection = connection
        val oldSession = sessionId()
        preparePendingTranscription()
        switchEditor("second editor text")

        deliver(LocalAsrEngine.Result.Success("Whispy clear all"), oldSession, firstConnection)

        assertEquals("first editor text", firstField.text.toString())
        assertEquals("second editor text", field.text.toString())
        assertFalse(privateField("isTranscribing").getBoolean(controller))
    }

    @Test
    fun `old transcription errors cannot reset a new transcription or its status`() {
        val oldSession = sessionId()
        val oldConnection = connection
        preparePendingTranscription()
        controller.onFinishInput()
        controller.onStartInput()
        val currentJob = preparePendingTranscription()
        val status = privateField("statusText").get(controller) as TextView
        status.text = "New transcription"

        deliver(LocalAsrEngine.Result.Error("Old error"), oldSession, oldConnection)

        assertTrue(privateField("isTranscribing").getBoolean(controller))
        assertFalse(currentJob.isCancelled)
        assertEquals("New transcription", status.text.toString())
    }

    @Test
    fun `finishing input resets recording state and selected microphone`() {
        privateField("isListening").setBoolean(controller, true)
        val mic = privateField("micButton").get(controller) as android.view.View
        mic.isSelected = true

        controller.onFinishInput()

        assertFalse(privateField("isListening").getBoolean(controller))
        assertFalse(privateField("isTranscribing").getBoolean(controller))
        assertFalse((privateField("micButton").get(controller) as android.view.View).isSelected)
    }

    @Test
    fun `a canceled scope still removes the WAV when transcription cannot start`() {
        val wav = File(field.context.cacheDir, "cancelled-transcription.wav").apply { writeText("audio") }
        scope.cancel()

        transcribe(wav)

        assertTrue((privateField("transcribeJob").get(controller) as Job).isCancelled)
        assertFalse(wav.exists())
    }

    @Test
    fun `completed transcription errors also remove the temporary WAV`() {
        prefs.activeModelId = ""
        val wav = File(field.context.cacheDir, "failed-transcription.wav").apply { writeText("audio") }
        privateField("isTranscribing").setBoolean(controller, true)
        transcribe(wav)
        val job = privateField("transcribeJob").get(controller) as Job
        val deadline = System.nanoTime() + 10_000_000_000L
        while (!job.isCompleted && System.nanoTime() < deadline) {
            shadowOf(Looper.getMainLooper()).idle()
            Thread.sleep(10)
        }

        assertTrue("Transcription did not finish", job.isCompleted)
        assertFalse(privateField("isTranscribing").getBoolean(controller))
        assertFalse(wav.exists())
    }

    private fun setText(text: String) {
        field.setText(text)
        field.setSelection(field.text.length)
    }

    private fun recognize(text: String) {
        KeyboardController::class.java.getDeclaredMethod("handleDictation", String::class.java)
            .apply { isAccessible = true }
            .invoke(controller, text)
    }

    private fun privateField(name: String) = KeyboardController::class.java.getDeclaredField(name)
        .apply { isAccessible = true }

    private fun sessionId() = privateField("inputSessionId").getLong(controller)

    private fun preparePendingTranscription(): Job = Job().also {
        privateField("isTranscribing").setBoolean(controller, true)
        privateField("transcribeJob").set(controller, it)
    }

    private fun switchEditor(text: String) {
        field = EditText(field.context).apply {
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE
        }
        connection = field.onCreateInputConnection(EditorInfo())!!
        setText(text)
    }

    private fun deliver(result: LocalAsrEngine.Result, sessionId: Long, source: InputConnection) {
        KeyboardController::class.java.getDeclaredMethod(
            "applyTranscriptionResult", LocalAsrEngine.Result::class.java,
            Long::class.javaPrimitiveType, InputConnection::class.java,
        ).apply { isAccessible = true }.invoke(controller, result, sessionId, source)
    }

    private fun transcribe(wav: File) {
        KeyboardController::class.java.getDeclaredMethod("transcribeRecording", File::class.java)
            .apply { isAccessible = true }.invoke(controller, wav)
    }
}
