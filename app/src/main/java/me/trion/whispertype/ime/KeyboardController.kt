package me.trion.whispertype.ime

import android.content.Context
import android.content.Intent
import android.os.Handler
import android.os.Looper
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.view.inputmethod.EditorInfo
import android.widget.PopupWindow
import android.view.inputmethod.InputConnection
import android.widget.Button
import android.widget.ImageButton
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.content.ContextCompat
import me.trion.whispertype.R
import me.trion.whispertype.settings.SettingsActivity
import me.trion.whispertype.util.Prefs
import me.trion.whispertype.voice.AudioRecorder
import me.trion.whispertype.voice.LocalAsrEngine
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

class KeyboardController(
    private val context: Context,
    private val root: View,
    private val scope: CoroutineScope,
    private val inputConnectionProvider: () -> InputConnection?,
    private val editorInfoProvider: () -> EditorInfo?,
    private val performHaptic: () -> Unit,
    private val requestMicPermission: () -> Boolean
) {
    private val prefs = Prefs(context)
    private val asr = LocalAsrEngine(context)
    private val recorder = AudioRecorder()
    private val mainHandler = Handler(Looper.getMainLooper())

    private val statusText: TextView = root.findViewById(R.id.status_text)
    private val settingsBtn: TextView = root.findViewById(R.id.btn_settings)
    private val rows = listOf<LinearLayout>(
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

    private enum class ShiftState { OFF, ONCE, LOCKED }

    init {
        settingsBtn.setOnClickListener {
            val intent = Intent(context, SettingsActivity::class.java).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(intent)
        }
        rebuildKeys()
        refreshReadyStatus()
    }

    fun onStartInput() {
        if (!isListening && !isTranscribing) {
            refreshReadyStatus()
        }
    }

    fun destroy() {
        deleteJob?.let { mainHandler.removeCallbacks(it) }
        transcribeJob?.cancel()
        activePopup?.dismiss()
        activePopup = null
        recorder.cancel()
        asr.release()
    }

    private fun refreshReadyStatus() {
        val status = if (asr.isModelReady()) {
            context.getString(R.string.status_ready)
        } else {
            context.getString(R.string.error_no_model)
        }
        setStatus(status)
    }

    private fun rebuildKeys() {
        val layout = KeyboardLayout.rowsFor(mode)
        rows.forEachIndexed { index, row ->
            row.removeAllViews()
            val keys = layout.getOrNull(index) ?: return@forEachIndexed
            keys.forEach { key ->
                row.addView(createKeyView(key))
            }
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
                    KeyType.MODE_123, KeyType.MODE_ABC, KeyType.MODE_SYMBOLS -> 13f
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
                KeyType.SHIFT, KeyType.BACKSPACE, KeyType.MODE_123, KeyType.MODE_ABC, KeyType.MODE_SYMBOLS, KeyType.MODE_EMOJI ->
                    R.drawable.key_background_special
                else -> R.drawable.key_background
            }
        )

        when (key.type) {
            KeyType.BACKSPACE -> bindBackspace(view)
            KeyType.MIC -> view.setOnClickListener { onMicTapped() }
            else -> {
                if (key.popupLabels.isNotEmpty()) {
                    bindKeyWithPopup(view, key)
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

    private fun shouldUppercase(): Boolean = shiftState != ShiftState.OFF && mode == KeyboardLayout.Mode.LETTERS

    private fun bindBackspace(view: View) {
        view.setOnTouchListener { v, event ->
            when (event.action) {
                MotionEvent.ACTION_DOWN -> {
                    v.isPressed = true
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
                ic.commitText(text, 1)
                if (shiftState == ShiftState.ONCE) {
                    shiftState = ShiftState.OFF
                    rebuildKeys()
                }
            }
            KeyType.SPACE -> ic.commitText(" ", 1)
            KeyType.COMMA -> ic.commitText(",", 1)
            KeyType.PERIOD -> ic.commitText(".", 1)
            KeyType.ENTER -> {
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
                rebuildKeys()
            }
            KeyType.MODE_EMOJI -> {
                mode = KeyboardLayout.Mode.EMOJI
                rebuildKeys()
            }
            KeyType.MODE_SYMBOLS -> {
                mode = KeyboardLayout.Mode.SYMBOLS
                rebuildKeys()
            }
            KeyType.MODE_ABC -> {
                mode = KeyboardLayout.Mode.LETTERS
                rebuildKeys()
            }
            KeyType.BACKSPACE, KeyType.MIC -> Unit
        }
    }

    private fun bindKeyWithPopup(view: View, key: KeyDef) {
        var longPressed = false
        var popup: PopupWindow? = null
        val longPressRunnable = Runnable {
            longPressed = true
            performHaptic()
            popup = showPopupWindow(view, key)
        }

        view.setOnTouchListener { v, event ->
            when (event.action) {
                MotionEvent.ACTION_DOWN -> {
                    activePopup?.dismiss()
                    longPressed = false
                    v.isPressed = true
                    v.postDelayed(longPressRunnable, 300)
                    true
                }
                MotionEvent.ACTION_UP -> {
                    v.removeCallbacks(longPressRunnable)
                    v.isPressed = false
                    if (!longPressed) {
                        performHaptic()
                        onKey(key)
                    }
                    true
                }
                MotionEvent.ACTION_CANCEL -> {
                    v.removeCallbacks(longPressRunnable)
                    v.isPressed = false
                    // leave popup if open (user might move) - or dismiss:
                    // optional: don't dismiss on cancel from key
                    true
                }
                else -> false
            }
        }
    }

    private fun showPopupWindow(anchor: View, key: KeyDef): PopupWindow {
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

        key.popupLabels.forEach { label ->
            val btn = TextView(context).apply {  // TextView not Button - Buttons can request focus
                text = label
                textSize = 18f
                gravity = android.view.Gravity.CENTER
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

        // focusable = false  <<< critical for IME
        val popup = PopupWindow(popupContent, pw, ph, /* focusable */ false).apply {
            isOutsideTouchable = true
            isTouchable = true
            inputMethodMode = PopupWindow.INPUT_METHOD_NOT_NEEDED
            isClippingEnabled = false
            elevation = 6f * density
            // Required so outsideTouchable works:
            setBackgroundDrawable(android.graphics.drawable.ColorDrawable(android.graphics.Color.TRANSPARENT))
            setOnDismissListener { if (activePopup === this) activePopup = null }
        }

        for (i in 0 until popupContent.childCount) {
            val child = popupContent.getChildAt(i)
            val label = key.popupLabels[i]
            child.setOnClickListener {
                performHaptic()
                commitPopupText(label)
                anchor.isPressed = false
                popup.dismiss()
            }
        }

        // Position in IME window coords (not screen) - more stable for InputMethodService
        val anchorLoc = IntArray(2)
        anchor.getLocationInWindow(anchorLoc)
        val x = anchorLoc[0] + (anchor.width - pw) / 2
        val y = anchorLoc[1] - ph - gap
        popup.showAtLocation(root, android.view.Gravity.NO_GRAVITY, x, y)

        activePopup = popup
        return popup
    }

    private fun commitPopupText(text: String) {
        inputConnectionProvider()?.commitText(text, 1)
    }

    private fun deleteOnce() {
        val ic = inputConnectionProvider() ?: return
        val selected = ic.getSelectedText(0)
        if (!selected.isNullOrEmpty()) {
            ic.commitText("", 1)
        } else {
            ic.deleteSurroundingText(1, 0)
        }
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
            setStatus(context.getString(R.string.error_mic_permission))
            return
        }
        if (!asr.isModelReady()) {
            setStatus(context.getString(R.string.error_no_model))
            return
        }
        if (!recorder.start()) {
            setStatus(context.getString(R.string.error_mic_permission))
            return
        }
        isListening = true
        micButton?.isSelected = true
        setStatus(context.getString(R.string.status_listening))
        performHaptic()
    }

    private fun stopAndTranscribe() {
        isListening = false
        micButton?.isSelected = false
        isTranscribing = true
        setStatus(context.getString(R.string.status_transcribing))

        val cacheFile = File(context.cacheDir, "whisper_input_${System.currentTimeMillis()}.wav")
        val wav = recorder.stopToWav(cacheFile)
        if (wav == null) {
            isTranscribing = false
            setStatus(context.getString(R.string.error_no_audio))
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
                    }
                    is LocalAsrEngine.Result.Error -> {
                        setStatus(result.message)
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
        performHaptic()
    }

    private fun setStatus(text: String) {
        statusText.text = text
    }
}
