package me.trion.whispertype.util

import android.content.Context
import android.content.SharedPreferences
import androidx.preference.PreferenceManager
import me.trion.whispertype.voice.ModelCatalog

class Prefs(context: Context) {
    private val prefs: SharedPreferences =
        PreferenceManager.getDefaultSharedPreferences(context.applicationContext)

    var modelId: String
        get() {
            val id = prefs.getString(KEY_MODEL_ID, null)
            val valid = ModelCatalog.models.any { it.id == id }
            return if (valid) id!! else ModelCatalog.models.first { it.recommended }.id
        }
        set(value) = prefs.edit().putString(KEY_MODEL_ID, value).apply()

    var haptic: Boolean
        get() = prefs.getBoolean(KEY_HAPTIC, true)
        set(value) = prefs.edit().putBoolean(KEY_HAPTIC, value).apply()

    var autoSpace: Boolean
        get() = prefs.getBoolean(KEY_AUTO_SPACE, true)
        set(value) = prefs.edit().putBoolean(KEY_AUTO_SPACE, value).apply()

    companion object {
        const val KEY_MODEL_ID = "model_id"
        const val KEY_HAPTIC = "haptic"
        const val KEY_AUTO_SPACE = "auto_space"
    }
}
