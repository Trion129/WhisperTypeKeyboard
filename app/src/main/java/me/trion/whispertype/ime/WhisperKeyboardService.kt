package me.trion.whispertype.ime

import android.Manifest
import android.content.ClipDescription
import android.content.ClipboardManager
import android.content.pm.PackageManager
import android.inputmethodservice.InputMethodService
import android.os.Build
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.text.InputType
import android.view.View
import android.view.inputmethod.EditorInfo
import androidx.core.content.ContextCompat
import me.trion.whispertype.R
import me.trion.whispertype.util.Prefs
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel

class WhisperKeyboardService : InputMethodService() {
    private val serviceJob = SupervisorJob()
    private val serviceScope = CoroutineScope(Dispatchers.Main + serviceJob)
    private var controller: KeyboardController? = null
    private var prefs: Prefs? = null
    private var clipboard: ClipboardManager? = null

    private val clipListener = ClipboardManager.OnPrimaryClipChangedListener {
        val cm = clipboard ?: return@OnPrimaryClipChangedListener
        val clip = cm.primaryClip ?: return@OnPrimaryClipChangedListener
        if (clip.itemCount == 0) return@OnPrimaryClipChangedListener
        val desc = clip.description
        val sensitive = isSensitive(desc) || isPasswordField()
        val text = clip.getItemAt(0).coerceToText(this)?.toString() ?: return@OnPrimaryClipChangedListener
        controller?.onPrimaryClip(text, sensitive)
    }

    override fun onCreate() {
        super.onCreate()
        prefs = Prefs(this)
        clipboard = getSystemService(CLIPBOARD_SERVICE) as? ClipboardManager
    }

    override fun onCreateInputView(): View {
        val root = layoutInflater.inflate(R.layout.keyboard_view, null)
        controller = KeyboardController(
            context = this,
            root = root,
            scope = serviceScope,
            inputConnectionProvider = { currentInputConnection },
            editorInfoProvider = { currentInputEditorInfo },
            performHaptic = { haptic() },
            requestMicPermission = { hasMicPermission() },
            switchToNextIme = {
                Build.VERSION.SDK_INT >= Build.VERSION_CODES.P && switchToNextInputMethod(false)
            },
            shouldOfferImeSwitch = {
                Build.VERSION.SDK_INT >= Build.VERSION_CODES.P && shouldOfferSwitchingToNextInputMethod()
            },
        )
        return root
    }

    override fun onStartInputView(info: EditorInfo?, restarting: Boolean) {
        super.onStartInputView(info, restarting)
        controller?.onStartInput()
        clipboard?.addPrimaryClipChangedListener(clipListener)
    }

    override fun onFinishInputView(finishingInput: Boolean) {
        clipboard?.removePrimaryClipChangedListener(clipListener)
        controller?.onFinishInput()
        super.onFinishInputView(finishingInput)
    }

    override fun onDestroy() {
        clipboard?.removePrimaryClipChangedListener(clipListener)
        controller?.destroy()
        controller = null
        serviceScope.cancel()
        super.onDestroy()
    }

    private fun isPasswordField(): Boolean {
        val info = currentInputEditorInfo ?: return false
        val variation = info.inputType and InputType.TYPE_MASK_VARIATION
        return variation == InputType.TYPE_TEXT_VARIATION_PASSWORD ||
            variation == InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD ||
            variation == InputType.TYPE_TEXT_VARIATION_WEB_PASSWORD ||
            variation == InputType.TYPE_NUMBER_VARIATION_PASSWORD
    }

    private fun isSensitive(desc: ClipDescription): Boolean {
        if (Build.VERSION.SDK_INT >= 33 && desc.extras?.getBoolean(ClipDescription.EXTRA_IS_SENSITIVE) == true) {
            return true
        }
        return desc.extras?.getBoolean("android.content.extra.IS_SENSITIVE") == true
    }

    private fun hasMicPermission(): Boolean {
        return ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) ==
            PackageManager.PERMISSION_GRANTED
    }

    private fun haptic() {
        if (prefs?.haptic != true) return
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                val manager = getSystemService(VibratorManager::class.java)
                manager?.defaultVibrator?.vibrate(
                    VibrationEffect.createOneShot(12, VibrationEffect.DEFAULT_AMPLITUDE)
                )
            } else {
                @Suppress("DEPRECATION")
                val vibrator = getSystemService(VIBRATOR_SERVICE) as? Vibrator
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    vibrator?.vibrate(
                        VibrationEffect.createOneShot(12, VibrationEffect.DEFAULT_AMPLITUDE)
                    )
                } else {
                    @Suppress("DEPRECATION")
                    vibrator?.vibrate(12)
                }
            }
        } catch (_: Exception) {
        }
    }
}
