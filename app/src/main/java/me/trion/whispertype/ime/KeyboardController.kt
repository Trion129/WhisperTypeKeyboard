package me.trion.whispertype.ime

import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.text.InputType
import android.view.Gravity
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputConnection
import android.widget.Button
import android.widget.FrameLayout
import android.widget.HorizontalScrollView
import android.widget.ImageButton
import android.widget.LinearLayout
import android.widget.PopupWindow
import android.widget.ScrollView
import android.widget.TextView
import androidx.core.content.ContextCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import me.trion.whispertype.R
import me.trion.whispertype.settings.SettingsActivity
import me.trion.whispertype.util.Prefs
import me.trion.whispertype.voice.AudioRecorder
import me.trion.whispertype.voice.LocalAsrEngine
import java.io.File

class KeyboardController(
    private val context: Context,
    private val root: View,
    private val scope: CoroutineScope,
    private val inputConnectionProvider: () -> InputConnection?,
    private val editorInfoProvider: () -> EditorInfo?,
    private val performHaptic: () -> Unit,
    private val requestMicPermission: () -> Boolean,
    private val switchToNextIme: () -> Boolean = { false },
    private val shouldOfferImeSwitch: () -> Boolean = { false },
    private val clipboardStore: ClipboardStore,
) {
    private val prefs = Prefs(context)
    private val asr = LocalAsrEngine(context)
    private val recorder = AudioRecorder()
    private val limiter = RecordingLimiter()
    private val mainHandler = Handler(Looper.getMainLooper())
    private val unigramStore = UnigramStore(File(context.filesDir, "unigrams.json"))

    private val statusText: TextView = root.findViewById(R.id.status_text)
    private val settingsBtn: TextView = root.findViewById(R.id.btn_settings)
    private val clipboardBtn: TextView = root.findViewById(R.id.btn_clipboard)
    private val emojiStripBtn: TextView = root.findViewById(R.id.btn_emoji_strip)
    private val incognitoGlyph: TextView = root.findViewById(R.id.incognito_glyph)
    private val suggestionRow: LinearLayout = root.findViewById(R.id.suggestion_row)
    private val cursorBar: LinearLayout = root.findViewById(R.id.cursor_bar)
    private val panelHost: FrameLayout = root.findViewById(R.id.panel_host)
    private val rows = listOf<LinearLayout>(
        root.findViewById(R.id.row0),
        root.findViewById(R.id.row1),
        root.findViewById(R.id.row2),
        root.findViewById(R.id.row3),
        root.findViewById(R.id.row4)
    )

    private var mode = KeyboardLayout.Mode.LETTERS
    private var shiftState = ShiftState.OFF
    private var isListening = false
    private var isTranscribing = false
    private var micButton: View? = null
    private var deleteJob: Runnable? = null
    private var transcribeJob: Job? = null
    private var activePopup: PopupWindow? = null
    private var warnedHaptic = false
    private var lastSpaceAt = 0L
    private var selecting = false
    private var cursorVisible = false
    private var panel: Panel = Panel.NONE
    private var clearArmedUntil = 0L
    private var emojiCatalog: EmojiCatalog? = null
    private var suggestionEngine: SuggestionEngine? = null
    private var lastSuggestions: List<String> = emptyList()
    private var emojiQuery: TextView? = null
    private var emojiFilter: ((String) -> Unit)? = null

    private enum class ShiftState { OFF, ONCE, LOCKED }
    private enum class Panel { NONE, EMOJI, CLIPBOARD }

    init {
        settingsBtn.setOnClickListener {
            val intent = Intent(context, SettingsActivity::class.java).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(intent)
        }
        clipboardBtn.setOnClickListener { togglePanel(Panel.CLIPBOARD) }
        emojiStripBtn.setOnClickListener {
            mode = KeyboardLayout.Mode.EMOJI
            togglePanel(Panel.EMOJI)
            rebuildKeys()
        }
        recorder.onBytes = { bytes ->
            mainHandler.post { onRecordingBytes(bytes) }
        }
        buildCursorBar()
        rebuildKeys()
        refreshReadyStatus()
        refreshSuggestions()
    }

    fun onStartInput() {
        if (!isListening && !isTranscribing) {
            refreshReadyStatus()
        }
        refreshSuggestions()
        incognitoGlyph.visibility = if (isPrivate()) View.VISIBLE else View.GONE
    }

    fun onFinishInput() {
        if (isListening) {
            isListening = false
            micButton?.isSelected = false
            recorder.cancel()
        }
        activePopup?.dismiss()
        activePopup = null
    }

    fun destroy() {
        deleteJob?.let { mainHandler.removeCallbacks(it) }
        transcribeJob?.cancel()
        activePopup?.dismiss()
        activePopup = null
        recorder.onBytes = null
        recorder.cancel()
        asr.release()
    }

    fun onClipboardChanged() {
        if (panel == Panel.CLIPBOARD) showClipboardPanel()
    }

    private fun isPrivate(): Boolean {
        if (prefs.incognito) return true
        val flags = editorInfoProvider()?.imeOptions ?: 0
        return flags and EditorInfo.IME_FLAG_NO_PERSONALIZED_LEARNING != 0
    }

    private fun fieldVariation(): FieldVariation {
        val info = editorInfoProvider() ?: return FieldVariation.NORMAL
        val cls = info.inputType and InputType.TYPE_MASK_CLASS
        val varBits = info.inputType and InputType.TYPE_MASK_VARIATION
        if (cls == InputType.TYPE_CLASS_NUMBER || cls == InputType.TYPE_CLASS_PHONE) {
            return FieldVariation.NUMBER
        }
        return when (varBits) {
            InputType.TYPE_TEXT_VARIATION_URI -> FieldVariation.URI
            InputType.TYPE_TEXT_VARIATION_EMAIL_ADDRESS,
            InputType.TYPE_TEXT_VARIATION_WEB_EMAIL_ADDRESS -> FieldVariation.EMAIL
            InputType.TYPE_TEXT_VARIATION_PASSWORD,
            InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD,
            InputType.TYPE_TEXT_VARIATION_WEB_PASSWORD -> FieldVariation.PASSWORD
            else -> FieldVariation.NORMAL
        }
    }

    private fun refreshReadyStatus() {
        val status = if (asr.isModelReady()) {
            context.getString(R.string.status_ready)
        } else {
            context.getString(R.string.error_no_model)
        }
        setStatus(status, warning = false, showStatus = false)
    }

    private fun rebuildKeys() {
        val layout = KeyboardLayout.rowsFor(mode)
        rows[0].visibility = View.GONE
        val hideLetterRows = panel != Panel.NONE
        for (i in 1..3) {
            rows[i].visibility = if (hideLetterRows) View.GONE else View.VISIBLE
        }
        rows[4].visibility = View.VISIBLE
        panelHost.visibility = if (panel != Panel.NONE) View.VISIBLE else View.GONE
        emojiStripBtn.visibility =
            if (mode == KeyboardLayout.Mode.EMOJI || panel == Panel.EMOJI) View.GONE else View.VISIBLE

        val mapped: List<List<KeyDef>> = listOf(emptyList<KeyDef>()) + layout
        rows.forEachIndexed { index, row ->
            row.removeAllViews()
            val keys = mapped.getOrNull(index) ?: return@forEachIndexed
            val offerGlobe = shouldOfferImeSwitch()
            keys.forEach { key ->
                if (key.type == KeyType.GLOBE && !offerGlobe) return@forEach
                row.addView(createKeyView(key))
            }
        }
        when (panel) {
            Panel.EMOJI -> showEmojiPanel()
            Panel.CLIPBOARD -> showClipboardPanel()
            Panel.NONE -> panelHost.removeAllViews()
        }
    }

    private fun createKeyView(key: KeyDef): View {
        val density = context.resources.displayMetrics.density
        val margin = (3 * density).toInt()
        val params = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.MATCH_PARENT, key.weight).apply {
            setMargins(margin, 0, margin, 0)
        }

        val view: View = if (key.type == KeyType.MIC || key.type == KeyType.BACKSPACE || key.type == KeyType.SHIFT) {
            ImageButton(context).apply {
                scaleType = android.widget.ImageView.ScaleType.CENTER_INSIDE
                setPadding((10 * density).toInt(), (8 * density).toInt(), (10 * density).toInt(), (8 * density).toInt())
                setColorFilter(ContextCompat.getColor(context, R.color.key_text))
                when (key.type) {
                    KeyType.MIC -> {
                        setImageResource(R.drawable.ic_mic)
                        micButton = this
                        isSelected = isListening
                    }
                    KeyType.BACKSPACE -> setImageResource(R.drawable.ic_backspace)
                    KeyType.SHIFT -> {
                        setImageResource(R.drawable.ic_shift)
                        alpha = if (shiftState == ShiftState.OFF) 0.7f else 1f
                    }
                    else -> Unit
                }
            }
        } else {
            Button(context).apply {
                text = displayLabel(key)
                isAllCaps = false
                textSize = when (key.type) {
                    KeyType.SPACE -> 14f
                    KeyType.MODE_123, KeyType.MODE_ABC, KeyType.MODE_SYMBOLS, KeyType.GLOBE -> 13f
                    KeyType.MODE_EMOJI -> 16f
                    else -> 18f
                }
                setTextColor(ContextCompat.getColor(context, R.color.key_text))
                setPadding(0, 0, 0, 0)
                stateListAnimator = null
                elevation = 0f
                minimumWidth = 0
                minimumHeight = 0
            }
        }

        view.layoutParams = params
        view.background = ContextCompat.getDrawable(
            context,
            when (key.type) {
                KeyType.MIC -> R.drawable.key_background_mic
                KeyType.ENTER -> R.drawable.key_background_action
                KeyType.SHIFT, KeyType.BACKSPACE, KeyType.MODE_123, KeyType.MODE_ABC,
                KeyType.MODE_SYMBOLS, KeyType.MODE_EMOJI, KeyType.GLOBE ->
                    R.drawable.key_background_special
                else -> R.drawable.key_background
            }
        )

        when (key.type) {
            KeyType.BACKSPACE -> bindBackspace(view)
            KeyType.MIC -> view.setOnClickListener { onMicTapped() }
            KeyType.SPACE -> bindSpace(view, key)
            else -> {
                val popups = resolvedPopups(key)
                if (popups.isNotEmpty()) {
                    bindKeyWithPopup(view, key, popups)
                } else {
                    view.setOnClickListener {
                        performHaptic()
                        onKey(key)
                    }
                }
            }
        }
        return view
    }

    private fun resolvedPopups(key: KeyDef): List<String> {
        if (key.popupLabels.isNotEmpty()) {
            return if (shouldUppercase() && key.type == KeyType.CHAR) {
                KeyPopupCatalog.popupsFor(key.label, uppercase = true)
            } else {
                key.popupLabels
            }
        }
        return emptyList()
    }

    private fun displayLabel(key: KeyDef): String {
        return when (key.type) {
            KeyType.CHAR -> if (shouldUppercase()) key.shiftLabel.ifEmpty { key.label.uppercase() } else key.label
            KeyType.SPACE -> "space"
            KeyType.SHIFT -> when (shiftState) {
                ShiftState.LOCKED -> "⇪"
                ShiftState.ONCE -> "⇧"
                ShiftState.OFF -> "⇧"
            }
            else -> key.label
        }
    }

    private fun shouldUppercase(): Boolean {
        if (mode != KeyboardLayout.Mode.LETTERS) return false
        if (shiftState != ShiftState.OFF) return true
        if (!prefs.sentenceCaps) return false
        val before = inputConnectionProvider()?.getTextBeforeCursor(64, 0)?.toString() ?: ""
        return TypingRules.shouldSentenceCap(before, fieldVariation())
    }

    private fun bindBackspace(view: View) {
        var downX = 0f
        view.setOnTouchListener { v, event ->
            when (event.action) {
                MotionEvent.ACTION_DOWN -> {
                    v.isPressed = true
                    downX = event.x
                    performHaptic()
                    deleteOnce()
                    val repeater = object : Runnable {
                        override fun run() {
                            deleteOnce()
                            mainHandler.postDelayed(this, 50)
                        }
                    }
                    deleteJob = repeater
                    mainHandler.postDelayed(repeater, 400)
                    true
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    v.isPressed = false
                    deleteJob?.let { mainHandler.removeCallbacks(it) }
                    deleteJob = null
                    if (event.action == MotionEvent.ACTION_UP && downX - event.x > 48f) {
                        deleteWord()
                    }
                    refreshSuggestions()
                    true
                }
                else -> false
            }
        }
    }

    private fun bindSpace(view: View, key: KeyDef) {
        var longPressed = false
        val longPress = Runnable {
            longPressed = true
            performHaptic()
            cursorVisible = !cursorVisible
            cursorBar.visibility = if (cursorVisible) View.VISIBLE else View.GONE
            if (!cursorVisible) selecting = false
        }
        view.setOnTouchListener { v, event ->
            when (event.action) {
                MotionEvent.ACTION_DOWN -> {
                    longPressed = false
                    v.isPressed = true
                    v.postDelayed(longPress, 400)
                    true
                }
                MotionEvent.ACTION_UP -> {
                    v.removeCallbacks(longPress)
                    v.isPressed = false
                    if (!longPressed) {
                        performHaptic()
                        onKey(key)
                    }
                    true
                }
                MotionEvent.ACTION_CANCEL -> {
                    v.removeCallbacks(longPress)
                    v.isPressed = false
                    true
                }
                else -> false
            }
        }
    }

    private fun onKey(key: KeyDef) {
        val ic = inputConnectionProvider() ?: return
        when (key.type) {
            KeyType.CHAR -> {
                val text = if (shouldUppercase()) {
                    key.shiftLabel.ifEmpty { key.label.uppercase() }
                } else {
                    key.label
                }
                commitTyped(text)
                if (shiftState == ShiftState.ONCE) {
                    shiftState = ShiftState.OFF
                    rebuildKeys()
                }
            }
            KeyType.SPACE -> commitSpace()
            KeyType.COMMA -> commitTyped(",")
            KeyType.PERIOD -> commitTyped(".")
            KeyType.ENTER -> {
                learnCurrentWord()
                val editorInfo = editorInfoProvider()
                val action = editorInfo?.imeOptions?.and(EditorInfo.IME_MASK_ACTION) ?: EditorInfo.IME_ACTION_NONE
                if (action != EditorInfo.IME_ACTION_NONE && action != EditorInfo.IME_ACTION_UNSPECIFIED &&
                    (editorInfo?.imeOptions?.and(EditorInfo.IME_FLAG_NO_ENTER_ACTION) == 0)
                ) {
                    ic.performEditorAction(action)
                } else {
                    ic.sendKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_ENTER))
                    ic.sendKeyEvent(KeyEvent(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_ENTER))
                }
                refreshSuggestions()
            }
            KeyType.SHIFT -> {
                shiftState = when (shiftState) {
                    ShiftState.OFF -> ShiftState.ONCE
                    ShiftState.ONCE -> ShiftState.LOCKED
                    ShiftState.LOCKED -> ShiftState.OFF
                }
                rebuildKeys()
            }
            KeyType.MODE_123 -> {
                mode = KeyboardLayout.Mode.NUMBERS
                closePanel()
                rebuildKeys()
            }
            KeyType.MODE_EMOJI -> {
                mode = KeyboardLayout.Mode.EMOJI
                togglePanel(Panel.EMOJI)
                rebuildKeys()
            }
            KeyType.MODE_SYMBOLS -> {
                mode = KeyboardLayout.Mode.SYMBOLS
                closePanel()
                rebuildKeys()
            }
            KeyType.MODE_ABC -> {
                mode = KeyboardLayout.Mode.LETTERS
                closePanel()
                rebuildKeys()
            }
            KeyType.GLOBE -> switchToNextIme()
            KeyType.BACKSPACE, KeyType.MIC -> Unit
        }
    }

    private fun commitTyped(text: String) {
        if (panel == Panel.EMOJI && text.all { it.isLetterOrDigit() || it == ' ' }) {
            val next = (emojiQuery?.text?.toString() ?: "") + text
            emojiFilter?.invoke(next)
            return
        }
        inputConnectionProvider()?.commitText(text, 1)
        if (text.any { !it.isLetter() }) learnCurrentWord()
        refreshSuggestions()
    }

    private fun commitSpace() {
        val ic = inputConnectionProvider() ?: return
        val now = System.currentTimeMillis()
        val before = ic.getTextBeforeCursor(8, 0)?.toString() ?: ""
        if (prefs.doubleSpacePeriod &&
            now - lastSpaceAt < 500 &&
            TypingRules.shouldAutoPeriod(before.trimEnd(), fieldVariation())
        ) {
            ic.deleteSurroundingText(1, 0)
            ic.commitText(". ", 1)
            lastSpaceAt = 0L
        } else {
            learnCurrentWord()
            ic.commitText(" ", 1)
            lastSpaceAt = now
        }
        refreshSuggestions()
    }

    private fun bindKeyWithPopup(view: View, key: KeyDef, popups: List<String>) {
        var longPressed = false
        var highlighted = -1
        val longPressRunnable = Runnable {
            longPressed = true
            performHaptic()
            showPopupWindow(view, popups)
            highlighted = -1
        }

        view.setOnTouchListener { v, event ->
            when (event.action) {
                MotionEvent.ACTION_DOWN -> {
                    activePopup?.dismiss()
                    longPressed = false
                    highlighted = -1
                    v.isPressed = true
                    v.postDelayed(longPressRunnable, 300)
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    if (longPressed) {
                        highlighted = highlightPopup(event.rawX)
                    }
                    true
                }
                MotionEvent.ACTION_UP -> {
                    v.removeCallbacks(longPressRunnable)
                    v.isPressed = false
                    if (longPressed) {
                        val label = if (highlighted in popups.indices) popups[highlighted] else null
                        if (label != null) commitPopupText(label) else onKey(key)
                        activePopup?.dismiss()
                    } else {
                        performHaptic()
                        onKey(key)
                    }
                    true
                }
                MotionEvent.ACTION_CANCEL -> {
                    v.removeCallbacks(longPressRunnable)
                    v.isPressed = false
                    true
                }
                else -> false
            }
        }
    }

    private fun highlightPopup(rawX: Float): Int {
        val popup = activePopup ?: return -1
        val content = popup.contentView as? LinearLayout ?: return -1
        var found = -1
        for (i in 0 until content.childCount) {
            val child = content.getChildAt(i)
            val loc = IntArray(2)
            child.getLocationOnScreen(loc)
            val hit = rawX >= loc[0] && rawX <= loc[0] + child.width
            child.isSelected = hit
            child.alpha = if (hit) 1f else 0.55f
            if (hit) found = i
        }
        return found
    }

    private fun showPopupWindow(anchor: View, labels: List<String>): PopupWindow {
        activePopup?.dismiss()
        activePopup = null

        val density = context.resources.displayMetrics.density
        val gap = (8 * density).toInt()

        val popupContent = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding((8 * density).toInt(), (8 * density).toInt(), (8 * density).toInt(), (8 * density).toInt())
            setBackgroundColor(ContextCompat.getColor(context, R.color.keyboard_bg))
            elevation = 6f * density
        }

        labels.forEach { label ->
            val btn = TextView(context).apply {
                text = label
                textSize = 18f
                gravity = Gravity.CENTER
                setTextColor(ContextCompat.getColor(context, R.color.key_text))
                setPadding((14 * density).toInt(), (10 * density).toInt(), (14 * density).toInt(), (10 * density).toInt())
                background = ContextCompat.getDrawable(context, R.drawable.key_background)
                isClickable = true
                isFocusable = false
            }
            popupContent.addView(
                btn,
                LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                    (44 * density).toInt()
                ).apply { setMargins((3 * density).toInt(), 0, (3 * density).toInt(), 0) }
            )
        }

        popupContent.measure(
            View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED),
            View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED)
        )
        val pw = popupContent.measuredWidth
        val ph = popupContent.measuredHeight

        val popup = PopupWindow(popupContent, pw, ph, false).apply {
            isOutsideTouchable = true
            isTouchable = true
            inputMethodMode = PopupWindow.INPUT_METHOD_NOT_NEEDED
            isClippingEnabled = false
            elevation = 6f * density
            setBackgroundDrawable(android.graphics.drawable.ColorDrawable(android.graphics.Color.TRANSPARENT))
            setOnDismissListener { if (activePopup === this) activePopup = null }
        }

        for (i in 0 until popupContent.childCount) {
            val child = popupContent.getChildAt(i)
            val label = labels[i]
            child.setOnClickListener {
                performHaptic()
                commitPopupText(label)
                anchor.isPressed = false
                popup.dismiss()
            }
        }

        val anchorLoc = IntArray(2)
        anchor.getLocationInWindow(anchorLoc)
        val x = anchorLoc[0] + (anchor.width - pw) / 2
        val y = anchorLoc[1] - ph - gap
        popup.showAtLocation(root, Gravity.NO_GRAVITY, x, y)

        activePopup = popup
        return popup
    }

    private fun commitPopupText(text: String) {
        inputConnectionProvider()?.commitText(text, 1)
        if (!isPrivate() && text.any { Character.getType(it) == Character.OTHER_SYMBOL.toInt() }) {
            rememberEmoji(text)
        }
        refreshSuggestions()
    }

    private fun deleteOnce() {
        if (panel == Panel.EMOJI) {
            val q = emojiQuery?.text?.toString().orEmpty()
            if (q.isNotEmpty()) {
                emojiFilter?.invoke(q.dropLast(1))
                return
            }
        }
        val ic = inputConnectionProvider() ?: return
        val selected = ic.getSelectedText(0)
        if (!selected.isNullOrEmpty()) {
            ic.commitText("", 1)
        } else {
            ic.deleteSurroundingText(1, 0)
        }
    }

    private fun deleteWord() {
        val ic = inputConnectionProvider() ?: return
        val selected = ic.getSelectedText(0)
        if (!selected.isNullOrEmpty()) {
            ic.commitText("", 1)
            return
        }
        val before = ic.getTextBeforeCursor(64, 0)?.toString() ?: return
        val n = TypingRules.previousWordLength(before)
        if (n > 0) ic.deleteSurroundingText(n, 0)
    }

    private fun onMicTapped() {
        if (isTranscribing) return
        if (isListening) {
            stopAndTranscribe()
        } else {
            startListening()
        }
    }

    private fun startListening() {
        if (!requestMicPermission()) {
            setStatus(context.getString(R.string.error_mic_permission), warning = false, showStatus = true)
            return
        }
        if (!asr.isModelReady()) {
            setStatus(context.getString(R.string.error_no_model), warning = false, showStatus = true)
            return
        }
        if (!recorder.start()) {
            setStatus(context.getString(R.string.error_mic_permission), warning = false, showStatus = true)
            return
        }
        isListening = true
        warnedHaptic = false
        micButton?.isSelected = true
        setStatus(context.getString(R.string.status_listening), warning = false, showStatus = true)
        performHaptic()
    }

    private fun onRecordingBytes(bytes: Int) {
        if (!isListening) return
        val tick = limiter.onBytes(bytes)
        val elapsedS = (tick.elapsedMs / 1000L).toInt()
        val remainS = (tick.remainingMs / 1000L).toInt()
        val warning = tick.phase != RecordingPhase.LISTENING
        if (tick.phase == RecordingPhase.WARN && !warnedHaptic) {
            warnedHaptic = true
            performHaptic()
        }
        setStatus(
            context.getString(R.string.status_listening_timer, elapsedS, remainS),
            warning = warning,
            showStatus = true
        )
        if (tick.phase == RecordingPhase.LIMIT) {
            stopAndTranscribe()
        }
    }

    private fun stopAndTranscribe() {
        if (!isListening && !isTranscribing) return
        isListening = false
        micButton?.isSelected = false
        isTranscribing = true
        setStatus(context.getString(R.string.status_transcribing), warning = false, showStatus = true)

        val cacheFile = File(context.cacheDir, "whisper_input_${System.currentTimeMillis()}.wav")
        val wav = recorder.stopToWav(cacheFile)
        if (wav == null) {
            isTranscribing = false
            setStatus(context.getString(R.string.error_no_audio), warning = false, showStatus = true)
            return
        }

        transcribeJob = scope.launch {
            val result = withContext(Dispatchers.Default) {
                asr.transcribeWav(wav)
            }
            withContext(Dispatchers.Main) {
                isTranscribing = false
                when (result) {
                    is LocalAsrEngine.Result.Success -> {
                        commitDictation(result.text)
                        refreshReadyStatus()
                        refreshSuggestions()
                    }
                    is LocalAsrEngine.Result.Error -> {
                        setStatus(result.message, warning = false, showStatus = true)
                    }
                }
            }
            wav.delete()
        }
    }

    private fun commitDictation(text: String) {
        val ic = inputConnectionProvider() ?: return
        var out = text.trim()
        if (out.isEmpty()) return

        val before = ic.getTextBeforeCursor(1, 0)
        if (prefs.autoSpace && !before.isNullOrEmpty()) {
            val ch = before[before.length - 1]
            if (!ch.isWhitespace() && ch != '\n') {
                out = " $out"
            }
        }
        ic.commitText(out, 1)
        if (!isPrivate()) {
            out.split(Regex("\\W+")).forEach { unigramStore.learn(it) }
        }
        performHaptic()
    }

    private fun setStatus(text: String, warning: Boolean, showStatus: Boolean) {
        statusText.text = text
        statusText.setTextColor(
            ContextCompat.getColor(context, if (warning) R.color.status_warn else R.color.key_text_secondary)
        )
        if (showStatus) {
            statusText.visibility = View.VISIBLE
            suggestionRow.visibility = View.GONE
        } else if (!isListening && !isTranscribing) {
            statusText.visibility = View.GONE
            suggestionRow.visibility = View.VISIBLE
        }
    }

    private fun engine(): SuggestionEngine {
        suggestionEngine?.let { return it }
        val words = try {
            context.assets.open("dictionary/en.txt").bufferedReader().readLines()
                .map { it.trim() }
                .filter { it.isNotEmpty() }
        } catch (_: Exception) {
            emptyList()
        }
        return SuggestionEngine(words).also { suggestionEngine = it }
    }

    private fun currentWord(): String {
        val before = inputConnectionProvider()?.getTextBeforeCursor(48, 0)?.toString() ?: return ""
        val i = before.indexOfLast { !it.isLetter() }
        return if (i < 0) before else before.substring(i + 1)
    }

    private fun refreshSuggestions() {
        if (isListening || isTranscribing) return
        suggestionRow.removeAllViews()
        val prefix = currentWord()
        lastSuggestions = engine().suggest(prefix, unigramStore.snapshot(), 3)
        val density = context.resources.displayMetrics.density
        lastSuggestions.forEach { word ->
            val chip = TextView(context).apply {
                text = word
                gravity = Gravity.CENTER
                setTextColor(ContextCompat.getColor(context, R.color.key_text))
                textSize = 14f
                setPadding((10 * density).toInt(), 0, (10 * density).toInt(), 0)
                setOnClickListener { applySuggestion(word) }
            }
            suggestionRow.addView(
                chip,
                LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.MATCH_PARENT, 1f)
            )
        }
        incognitoGlyph.visibility = if (isPrivate()) View.VISIBLE else View.GONE
    }

    private fun applySuggestion(word: String) {
        val ic = inputConnectionProvider() ?: return
        val prefix = currentWord()
        if (prefix.isNotEmpty()) ic.deleteSurroundingText(prefix.length, 0)
        ic.commitText(word, 1)
        if (!isPrivate()) unigramStore.learn(word)
        refreshSuggestions()
    }

    private fun learnCurrentWord() {
        if (isPrivate()) return
        val word = currentWord()
        if (word.length >= 2) unigramStore.learn(word)
    }

    private fun togglePanel(target: Panel) {
        panel = if (panel == target) Panel.NONE else target
        if (panel == Panel.NONE && mode == KeyboardLayout.Mode.EMOJI) {
            mode = KeyboardLayout.Mode.LETTERS
        }
        if (target == Panel.EMOJI && panel == Panel.EMOJI) {
            mode = KeyboardLayout.Mode.EMOJI
        }
        rebuildKeys()
    }

    private fun closePanel() {
        panel = Panel.NONE
        cursorVisible = false
        cursorBar.visibility = View.GONE
        selecting = false
    }

    private fun catalog(): EmojiCatalog {
        emojiCatalog?.let { return it }
        val parsed = try {
            context.assets.open("emoji/catalog.json").bufferedReader().use { EmojiCatalog.parse(it.readText()) }
        } catch (_: Exception) {
            EmojiCatalog(EmojiCatalog.DEFAULT_GROUPS, emptyList())
        }
        emojiCatalog = parsed
        return parsed
    }

    private fun rememberEmoji(emoji: String) {
        val next = (listOf(emoji) + prefs.emojiRecents()).distinct().take(24)
        prefs.setEmojiRecents(next)
    }

    private fun showEmojiPanel() {
        val cat = catalog()
        panelHost.removeAllViews()
        val density = context.resources.displayMetrics.density
        val column = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }

        val search = TextView(context).apply {
            hint = context.getString(R.string.emoji_search_hint)
            setHintTextColor(ContextCompat.getColor(context, R.color.key_text_secondary))
            setTextColor(ContextCompat.getColor(context, R.color.key_text))
            textSize = 14f
            setPadding((8 * density).toInt(), (6 * density).toInt(), (8 * density).toInt(), (6 * density).toInt())
        }
        emojiQuery = search

        val tabs = HorizontalScrollView(context).apply { isHorizontalScrollBarEnabled = false }
        val tabRow = LinearLayout(context).apply { orientation = LinearLayout.HORIZONTAL }
        tabs.addView(tabRow)
        val gridScroll = ScrollView(context)
        val grid = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
        gridScroll.addView(grid)

        fun fill(items: List<EmojiItem>) {
            grid.removeAllViews()
            val cols = 8
            var row: LinearLayout? = null
            items.forEachIndexed { index, item ->
                if (index % cols == 0) {
                    row = LinearLayout(context).apply {
                        orientation = LinearLayout.HORIZONTAL
                        layoutParams = LinearLayout.LayoutParams(
                            LinearLayout.LayoutParams.MATCH_PARENT,
                            (40 * density).toInt()
                        )
                    }
                    grid.addView(row)
                }
                val cell = TextView(context).apply {
                    text = item.emoji
                    gravity = Gravity.CENTER
                    textSize = 18f
                    setOnClickListener {
                        commitPopupText(item.emoji)
                        if (!isPrivate()) rememberEmoji(item.emoji)
                    }
                    if (item.toneCapable) {
                        setOnLongClickListener {
                            showPopupWindow(this, item.tones)
                            true
                        }
                    }
                }
                row!!.addView(cell, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.MATCH_PARENT, 1f))
            }
        }

        var activeGroup = "recents"
        fun showGroup(name: String) {
            activeGroup = name
            val items = if (name == "recents") cat.recents(prefs.emojiRecents()) else cat.inGroup(name)
            fill(items)
        }

        tabRow.removeAllViews()
        (listOf("recents") + cat.groups).forEach { name ->
            val tab = TextView(context).apply {
                text = if (name == "recents") "🕒" else name.split(' ').first()
                setTextColor(ContextCompat.getColor(context, R.color.key_text))
                textSize = 12f
                setPadding((8 * density).toInt(), (4 * density).toInt(), (8 * density).toInt(), (4 * density).toInt())
                setOnClickListener {
                    search.text = ""
                    showGroup(name)
                }
                contentDescription = name
            }
            tabRow.addView(tab)
        }

        emojiFilter = { q ->
            search.text = q
            if (q.isBlank()) showGroup(activeGroup) else fill(cat.search(q))
        }

        showGroup("recents")
        column.addView(search, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, (36 * density).toInt()))
        column.addView(tabs, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, (32 * density).toInt()))
        column.addView(gridScroll, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f))
        panelHost.addView(
            column,
            FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT)
        )
    }

    private fun showClipboardPanel() {
        panelHost.removeAllViews()
        val density = context.resources.displayMetrics.density
        val column = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
        val header = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        val title = TextView(context).apply {
            text = context.getString(R.string.clipboard_title)
            setTextColor(ContextCompat.getColor(context, R.color.key_text))
            textSize = 14f
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        }
        val clear = TextView(context).apply {
            text = context.getString(R.string.clipboard_clear)
            setTextColor(ContextCompat.getColor(context, R.color.key_text_secondary))
            textSize = 13f
            setPadding((8 * density).toInt(), (4 * density).toInt(), (8 * density).toInt(), (4 * density).toInt())
            setOnClickListener {
                val now = System.currentTimeMillis()
                if (now < clearArmedUntil) {
                    clipboardStore.clear()
                    clearArmedUntil = 0L
                    showClipboardPanel()
                } else {
                    clearArmedUntil = now + 2000
                    text = context.getString(R.string.clipboard_clear_confirm)
                }
            }
        }
        header.addView(title)
        header.addView(clear)
        column.addView(header)

        val items = clipboardStore.snapshot()
        if (items.isEmpty()) {
            val empty = TextView(context).apply {
                text = context.getString(R.string.clipboard_empty)
                setTextColor(ContextCompat.getColor(context, R.color.key_text_secondary))
                gravity = Gravity.CENTER
                setPadding(0, (24 * density).toInt(), 0, 0)
            }
            column.addView(empty)
        } else {
            val list = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
            items.forEach { item ->
                val row = LinearLayout(context).apply {
                    orientation = LinearLayout.HORIZONTAL
                    gravity = Gravity.CENTER_VERTICAL
                    setPadding((8 * density).toInt(), (8 * density).toInt(), (8 * density).toInt(), (8 * density).toInt())
                }
                val preview = TextView(context).apply {
                    text = item.text.replace('\n', ' ')
                    setTextColor(ContextCompat.getColor(context, R.color.key_text))
                    maxLines = 1
                    ellipsize = android.text.TextUtils.TruncateAt.END
                    layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
                    setOnClickListener {
                        inputConnectionProvider()?.commitText(item.text, 1)
                    }
                }
                val del = TextView(context).apply {
                    text = "×"
                    setTextColor(ContextCompat.getColor(context, R.color.key_text_secondary))
                    textSize = 18f
                    setPadding((10 * density).toInt(), 0, (4 * density).toInt(), 0)
                    setOnClickListener {
                        clipboardStore.delete(item.id)
                        showClipboardPanel()
                    }
                }
                var startX = 0f
                row.setOnTouchListener { _, ev ->
                    when (ev.action) {
                        MotionEvent.ACTION_DOWN -> {
                            startX = ev.x
                            false
                        }
                        MotionEvent.ACTION_UP -> {
                            if (startX - ev.x > 80f) {
                                clipboardStore.delete(item.id)
                                showClipboardPanel()
                                true
                            } else false
                        }
                        else -> false
                    }
                }
                row.addView(preview)
                row.addView(del)
                list.addView(row)
            }
            val scroller = ScrollView(context)
            scroller.addView(list)
            column.addView(scroller, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f))
        }
        panelHost.addView(column, FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.MATCH_PARENT,
            FrameLayout.LayoutParams.MATCH_PARENT
        ))
    }

    private fun buildCursorBar() {
        cursorBar.removeAllViews()
        val actions = listOf(
            R.string.cursor_word_left to { moveByWord(-1) },
            R.string.cursor_left to { moveBy(KeyEvent.KEYCODE_DPAD_LEFT) },
            R.string.cursor_sel to {
                selecting = !selecting
            },
            R.string.cursor_right to { moveBy(KeyEvent.KEYCODE_DPAD_RIGHT) },
            R.string.cursor_word_right to { moveByWord(1) },
        )
        actions.forEach { (res, action) ->
            val btn = TextView(context).apply {
                text = context.getString(res)
                gravity = Gravity.CENTER
                setTextColor(ContextCompat.getColor(context, R.color.key_text))
                textSize = 13f
                setOnClickListener {
                    performHaptic()
                    action()
                }
            }
            cursorBar.addView(btn, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.MATCH_PARENT, 1f))
        }
    }

    private fun moveBy(code: Int) {
        val ic = inputConnectionProvider() ?: return
        val meta = if (selecting) KeyEvent.META_SHIFT_ON else 0
        ic.sendKeyEvent(KeyEvent(0, 0, KeyEvent.ACTION_DOWN, code, 0, meta))
        ic.sendKeyEvent(KeyEvent(0, 0, KeyEvent.ACTION_UP, code, 0, meta))
    }

    private fun moveByWord(dir: Int) {
        val ic = inputConnectionProvider() ?: return
        val before = ic.getTextBeforeCursor(80, 0)?.toString() ?: ""
        val after = ic.getTextAfterCursor(80, 0)?.toString() ?: ""
        val steps = if (dir < 0) {
            TypingRules.previousWordLength(before)
        } else {
            var i = 0
            while (i < after.length && after[i].isWhitespace()) i++
            while (i < after.length && !after[i].isWhitespace()) i++
            i
        }
        val code = if (dir < 0) KeyEvent.KEYCODE_DPAD_LEFT else KeyEvent.KEYCODE_DPAD_RIGHT
        repeat(steps) { moveBy(code) }
    }
}
