package com.zim.bubbletranslate

import android.content.Context
import java.util.Locale

/** The few settings the app remembers between runs. */
object Prefs {
    const val AUTO = "auto"

    /**
     * Languages the app can translate INTO but cannot read off the screen,
     * because the on-device text reader has no model for their alphabet
     * (Arabic, Cyrillic, Thai, Greek and so on).
     */
    val UNREADABLE = setOf(
        "ar", "be", "bg", "bn", "el", "fa", "gu", "he", "ka",
        "kn", "mk", "ru", "ta", "te", "th", "uk", "ur"
    )

    private fun sp(c: Context) = c.getSharedPreferences("settings", Context.MODE_PRIVATE)

    fun target(c: Context): String = sp(c).getString("target", "en") ?: "en"
    fun setTarget(c: Context, tag: String) = sp(c).edit().putString("target", tag).apply()

    fun source(c: Context): String = sp(c).getString("source", AUTO) ?: AUTO
    fun setSource(c: Context, tag: String) = sp(c).edit().putString("source", tag).apply()

    /** The writing system that worked last time, tried first on the next tap. */
    fun lastScript(c: Context): Script? =
        sp(c).getString("lastScript", null)?.let { name -> Script.values().firstOrNull { it.name == name } }

    fun setLastScript(c: Context, s: Script) = sp(c).edit().putString("lastScript", s.name).apply()

    fun bubbleX(c: Context, fallback: Int): Int = sp(c).getInt("bx", fallback)
    fun bubbleY(c: Context, fallback: Int): Int = sp(c).getInt("by", fallback)
    fun setBubblePos(c: Context, x: Int, y: Int) = sp(c).edit().putInt("bx", x).putInt("by", y).apply()

    /** "ja" becomes "Japanese", in the phone's own language. */
    fun name(tag: String): String {
        val n = Locale.forLanguageTag(tag).getDisplayName(Locale.getDefault())
        return n.replaceFirstChar { if (it.isLowerCase()) it.titlecase(Locale.getDefault()) else it.toString() }
    }
}
