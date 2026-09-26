package com.vishal.fillbyvoice.voice

import android.content.Context
import androidx.core.content.edit
import java.util.Locale

enum class Language(val locale: Locale) {
    HINDI(Locale.forLanguageTag("hi-IN")),
    ENGLISH(Locale.forLanguageTag("en-IN")),
}

private const val PREFS = "settings"
private const val KEY_LANGUAGE = "language"

// Saved on the phone only. Null until the user picks one on first launch.
fun Context.savedLanguage(): Language? =
    getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY_LANGUAGE, null)
        ?.let { name -> Language.entries.find { it.name == name } }

fun Context.saveLanguage(language: Language) =
    getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit { putString(KEY_LANGUAGE, language.name) }
