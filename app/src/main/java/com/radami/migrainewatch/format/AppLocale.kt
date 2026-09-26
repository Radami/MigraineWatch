package com.radami.migrainewatch.format

import java.util.Locale

/**
 * The language every user-visible value is rendered in. Java's formatters default to the
 * device locale, not the app's English-only text, so everything the user reads formats
 * against this instead.
 */
object AppLocale {
    val DISPLAY: Locale = Locale.ENGLISH
}
