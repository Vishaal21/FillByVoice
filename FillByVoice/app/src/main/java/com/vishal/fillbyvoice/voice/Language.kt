package com.vishal.fillbyvoice.voice

import android.content.Context
import androidx.core.content.edit
import java.util.Locale

// locale: the voice that speaks. listenLocale: the offline speech pack that listens.
// The phone's offline English pack is en-US; asking for en-IN gave "Speech error 13" (language not downloaded).
enum class Language(val locale: Locale, val listenLocale: Locale) {
    HINDI(Locale.forLanguageTag("hi-IN"), Locale.forLanguageTag("hi-IN")),
    ENGLISH(Locale.forLanguageTag("en-IN"), Locale.forLanguageTag("en-US")),
}

// The phone's words in the chosen language.
fun Language.pick(hi: String, en: String): String = if (this == Language.HINDI) hi else en

private const val PREFS = "settings"
private const val KEY_LANGUAGE = "language"

// Saved on the phone only.
fun Context.saveLanguage(language: Language) =
    getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit { putString(KEY_LANGUAGE, language.name) }
