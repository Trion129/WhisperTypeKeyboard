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

    var incognito: Boolean
        get() = prefs.getBoolean(KEY_INCOGNITO, false)
        set(value) = prefs.edit().putBoolean(KEY_INCOGNITO, value).apply()

    var doubleSpacePeriod: Boolean
        get() = prefs.getBoolean(KEY_DOUBLE_SPACE_PERIOD, true)
        set(value) = prefs.edit().putBoolean(KEY_DOUBLE_SPACE_PERIOD, value).apply()

    var sentenceCaps: Boolean
        get() = prefs.getBoolean(KEY_SENTENCE_CAPS, true)
        set(value) = prefs.edit().putBoolean(KEY_SENTENCE_CAPS, value).apply()

    var activeModelId: String
        get() = prefs.getString(KEY_ACTIVE_MODEL_ID, "") ?: ""
        set(value) = prefs.edit().putString(KEY_ACTIVE_MODEL_ID, value).apply()

    fun emojiRecents(): List<String> {
        val raw = prefs.getString(KEY_EMOJI_RECENTS, "") ?: ""
        if (raw.isBlank()) return emptyList()
        return raw.split('\u001f').filter { it.isNotEmpty() }
    }

    fun setEmojiRecents(items: List<String>) {
        prefs.edit().putString(KEY_EMOJI_RECENTS, items.joinToString("\u001f")).apply()
    }

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
        const val KEY_INCOGNITO = "incognito"
        const val KEY_DOUBLE_SPACE_PERIOD = "double_space_period"
        const val KEY_SENTENCE_CAPS = "sentence_caps"
        const val KEY_ACTIVE_MODEL_ID = "active_model_id"
        const val KEY_EMOJI_RECENTS = "emoji_recents"

        // Legacy key from the RTranslator pipeline; only referenced by migrate().
        private const val KEY_MODEL_SOURCE = "model_source"
    }
}
