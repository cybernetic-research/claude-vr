package dev.claudevr.panel

import android.content.Context

/** App settings in private app storage (only this app can read them). */
class Settings(context: Context) {
    private val prefs = context.getSharedPreferences("settings", Context.MODE_PRIVATE)

    var apiKey: String
        get() = prefs.getString("apiKey", "") ?: ""
        set(v) = prefs.edit().putString("apiKey", v.trim()).apply()

    var model: String
        get() = prefs.getString("model", DEFAULT_MODEL) ?: DEFAULT_MODEL
        set(v) = prefs.edit().putString("model", v.trim().ifEmpty { DEFAULT_MODEL }).apply()

    /** low / medium / high - how hard Claude thinks before answering. */
    var effort: String
        get() = prefs.getString("effort", "medium") ?: "medium"
        set(v) = prefs.edit().putString("effort", v).apply()

    var includeView: Boolean
        get() = prefs.getBoolean("includeView", true)
        set(v) = prefs.edit().putBoolean("includeView", v).apply()

    var speakReplies: Boolean
        get() = prefs.getBoolean("speakReplies", false)
        set(v) = prefs.edit().putBoolean("speakReplies", v).apply()

    companion object {
        const val DEFAULT_MODEL = "claude-opus-5"
        val EFFORTS = listOf("low", "medium", "high")
    }
}
