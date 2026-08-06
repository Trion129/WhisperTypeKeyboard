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

    var activeModelId: String
        get() = prefs.getString(KEY_ACTIVE_MODEL_ID, "") ?: ""
        set(value) = prefs.edit().putString(KEY_ACTIVE_MODEL_ID, value).apply()

    fun clearActiveModelId() {
        prefs.edit().remove(KEY_ACTIVE_MODEL_ID).apply()
    }

    /**
     * One-time migration from the old RTranslator pipeline: the obsolete
     * model_source key (download/import) is dropped if it still exists.
     * Idempotent, safe to call on every launch.
     */
    fun migrate() {
        if (prefs.contains(KEY_MODEL_SOURCE)) {
            prefs.edit().remove(KEY_MODEL_SOURCE).apply()
        }
    }

    companion object {
        const val KEY_HAPTIC = "haptic"
        const val KEY_AUTO_SPACE = "auto_space"
        const val KEY_ACTIVE_MODEL_ID = "active_model_id"

        // Legacy key from the RTranslator pipeline; only referenced by migrate().
        private const val KEY_MODEL_SOURCE = "model_source"
    }
}
