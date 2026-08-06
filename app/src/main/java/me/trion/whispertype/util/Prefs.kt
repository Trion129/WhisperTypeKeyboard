package me.trion.whispertype.util

import android.content.Context
import android.content.SharedPreferences
import androidx.preference.PreferenceManager

class Prefs(context: Context) {
    private val prefs: SharedPreferences =
        PreferenceManager.getDefaultSharedPreferences(context.applicationContext)

    var haptic: Boolean
        get() = prefs.getBoolean(KEY_HAPTIC, true)
        set(value) = prefs.edit().putBoolean(KEY_HAPTIC, value).apply()

    var autoSpace: Boolean
        get() = prefs.getBoolean(KEY_AUTO_SPACE, true)
        set(value) = prefs.edit().putBoolean(KEY_AUTO_SPACE, value).apply()

    var modelSource: String
        get() = prefs.getString(KEY_MODEL_SOURCE, "") ?: ""
        set(value) = prefs.edit().putString(KEY_MODEL_SOURCE, value).apply()

    fun clearModelSource() {
        prefs.edit().remove(KEY_MODEL_SOURCE).apply()
    }

    companion object {
        const val KEY_HAPTIC = "haptic"
        const val KEY_AUTO_SPACE = "auto_space"
        const val KEY_MODEL_SOURCE = "model_source"
        const val MODEL_SOURCE_DOWNLOAD = "download"
        const val MODEL_SOURCE_IMPORT = "import"
    }
}
