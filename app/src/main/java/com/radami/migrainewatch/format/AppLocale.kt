package com.radami.migrainewatch.format

import java.util.Locale

/**
 * The language and conventions every user-visible value is rendered in.
 *
 * Java's formatters default to the *device* locale, not the app's. Since the app's own text is
 * English and only English, defaulting produced screens that disagreed with themselves — a
 * German phone showed "Samstag" beside an English heading, and "12,0 hPa" inside an English
 * sentence. Anything the user reads is formatted against this instead.
 *
 * A constant while there is one set of strings. When translations arrive this becomes the
 * locale the app's resources actually resolved to, and everything built on it follows.
 */
object AppLocale {
    val DISPLAY: Locale = Locale.ENGLISH
}
